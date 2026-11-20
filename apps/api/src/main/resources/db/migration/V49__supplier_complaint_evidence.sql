CREATE UNIQUE INDEX uq_supplier_complaint_event_status
    ON supplier_complaint_events(account_id,supplier_req_id,status);

-- Evidence is held in the existing private upload root; paths are never client supplied.
CREATE TABLE supplier_complaint_attachments (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES supplier_complaint_events(id),
    storage_key varchar(500) NOT NULL,
    content_type varchar(64) NOT NULL,
    file_size bigint NOT NULL CHECK (file_size > 0 AND file_size <= 10485760),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_supplier_complaint_attachments_event ON supplier_complaint_attachments(event_id);
