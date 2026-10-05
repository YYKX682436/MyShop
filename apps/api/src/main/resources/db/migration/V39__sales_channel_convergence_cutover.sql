-- S2.4A: per-listing authorization and immutable cutover evidence.
-- Existing rows deliberately receive no implicit production authorization.

ALTER TABLE sales_channel_listings
    ADD COLUMN convergence_cutover_state varchar(16) NOT NULL DEFAULT 'UNCERTIFIED',
    ADD COLUMN convergence_certification_id uuid,
    ADD COLUMN convergence_activated_at timestamp,
    ADD COLUMN convergence_paused_at timestamp;

CREATE TABLE sales_channel_cutover_certifications (
    id uuid PRIMARY KEY,
    sales_channel_listing_id uuid NOT NULL,
    desired_revision bigint NOT NULL,
    desired_state varchar(16) NOT NULL,
    desired_fingerprint varchar(64) NOT NULL,
    current_external_item_id varchar(128),
    agent_state_digest varchar(64) NOT NULL,
    requires_adoption boolean NOT NULL,
    bootstrap_operation_id uuid,
    evidence jsonb NOT NULL DEFAULT '{}',
    created_at timestamp NOT NULL,
    CONSTRAINT fk_sales_channel_cutover_cert_listing
        FOREIGN KEY (sales_channel_listing_id) REFERENCES sales_channel_listings(id) ON DELETE NO ACTION,
    CONSTRAINT fk_sales_channel_cutover_cert_bootstrap_operation
        FOREIGN KEY (bootstrap_operation_id) REFERENCES sales_channel_operations(id) ON DELETE NO ACTION,
    CONSTRAINT sales_channel_cutover_certifications_desired_state_check
        CHECK (desired_state IN ('ONLINE', 'OFFLINE')),
    CONSTRAINT sales_channel_cutover_certifications_revision_check
        CHECK (desired_revision >= 0),
    CONSTRAINT sales_channel_cutover_certifications_fingerprint_check
        CHECK (desired_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT sales_channel_cutover_certifications_agent_digest_check
        CHECK (agent_state_digest ~ '^[a-f0-9]{64}$')
);

CREATE INDEX idx_sales_channel_cutover_cert_listing_created
    ON sales_channel_cutover_certifications(sales_channel_listing_id, created_at);

ALTER TABLE sales_channel_listings
    ADD CONSTRAINT fk_sales_channel_listings_cutover_certification
        FOREIGN KEY (convergence_certification_id)
        REFERENCES sales_channel_cutover_certifications(id) ON DELETE NO ACTION,
    ADD CONSTRAINT sales_channel_listings_cutover_state_check
        CHECK (convergence_cutover_state IN ('UNCERTIFIED', 'CERTIFIED', 'ACTIVE', 'PAUSED')),
    ADD CONSTRAINT sales_channel_listings_cutover_evidence_check
        CHECK (
            (convergence_cutover_state = 'UNCERTIFIED'
                AND convergence_certification_id IS NULL
                AND convergence_activated_at IS NULL
                AND convergence_paused_at IS NULL)
            OR (convergence_cutover_state = 'CERTIFIED'
                AND convergence_certification_id IS NOT NULL
                AND convergence_activated_at IS NULL
                AND convergence_paused_at IS NULL)
            OR (convergence_cutover_state = 'ACTIVE'
                AND convergence_certification_id IS NOT NULL
                AND convergence_activated_at IS NOT NULL
                AND convergence_paused_at IS NULL)
            OR (convergence_cutover_state = 'PAUSED'
                AND convergence_certification_id IS NOT NULL
                AND convergence_activated_at IS NOT NULL
                AND convergence_paused_at IS NOT NULL)
        );

CREATE FUNCTION prevent_sales_channel_cutover_certification_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'sales_channel_cutover_certifications rows are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sales_channel_cutover_certifications_immutable
    BEFORE UPDATE OR DELETE ON sales_channel_cutover_certifications
    FOR EACH ROW EXECUTE FUNCTION prevent_sales_channel_cutover_certification_mutation();
