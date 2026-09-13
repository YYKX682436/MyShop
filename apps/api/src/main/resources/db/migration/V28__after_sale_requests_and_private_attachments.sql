-- Customer after-sale requests are historical records. Only one request for
-- an order may be pending at a time; rejected requests remain available for
-- audit and the same order may be submitted again afterwards.
ALTER TABLE orders
    DROP CONSTRAINT IF EXISTS orders_status_check;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM orders
        WHERE status NOT IN ('PENDING', 'PAID', 'DELIVERED', 'EXPIRED', 'REFUNDED', 'AFTER_SALE')
    ) THEN
        RAISE EXCEPTION 'Cannot add the after-sale order status contract';
    END IF;
END
$$;

ALTER TABLE orders
    ADD CONSTRAINT orders_status_check CHECK (
        status IN ('PENDING', 'PAID', 'DELIVERED', 'EXPIRED', 'REFUNDED', 'AFTER_SALE')
    );

CREATE TABLE after_sale_requests (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id),
    user_id uuid NOT NULL REFERENCES users(id),
    status varchar(16) NOT NULL,
    source_order_status varchar(16) NOT NULL,
    reason text NOT NULL,
    admin_reply varchar(500),
    handled_by uuid REFERENCES users(id),
    submitted_at timestamp NOT NULL,
    handled_at timestamp,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT after_sale_requests_status_check
        CHECK (status IN ('PENDING', 'REJECTED', 'REFUNDED')),
    CONSTRAINT after_sale_requests_source_status_check
        CHECK (source_order_status IN ('PAID', 'DELIVERED')),
    CONSTRAINT after_sale_requests_reason_check
        CHECK (char_length(btrim(reason)) BETWEEN 5 AND 1000),
    CONSTRAINT after_sale_requests_admin_reply_check
        CHECK (
            (status = 'REJECTED'
                AND admin_reply IS NOT NULL
                AND char_length(btrim(admin_reply)) BETWEEN 1 AND 500)
            OR
            (status <> 'REJECTED'
                AND (admin_reply IS NULL
                     OR char_length(btrim(admin_reply)) BETWEEN 1 AND 500))
        ),
    CONSTRAINT after_sale_requests_handled_check
        CHECK (
            (status = 'PENDING' AND handled_by IS NULL AND handled_at IS NULL)
            OR
            (status <> 'PENDING' AND handled_by IS NOT NULL AND handled_at IS NOT NULL)
        )
);

CREATE INDEX idx_after_sale_requests_user_submitted
    ON after_sale_requests(user_id, submitted_at DESC);

CREATE INDEX idx_after_sale_requests_order_submitted
    ON after_sale_requests(order_id, submitted_at DESC);

CREATE INDEX idx_after_sale_requests_status_submitted
    ON after_sale_requests(status, submitted_at DESC);

CREATE UNIQUE INDEX uq_after_sale_requests_pending_order
    ON after_sale_requests(order_id)
    WHERE status = 'PENDING';

CREATE TABLE after_sale_attachments (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES after_sale_requests(id) ON DELETE CASCADE,
    storage_key varchar(500) NOT NULL,
    original_filename varchar(255) NOT NULL,
    content_type varchar(64) NOT NULL,
    file_size bigint NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT after_sale_attachments_file_size_check
        CHECK (file_size > 0 AND file_size <= 5242880)
);

CREATE INDEX idx_after_sale_attachments_request_created
    ON after_sale_attachments(request_id, created_at ASC);

COMMENT ON TABLE after_sale_requests IS
    'Historical customer after-sale requests; only one PENDING request per order';
COMMENT ON TABLE after_sale_attachments IS
    'Private authenticated after-sale image metadata; storage_key is never client supplied';
