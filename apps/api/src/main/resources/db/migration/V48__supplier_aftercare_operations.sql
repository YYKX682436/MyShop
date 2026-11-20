CREATE TABLE supplier_aftercare_operations (
    id uuid PRIMARY KEY,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    account_id uuid NOT NULL REFERENCES supplier_accounts(id),
    purchase_id uuid NOT NULL REFERENCES supplier_purchases(id),
    kind varchar(16) NOT NULL CHECK (kind IN ('REFUND','COMPLAINT','CARD_VOID')),
    idempotency_key uuid NOT NULL,
    card_id uuid REFERENCES supplier_delivery_cards(id),
    after_sale_id uuid REFERENCES after_sale_requests(id),
    operator_id uuid NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('INTENT','SUBMITTING','UNKNOWN','ACCEPTED','COMPLETED','REJECTED')),
    topic varchar(200),
    content text,
    attachment_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    supplier_req_id bigint,
    upstream_status integer,
    result text,
    error_code varchar(64),
    submitted_at timestamp,
    last_checked_at timestamp,
    CONSTRAINT uq_supplier_aftercare_request UNIQUE(account_id,idempotency_key),
    CONSTRAINT supplier_aftercare_card_check CHECK ((kind='CARD_VOID') = (card_id IS NOT NULL))
);
-- The upstream protocol exposes no idempotency token for these actions.
CREATE UNIQUE INDEX uq_supplier_refund_once ON supplier_aftercare_operations(purchase_id) WHERE kind='REFUND';
CREATE UNIQUE INDEX uq_supplier_void_once ON supplier_aftercare_operations(card_id) WHERE kind='CARD_VOID';
CREATE UNIQUE INDEX uq_supplier_complaint_unresolved ON supplier_aftercare_operations(purchase_id)
    WHERE kind='COMPLAINT' AND status IN ('INTENT','SUBMITTING','UNKNOWN');
CREATE UNIQUE INDEX uq_supplier_complaint_req ON supplier_aftercare_operations(account_id,supplier_req_id)
    WHERE kind='COMPLAINT' AND supplier_req_id IS NOT NULL;
CREATE INDEX idx_supplier_aftercare_recovery ON supplier_aftercare_operations(kind,status,last_checked_at,created_at);

ALTER TABLE supplier_delivery_cards ADD COLUMN last_checked_at timestamp;
ALTER TABLE supplier_delivery_cards ADD COLUMN supplier_result text;

CREATE TABLE supplier_complaint_events (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES supplier_accounts(id),
    operation_id uuid NOT NULL REFERENCES supplier_aftercare_operations(id),
    supplier_req_id bigint NOT NULL,
    event_hash varchar(64) NOT NULL,
    status integer NOT NULL CHECK (status BETWEEN 1 AND 4),
    reply text NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_supplier_complaint_event UNIQUE(account_id,event_hash)
);
