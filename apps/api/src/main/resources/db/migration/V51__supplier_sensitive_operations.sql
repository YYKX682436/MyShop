-- Money-changing supplier operations are never retried after a committed claim.
CREATE TABLE supplier_sensitive_operations (
    id uuid PRIMARY KEY,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    account_id uuid NOT NULL REFERENCES supplier_accounts(id),
    account_name_snapshot varchar(120) NOT NULL,
    provider_type_snapshot varchar(32) NOT NULL,
    base_url_snapshot varchar(500) NOT NULL,
    app_id_snapshot varchar(120) NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('TRANSFER','CDKEY')),
    idempotency_key uuid NOT NULL,
    operator_id uuid NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('INTENT','SUBMITTING','UNKNOWN','SUCCEEDED','REJECTED')),
    outer_number varchar(100),
    parameters jsonb NOT NULL,
    encrypted_result text,
    error_code varchar(64),
    submitted_at timestamp,
    review_outcome varchar(40),
    reviewed_by uuid,
    reviewed_at timestamp,
    CONSTRAINT uq_supplier_sensitive_request UNIQUE (account_id,idempotency_key),
    CONSTRAINT uq_supplier_transfer_outer UNIQUE (outer_number),
    CONSTRAINT supplier_sensitive_outer_check CHECK ((kind='TRANSFER') = (outer_number IS NOT NULL)),
    CONSTRAINT supplier_sensitive_result_check CHECK (encrypted_result IS NULL OR (kind='CDKEY' AND status='SUCCEEDED'))
);
CREATE INDEX idx_supplier_sensitive_account ON supplier_sensitive_operations(account_id,kind,created_at DESC);
CREATE INDEX idx_supplier_sensitive_recovery ON supplier_sensitive_operations(status,submitted_at);
