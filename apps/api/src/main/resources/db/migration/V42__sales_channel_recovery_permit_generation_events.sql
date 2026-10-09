CREATE TABLE sales_channel_recovery_permit_events (
    event_id BIGSERIAL PRIMARY KEY,
    permit_id UUID NOT NULL REFERENCES sales_channel_recovery_permits(id) ON DELETE RESTRICT,
    generation BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    previous_generation BIGINT,
    previous_state VARCHAR(32),
    new_state VARCHAR(32) NOT NULL,
    operator VARCHAR(128) NOT NULL,
    reason TEXT NOT NULL,
    event_at TIMESTAMP,
    recorded_at TIMESTAMP NOT NULL DEFAULT now(),
    execution_fence BIGINT NOT NULL,
    identity_fingerprint VARCHAR(64) NOT NULL,
    evidence_json JSONB NOT NULL
);

CREATE INDEX idx_sales_channel_recovery_event_generation
    ON sales_channel_recovery_permit_events (permit_id, generation, event_id);

-- The old row contains the exact saved operator/reason, state, fences,
-- preconditions, and lifecycle timestamps. Keep them as an explicitly
-- observed snapshot; do not invent historical transition timestamps.
INSERT INTO sales_channel_recovery_permit_events (
    permit_id, generation, event_type, previous_generation, previous_state, new_state,
    operator, reason, event_at, execution_fence, identity_fingerprint, evidence_json
)
SELECT p.id, p.generation, 'HISTORICAL_SNAPSHOT', NULL, NULL, p.state,
       p.operator, p.reason, NULL, p.execution_fence,
       encode(sha256(convert_to(concat_ws(E'\n',
           lower(p.id::text), p.generation::text, p.account_id,
           lower(p.sales_channel_listing_id::text), lower(p.myshop_operation_id::text),
           p.agent_operation_id::text, p.request_id, p.desired_revision::text,
           p.desired_fingerprint, p.expected_old_item_id), 'UTF8')), 'hex'),
       jsonb_build_object(
           'snapshot_kind', 'migration_observation',
           'prepared_at', p.prepared_at,
           'armed_at', p.armed_at,
           'go_at', p.go_at,
           'execution_started_at', p.execution_started_at,
           'terminal_at', p.terminal_at,
           'execution_outcome', p.execution_outcome,
           'execution_fence', p.execution_fence,
           'updated_at_snapshot', p.updated_at,
           'preconditions_json', p.preconditions_json,
           'agent_preconditions_json', p.agent_preconditions_json
       )
FROM sales_channel_recovery_permits p
WHERE NOT EXISTS (
    SELECT 1 FROM sales_channel_recovery_permit_events e
    WHERE e.permit_id = p.id AND e.generation = p.generation
      AND e.event_type = 'HISTORICAL_SNAPSHOT'
);
