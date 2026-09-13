-- V29 turns each after-sale request into an immutable conversation timeline.
-- V28 remains the historical schema source; this migration is the only place
-- that renames its administrator reply field and adds the conversation data.

ALTER TABLE after_sale_requests
    RENAME COLUMN admin_reply TO rejection_reason;

ALTER TABLE after_sale_requests
    DROP CONSTRAINT IF EXISTS after_sale_requests_admin_reply_check;

ALTER TABLE after_sale_requests
    ADD CONSTRAINT after_sale_requests_rejection_reason_check
        CHECK (
            (status = 'REJECTED'
                AND rejection_reason IS NOT NULL
                AND char_length(btrim(rejection_reason)) BETWEEN 1 AND 500)
            OR
            (status <> 'REJECTED'
                AND rejection_reason IS NULL)
        );

ALTER TABLE after_sale_requests
    ADD COLUMN waiting_for varchar(16),
    ADD COLUMN last_activity_at timestamp;

UPDATE after_sale_requests
SET waiting_for = CASE WHEN status = 'PENDING' THEN 'ADMIN' ELSE NULL END,
    last_activity_at = COALESCE(handled_at, submitted_at);

ALTER TABLE after_sale_requests
    ALTER COLUMN last_activity_at SET NOT NULL;

ALTER TABLE after_sale_requests
    ADD CONSTRAINT after_sale_requests_waiting_for_check
        CHECK (
            (status = 'PENDING' AND waiting_for IN ('ADMIN', 'CUSTOMER'))
            OR
            (status <> 'PENDING' AND waiting_for IS NULL)
        );

CREATE INDEX idx_after_sale_requests_pending_attention
    ON after_sale_requests(waiting_for, last_activity_at DESC, order_id)
    WHERE status = 'PENDING';

CREATE TABLE after_sale_messages (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES after_sale_requests(id) ON DELETE CASCADE,
    sender_type varchar(16) NOT NULL,
    sender_user_id uuid NOT NULL REFERENCES users(id),
    content text NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT after_sale_messages_sender_type_check
        CHECK (sender_type IN ('CUSTOMER', 'ADMIN')),
    CONSTRAINT after_sale_messages_content_check
        CHECK (char_length(btrim(content)) BETWEEN 1 AND 1000),
    CONSTRAINT uq_after_sale_messages_id_request
        UNIQUE (id, request_id)
);

CREATE INDEX idx_after_sale_messages_request_created
    ON after_sale_messages(request_id, created_at ASC, id ASC);

ALTER TABLE after_sale_attachments
    ADD COLUMN message_id uuid;

ALTER TABLE after_sale_attachments
    ADD CONSTRAINT after_sale_attachments_message_request_fkey
        FOREIGN KEY (message_id, request_id)
        REFERENCES after_sale_messages(id, request_id)
        ON DELETE CASCADE;

CREATE INDEX idx_after_sale_attachments_message_created
    ON after_sale_attachments(message_id, created_at ASC)
    WHERE message_id IS NOT NULL;

COMMENT ON TABLE after_sale_messages IS
    'Immutable customer and administrator after-sale conversation messages';
COMMENT ON COLUMN after_sale_requests.rejection_reason IS
    'Administrator explanation required only when the request is rejected';
COMMENT ON COLUMN after_sale_requests.waiting_for IS
    'The side expected to send the next message while the request is pending';
COMMENT ON COLUMN after_sale_requests.last_activity_at IS
    'Last application, reply, rejection, or approval activity time';
COMMENT ON COLUMN after_sale_attachments.message_id IS
    'Null for initial application evidence; set for reply evidence';
