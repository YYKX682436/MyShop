CREATE TABLE support_conversations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id uuid REFERENCES subsites(id),
    site_scope uuid GENERATED ALWAYS AS (coalesce(site_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
    user_id uuid REFERENCES users(id),
    guest_token_hash bytea,
    order_id uuid REFERENCES orders(id),
    order_scope uuid GENERATED ALWAYS AS (coalesce(order_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((user_id IS NOT NULL AND guest_token_hash IS NULL) OR
           (user_id IS NULL AND guest_token_hash IS NOT NULL AND octet_length(guest_token_hash)=32)),
    CHECK (site_id IS NULL OR site_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    CHECK (order_id IS NULL OR order_id <> '00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE UNIQUE INDEX uq_support_conversation_user ON support_conversations(site_scope,user_id,order_scope)
    WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX uq_support_conversation_guest ON support_conversations(site_scope,guest_token_hash,order_scope)
    WHERE guest_token_hash IS NOT NULL;
CREATE INDEX idx_support_conversation_order ON support_conversations(order_id) WHERE order_id IS NOT NULL;

CREATE TABLE support_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES support_conversations(id),
    sender_type varchar(16) NOT NULL CHECK (sender_type IN ('CUSTOMER','ADMIN')),
    sender_user_id uuid REFERENCES users(id),
    content text NOT NULL CHECK (char_length(content) <= 1000),
    client_key uuid NOT NULL,
    request_fingerprint bytea NOT NULL CHECK (octet_length(request_fingerprint)=32),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (conversation_id,sender_type,client_key)
);
CREATE INDEX idx_support_messages_conversation ON support_messages(conversation_id,created_at,id);

CREATE TABLE support_attachments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL REFERENCES support_messages(id),
    storage_key varchar(500) NOT NULL UNIQUE,
    original_filename varchar(255) NOT NULL,
    content_type varchar(64) NOT NULL,
    file_size bigint NOT NULL CHECK (file_size > 0 AND file_size <= 10485760),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_support_attachments_message ON support_attachments(message_id,id);

CREATE SEQUENCE support_activity_seq;
CREATE TABLE support_activity (
    seq bigint PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES support_conversations(id),
    source_type varchar(32) NOT NULL CHECK (source_type IN
        ('AFTER_SALE_APPLY','AFTER_SALE_MESSAGE','AFTER_SALE_REJECT','ORDER_REFUND','SUPPLIER_UPDATE','SUPPORT_MESSAGE')),
    source_id uuid NOT NULL,
    happened_at timestamp,
    notify_customer boolean NOT NULL,
    notify_admin boolean NOT NULL,
    legacy boolean NOT NULL DEFAULT false,
    UNIQUE (source_type,source_id)
);
CREATE INDEX idx_support_activity_conversation_seq ON support_activity(conversation_id,seq);

CREATE TABLE support_read_states (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES support_conversations(id),
    viewer_type varchar(16) NOT NULL CHECK (viewer_type IN ('CUSTOMER','ADMIN')),
    viewer_user_id uuid REFERENCES users(id),
    read_seq bigint NOT NULL CHECK (read_seq >= 0),
    read_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((viewer_type='CUSTOMER' AND viewer_user_id IS NULL) OR
           (viewer_type='ADMIN' AND viewer_user_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_support_read_customer ON support_read_states(conversation_id) WHERE viewer_type='CUSTOMER';
CREATE UNIQUE INDEX uq_support_read_admin ON support_read_states(conversation_id,viewer_user_id) WHERE viewer_type='ADMIN';

ALTER TABLE after_sale_messages ADD COLUMN client_key uuid;
ALTER TABLE after_sale_messages ADD COLUMN request_fingerprint bytea;
ALTER TABLE after_sale_requests ADD CONSTRAINT uq_after_sale_request_id_order UNIQUE (id,order_id);
ALTER TABLE after_sale_messages ADD COLUMN order_id uuid;
-- Backfill only a foreign key. Historical message text may already reference
-- a deleted media URL, so keep it intact without treating it as a new edit.
ALTER TABLE after_sale_messages DISABLE TRIGGER myshop_media_guard;
UPDATE after_sale_messages m SET order_id=r.order_id FROM after_sale_requests r WHERE r.id=m.request_id;
ALTER TABLE after_sale_messages ENABLE TRIGGER myshop_media_guard;
ALTER TABLE after_sale_messages ALTER COLUMN order_id SET NOT NULL;
ALTER TABLE after_sale_messages ADD CONSTRAINT fk_after_sale_message_request_order
    FOREIGN KEY (request_id,order_id) REFERENCES after_sale_requests(id,order_id);
ALTER TABLE after_sale_messages ADD CONSTRAINT after_sale_message_idempotency_check CHECK
    ((client_key IS NULL AND request_fingerprint IS NULL) OR
     (client_key IS NOT NULL AND request_fingerprint IS NOT NULL AND octet_length(request_fingerprint)=32));
CREATE UNIQUE INDEX uq_after_sale_message_client_key ON after_sale_messages(order_id,sender_type,sender_user_id,client_key)
    WHERE client_key IS NOT NULL;

CREATE FUNCTION support_bind_after_sale_order() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actual_order uuid;
BEGIN
    SELECT order_id INTO actual_order FROM after_sale_requests WHERE id=NEW.request_id;
    IF NEW.order_id IS NOT NULL AND NEW.order_id IS DISTINCT FROM actual_order THEN
        RAISE EXCEPTION 'after-sale message order mismatch' USING ERRCODE='23514';
    END IF;
    NEW.order_id := actual_order;
    RETURN NEW;
END $$;
CREATE TRIGGER support_bind_after_sale_order BEFORE INSERT OR UPDATE OF request_id,order_id ON after_sale_messages
    FOR EACH ROW EXECUTE FUNCTION support_bind_after_sale_order();

CREATE TRIGGER myshop_media_guard BEFORE INSERT OR UPDATE ON support_conversations FOR EACH ROW EXECUTE FUNCTION public.myshop_media_guard_write();
CREATE TRIGGER myshop_media_guard BEFORE INSERT OR UPDATE ON support_messages FOR EACH ROW EXECUTE FUNCTION public.myshop_media_guard_write();
CREATE TRIGGER myshop_media_guard BEFORE INSERT OR UPDATE ON support_attachments FOR EACH ROW EXECUTE FUNCTION public.myshop_media_guard_write();
CREATE TRIGGER myshop_media_guard BEFORE INSERT OR UPDATE ON support_activity FOR EACH ROW EXECUTE FUNCTION public.myshop_media_guard_write();
CREATE TRIGGER myshop_media_guard BEFORE INSERT OR UPDATE ON support_read_states FOR EACH ROW EXECUTE FUNCTION public.myshop_media_guard_write();

-- Assign the cursor only after taking a per-conversation transaction lock.
-- Commit order and cursor order then agree, even when a transaction stalls.
CREATE FUNCTION support_assign_activity_seq() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.conversation_id::text, 724386));
    NEW.seq := nextval('support_activity_seq');
    RETURN NEW;
END $$;
CREATE TRIGGER support_activity_assign_seq BEFORE INSERT ON support_activity
    FOR EACH ROW EXECUTE FUNCTION support_assign_activity_seq();

CREATE FUNCTION support_order_conversation(target_order uuid) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE found_id uuid;
BEGIN
    INSERT INTO support_conversations(site_id,user_id,order_id)
    SELECT subsite_id,user_id,id FROM orders WHERE id=target_order AND user_id IS NOT NULL AND order_type='NORMAL'
    ON CONFLICT DO NOTHING;
    SELECT c.id INTO found_id FROM support_conversations c JOIN orders o ON o.id=c.order_id
      WHERE o.id=target_order AND c.user_id=o.user_id AND c.site_id IS NOT DISTINCT FROM o.subsite_id;
    RETURN found_id;
END $$;

-- Only orders already containing a sale or a committed refund get a thread.
INSERT INTO support_conversations(site_id,user_id,order_id)
SELECT o.subsite_id,o.user_id,o.id FROM orders o
WHERE o.user_id IS NOT NULL AND o.order_type='NORMAL'
  AND (o.status='REFUNDED' OR EXISTS (SELECT 1 FROM after_sale_requests r WHERE r.order_id=o.id))
ON CONFLICT DO NOTHING;

INSERT INTO support_activity(conversation_id,source_type,source_id,happened_at,notify_customer,notify_admin,legacy)
SELECT c.id,e.source_type,e.source_id,e.happened_at,e.notify_customer,e.notify_admin,true
FROM (
    SELECT r.order_id,'AFTER_SALE_APPLY' AS source_type,r.id AS source_id,r.submitted_at AS happened_at,false AS notify_customer,true AS notify_admin FROM after_sale_requests r
    UNION ALL SELECT r.order_id,'AFTER_SALE_MESSAGE',m.id,m.created_at,m.sender_type='ADMIN',m.sender_type='CUSTOMER'
      FROM after_sale_messages m JOIN after_sale_requests r ON r.id=m.request_id
    UNION ALL SELECT r.order_id,'AFTER_SALE_REJECT',r.id,r.handled_at,true,false FROM after_sale_requests r WHERE r.status='REJECTED'
    UNION ALL SELECT o.id,'ORDER_REFUND',o.id,o.refunded_at,true,false FROM orders o WHERE o.status='REFUNDED'
    UNION ALL SELECT r.order_id,'SUPPLIER_UPDATE',e.id,e.created_at,true,true FROM supplier_complaint_events e
      JOIN supplier_aftercare_operations op ON op.id=e.operation_id JOIN after_sale_requests r ON r.id=op.after_sale_id
) e JOIN support_conversations c ON c.order_id=e.order_id
ORDER BY e.happened_at ASC NULLS FIRST,e.source_type,e.source_id;

CREATE FUNCTION support_after_sale_activity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE conversation uuid;
BEGIN
    IF TG_OP='INSERT' THEN
        conversation := support_order_conversation(NEW.order_id);
        IF conversation IS NOT NULL THEN
            INSERT INTO support_activity(conversation_id,source_type,source_id,happened_at,notify_customer,notify_admin)
            VALUES (conversation,'AFTER_SALE_APPLY',NEW.id,NEW.submitted_at,false,true) ON CONFLICT DO NOTHING;
        END IF;
    ELSIF NEW.status='REJECTED' AND OLD.status IS DISTINCT FROM NEW.status THEN
        conversation := support_order_conversation(NEW.order_id);
        IF conversation IS NOT NULL THEN
            INSERT INTO support_activity(conversation_id,source_type,source_id,happened_at,notify_customer,notify_admin)
            VALUES (conversation,'AFTER_SALE_REJECT',NEW.id,NEW.handled_at,true,false) ON CONFLICT DO NOTHING;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER support_after_sale_activity AFTER INSERT OR UPDATE OF status ON after_sale_requests
    FOR EACH ROW EXECUTE FUNCTION support_after_sale_activity();

CREATE FUNCTION support_after_sale_message_activity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE conversation uuid; target_order uuid;
BEGIN
    SELECT order_id INTO target_order FROM after_sale_requests WHERE id=NEW.request_id;
    conversation := support_order_conversation(target_order);
    IF conversation IS NOT NULL THEN
        INSERT INTO support_activity(conversation_id,source_type,source_id,happened_at,notify_customer,notify_admin)
        VALUES (conversation,'AFTER_SALE_MESSAGE',NEW.id,NEW.created_at,NEW.sender_type='ADMIN',NEW.sender_type='CUSTOMER') ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER support_after_sale_message_activity AFTER INSERT ON after_sale_messages
    FOR EACH ROW EXECUTE FUNCTION support_after_sale_message_activity();

CREATE FUNCTION support_refund_activity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE conversation uuid;
BEGIN
    IF NEW.status='REFUNDED' AND OLD.status IS DISTINCT FROM NEW.status THEN
        conversation := support_order_conversation(NEW.id);
        IF conversation IS NOT NULL THEN
            INSERT INTO support_activity(conversation_id,source_type,source_id,happened_at,notify_customer,notify_admin)
            VALUES (conversation,'ORDER_REFUND',NEW.id,NEW.refunded_at,true,false) ON CONFLICT DO NOTHING;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER support_refund_activity AFTER UPDATE OF status ON orders FOR EACH ROW EXECUTE FUNCTION support_refund_activity();

CREATE FUNCTION support_supplier_activity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE conversation uuid; target_order uuid;
BEGIN
    SELECT r.order_id INTO target_order FROM supplier_aftercare_operations op
      JOIN after_sale_requests r ON r.id=op.after_sale_id WHERE op.id=NEW.operation_id;
    IF target_order IS NOT NULL THEN
        conversation := support_order_conversation(target_order);
        IF conversation IS NOT NULL THEN
            INSERT INTO support_activity(conversation_id,source_type,source_id,happened_at,notify_customer,notify_admin)
            VALUES (conversation,'SUPPLIER_UPDATE',NEW.id,NEW.created_at,true,true) ON CONFLICT DO NOTHING;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER support_supplier_activity AFTER INSERT ON supplier_complaint_events
    FOR EACH ROW EXECUTE FUNCTION support_supplier_activity();

CREATE FUNCTION support_message_activity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO support_activity(conversation_id,source_type,source_id,happened_at,notify_customer,notify_admin)
    VALUES (NEW.conversation_id,'SUPPORT_MESSAGE',NEW.id,NEW.created_at,NEW.sender_type='ADMIN',NEW.sender_type='CUSTOMER')
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER support_message_activity AFTER INSERT ON support_messages FOR EACH ROW EXECUTE FUNCTION support_message_activity();
