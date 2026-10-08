CREATE TABLE sales_channel_recovery_permits (
    id UUID PRIMARY KEY,
    generation BIGINT NOT NULL,
    sales_channel_listing_id UUID NOT NULL REFERENCES sales_channel_listings(id),
    myshop_operation_id UUID NOT NULL UNIQUE REFERENCES sales_channel_operations(id),
    agent_operation_id BIGINT NOT NULL,
    account_id VARCHAR(80) NOT NULL,
    request_id VARCHAR(255) NOT NULL UNIQUE,
    desired_revision BIGINT NOT NULL,
    desired_fingerprint VARCHAR(64) NOT NULL,
    expected_old_item_id VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    operator VARCHAR(128) NOT NULL,
    reason TEXT NOT NULL,
    preconditions_json JSONB NOT NULL,
    agent_preconditions_json JSONB,
    execution_outcome VARCHAR(64),
    execution_fence BIGINT NOT NULL DEFAULT 0,
    prepared_at TIMESTAMP NOT NULL,
    armed_at TIMESTAMP,
    go_operator VARCHAR(128),
    go_at TIMESTAMP,
    execution_started_at TIMESTAMP,
    terminal_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_sales_channel_recovery_state CHECK
        (state IN ('PREPARED', 'ARMED', 'GO_PENDING', 'ACTIVE', 'EXECUTING', 'RECOVERY_REQUIRED', 'TERMINAL', 'CANCEL_PENDING', 'CANCELLED')),
    CONSTRAINT uk_sales_channel_recovery_operation_generation UNIQUE (myshop_operation_id, generation)
);

CREATE INDEX idx_sales_channel_recovery_scope_state
    ON sales_channel_recovery_permits (state, sales_channel_listing_id, created_at);
