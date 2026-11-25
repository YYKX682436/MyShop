-- Replace the retired banner/popup settings with notification audiences and
-- give existing support activity a stable customer timeline without copying
-- messages, attachments, or read state.

DROP TABLE IF EXISTS site_announcement_channels;

-- V59 imported the then-current subsite text. Carry forward any older site
-- rows that were edited after that migration, while preserving unknown dates
-- and retaining an already managed notice with the same source key.
INSERT INTO site_announcements(site_id, source_key, title, body, status, publish_version, published_at)
SELECT id, 'legacy:subsite:' || id::text, '网站公告', announcement, 'PUBLISHED', 1, NULL
FROM subsites
WHERE btrim(coalesce(announcement, '')) <> ''
ON CONFLICT (source_key) DO NOTHING;

ALTER TABLE site_announcements
    ALTER COLUMN title DROP NOT NULL,
    DROP CONSTRAINT site_announcements_title_check,
    ADD CONSTRAINT site_announcements_title_check
        CHECK (title IS NULL OR char_length(btrim(title)) BETWEEN 1 AND 200),
    ADD COLUMN audience_type varchar(16) NOT NULL DEFAULT 'ALL',
    ADD COLUMN audience_level_ids uuid[] NOT NULL DEFAULT ARRAY[]::uuid[],
    ADD COLUMN create_key uuid,
    ADD COLUMN create_fingerprint bytea,
    ADD CONSTRAINT site_announcements_audience_check CHECK (
        (audience_type = 'ALL' AND cardinality(audience_level_ids) = 0)
        OR (audience_type = 'LEVELS' AND cardinality(audience_level_ids) > 0)
    ),
    ADD CONSTRAINT site_announcements_create_fingerprint_check CHECK (
        (create_key IS NULL AND create_fingerprint IS NULL)
        OR (create_key IS NOT NULL AND create_fingerprint IS NOT NULL AND octet_length(create_fingerprint) = 32)
    );
CREATE UNIQUE INDEX uq_site_announcements_create_key
    ON site_announcements(site_scope, create_key) WHERE create_key IS NOT NULL;

DELETE FROM site_configs
WHERE config_key IN ('announcement_enabled', 'announcement', 'popup_enabled', 'popup_content');
ALTER TABLE subsites DROP COLUMN IF EXISTS announcement;

-- Orders and sale rounds belong to individual messages. The conversation row
-- is only the stable identity/site anchor for the combined timeline.
ALTER TABLE support_messages
    ADD COLUMN order_id uuid REFERENCES orders(id),
    ADD COLUMN after_sale_request_id uuid,
    ADD COLUMN source_announcement_id uuid REFERENCES site_announcements(id),
    ADD CONSTRAINT support_message_after_sale_order_check
        CHECK (after_sale_request_id IS NULL OR order_id IS NOT NULL),
    ADD CONSTRAINT fk_support_message_after_sale_request_order
        FOREIGN KEY (after_sale_request_id, order_id)
        REFERENCES after_sale_requests(id, order_id);
CREATE INDEX idx_support_messages_order ON support_messages(order_id, created_at, id)
    WHERE order_id IS NOT NULL;
CREATE INDEX idx_support_messages_source_announcement ON support_messages(source_announcement_id)
    WHERE source_announcement_id IS NOT NULL;

-- New roots are identity anchors, not copied conversations. Old activity and
-- per-conversation read positions remain attached to their original rows.
INSERT INTO support_conversations(site_id, user_id, order_id)
SELECT site_id, user_id, NULL
FROM support_conversations
WHERE user_id IS NOT NULL
GROUP BY site_id, user_id
ON CONFLICT DO NOTHING;

INSERT INTO support_conversations(site_id, guest_token_hash, order_id)
SELECT site_id, guest_token_hash, NULL
FROM support_conversations
WHERE guest_token_hash IS NOT NULL
GROUP BY site_id, guest_token_hash
ON CONFLICT DO NOTHING;

-- Receipts record only activity actually rendered to a viewer. Legacy
-- read_seq positions remain valid as a compact read prefix.
CREATE UNIQUE INDEX uq_support_activity_conversation_seq
    ON support_activity(conversation_id, seq);
CREATE TABLE support_activity_receipts (
    conversation_id uuid NOT NULL,
    seq bigint NOT NULL,
    viewer_type varchar(16) NOT NULL CHECK (viewer_type IN ('CUSTOMER', 'ADMIN')),
    viewer_user_id uuid REFERENCES users(id),
    reader_key uuid GENERATED ALWAYS AS
        (coalesce(viewer_user_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
    read_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, seq, viewer_type, reader_key),
    FOREIGN KEY (conversation_id, seq)
        REFERENCES support_activity(conversation_id, seq) ON DELETE CASCADE,
    CHECK ((viewer_type = 'CUSTOMER' AND viewer_user_id IS NULL)
        OR (viewer_type = 'ADMIN' AND viewer_user_id IS NOT NULL))
);
CREATE INDEX idx_support_activity_receipts_viewer
    ON support_activity_receipts(viewer_type, viewer_user_id, seq);

ALTER TABLE support_activity DROP CONSTRAINT support_activity_source_type_check;
ALTER TABLE support_activity ADD CONSTRAINT support_activity_source_type_check
    CHECK (source_type IN ('AFTER_SALE_APPLY', 'AFTER_SALE_MESSAGE', 'AFTER_SALE_REJECT',
        'ORDER_REFUND', 'SUPPLIER_UPDATE', 'SUPPORT_MESSAGE', 'SYSTEM_RESULT'));

CREATE TABLE support_system_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_type varchar(32) NOT NULL CHECK (source_type IN ('ACCOUNT_PAYMENT', 'BALANCE_TRANSACTION', 'ORDER')),
    source_id uuid NOT NULL,
    event_type varchar(32) NOT NULL CHECK (event_type IN (
        'RECHARGE_CREDITED', 'LEVEL_UPGRADE_COMPLETED', 'LEVEL_UPGRADE_RECOVERED',
        'BALANCE_ADJUSTMENT', 'ORDER_DELIVERED')),
    conversation_id uuid NOT NULL REFERENCES support_conversations(id),
    user_id uuid NOT NULL REFERENCES users(id),
    site_id uuid REFERENCES subsites(id),
    order_id uuid REFERENCES orders(id),
    amount numeric(12,2),
    direction varchar(8) NOT NULL DEFAULT 'NONE' CHECK (direction IN ('NONE', 'CREDIT', 'DEBIT')),
    balance_transaction_id uuid REFERENCES balance_transactions(id),
    balance_after numeric(12,2),
    target_level_id uuid REFERENCES user_levels(id),
    target_level_name varchar(100),
    happened_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_type, source_id),
    CHECK (amount IS NULL OR amount >= 0),
    CHECK (balance_after IS NULL OR balance_after >= 0),
    CHECK ((event_type = 'ORDER_DELIVERED' AND order_id IS NOT NULL)
        OR (event_type <> 'ORDER_DELIVERED' AND order_id IS NULL)),
    CHECK ((event_type = 'BALANCE_ADJUSTMENT' AND balance_transaction_id IS NOT NULL
                AND amount IS NOT NULL AND direction IN ('CREDIT', 'DEBIT') AND balance_after IS NOT NULL)
        OR event_type <> 'BALANCE_ADJUSTMENT'),
    CHECK ((event_type IN ('RECHARGE_CREDITED', 'LEVEL_UPGRADE_RECOVERED')
                AND balance_transaction_id IS NOT NULL AND amount IS NOT NULL AND direction = 'CREDIT')
        OR event_type NOT IN ('RECHARGE_CREDITED', 'LEVEL_UPGRADE_RECOVERED')),
    CHECK ((event_type = 'LEVEL_UPGRADE_COMPLETED' AND target_level_id IS NOT NULL AND target_level_name IS NOT NULL)
        OR event_type <> 'LEVEL_UPGRADE_COMPLETED')
);
CREATE INDEX idx_support_system_events_conversation
    ON support_system_events(conversation_id, happened_at, id);

CREATE FUNCTION support_ensure_customer_root(target_site uuid, target_user uuid)
RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE root_id uuid;
BEGIN
    INSERT INTO support_conversations(site_id, user_id, order_id)
    VALUES (target_site, target_user, NULL)
    ON CONFLICT DO NOTHING;
    SELECT id INTO root_id FROM support_conversations
    WHERE site_scope = coalesce(target_site, '00000000-0000-0000-0000-000000000000'::uuid)
      AND user_id = target_user AND order_id IS NULL;
    RETURN root_id;
END $$;

CREATE OR REPLACE FUNCTION support_order_conversation(target_order uuid) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE found_id uuid;
BEGIN
    INSERT INTO support_conversations(site_id,user_id,order_id)
    SELECT subsite_id,user_id,id FROM orders WHERE id=target_order AND user_id IS NOT NULL AND order_type='NORMAL'
    ON CONFLICT DO NOTHING;
    SELECT c.id INTO found_id FROM support_conversations c JOIN orders o ON o.id=c.order_id
      WHERE o.id=target_order AND c.user_id=o.user_id AND c.site_id IS NOT DISTINCT FROM o.subsite_id;
    PERFORM support_ensure_customer_root(o.subsite_id,o.user_id)
      FROM orders o WHERE o.id=target_order AND o.user_id IS NOT NULL AND o.order_type='NORMAL';
    RETURN found_id;
END $$;

CREATE FUNCTION support_system_event_activity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO support_activity(conversation_id, source_type, source_id, happened_at,
                                 notify_customer, notify_admin)
    VALUES (NEW.conversation_id, 'SYSTEM_RESULT', NEW.id, NEW.happened_at, true, true)
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER support_system_event_activity AFTER INSERT ON support_system_events
    FOR EACH ROW EXECUTE FUNCTION support_system_event_activity();

-- Account results come from the final persisted transaction state. A callback
-- that only reports payment received cannot create a customer success event.
CREATE FUNCTION support_account_payment_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    root_id uuid;
    result_type varchar(32);
    ledger_id uuid;
    target_name varchar(100);
BEGIN
    IF NEW.status NOT IN ('COMPLETED', 'RECOVERED')
       OR (TG_OP = 'UPDATE' AND OLD.status IS NOT DISTINCT FROM NEW.status) THEN
        RETURN NEW;
    END IF;
    result_type := CASE
        WHEN NEW.type = 'RECHARGE' THEN 'RECHARGE_CREDITED'
        WHEN NEW.status = 'RECOVERED' THEN 'LEVEL_UPGRADE_RECOVERED'
        ELSE 'LEVEL_UPGRADE_COMPLETED' END;
    SELECT id INTO ledger_id FROM balance_transactions
    WHERE account_payment_transaction_id = NEW.id
      AND type = CASE WHEN result_type = 'LEVEL_UPGRADE_COMPLETED' THEN 'LEVEL_UPGRADE' ELSE 'RECHARGE' END
    ORDER BY created_at DESC, id DESC LIMIT 1;
    SELECT name INTO target_name FROM user_levels WHERE id = NEW.target_level_id;
    root_id := support_ensure_customer_root(NEW.subsite_id, NEW.user_id);
    INSERT INTO support_system_events(source_type, source_id, event_type, conversation_id,
        user_id, site_id, amount, direction, balance_transaction_id,
        target_level_id, target_level_name, happened_at)
    VALUES ('ACCOUNT_PAYMENT', NEW.id, result_type, root_id, NEW.user_id, NEW.subsite_id,
        NEW.amount, CASE WHEN result_type IN ('RECHARGE_CREDITED', 'LEVEL_UPGRADE_RECOVERED') THEN 'CREDIT' ELSE 'NONE' END,
        ledger_id, CASE WHEN result_type = 'LEVEL_UPGRADE_COMPLETED' THEN NEW.target_level_id ELSE NULL END,
        CASE WHEN result_type = 'LEVEL_UPGRADE_COMPLETED' THEN target_name ELSE NULL END,
        coalesce(NEW.completed_at, NEW.updated_at, CURRENT_TIMESTAMP))
    ON CONFLICT (source_type, source_id) DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER support_account_payment_event AFTER INSERT OR UPDATE OF status ON account_payment_transactions
    FOR EACH ROW EXECUTE FUNCTION support_account_payment_event();

-- An administrator adjustment is visible by its actual ledger delta and
-- balance. The internal reason and operator note never enter this event.
CREATE FUNCTION support_balance_adjustment_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    target_site uuid;
    root_id uuid;
BEGIN
    IF NEW.type <> 'ADMIN_ADJUSTMENT' THEN RETURN NEW; END IF;
    SELECT subsite_id INTO target_site FROM users WHERE id = NEW.user_id;
    root_id := support_ensure_customer_root(target_site, NEW.user_id);
    INSERT INTO support_system_events(source_type, source_id, event_type, conversation_id,
        user_id, site_id, amount, direction, balance_transaction_id, balance_after, happened_at)
    VALUES ('BALANCE_TRANSACTION', NEW.id, 'BALANCE_ADJUSTMENT', root_id, NEW.user_id,
        target_site, NEW.amount, CASE WHEN NEW.delta > 0 THEN 'CREDIT' ELSE 'DEBIT' END,
        NEW.id, NEW.balance_after, NEW.created_at)
    ON CONFLICT (source_type, source_id) DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER support_balance_adjustment_event AFTER INSERT ON balance_transactions
    FOR EACH ROW EXECUTE FUNCTION support_balance_adjustment_event();

-- Only the first actual transition into delivered is customer-facing.
-- Restoring PAID/DELIVERED after a rejected sale must not announce delivery again.
CREATE FUNCTION support_order_delivery_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE root_id uuid;
BEGIN
    IF NEW.status <> 'DELIVERED' OR OLD.status IN ('AFTER_SALE', 'REFUNDED', 'DELIVERED')
       OR NEW.user_id IS NULL OR NEW.order_type <> 'NORMAL' THEN
        RETURN NEW;
    END IF;
    root_id := support_ensure_customer_root(NEW.subsite_id, NEW.user_id);
    INSERT INTO support_system_events(source_type, source_id, event_type, conversation_id,
        user_id, site_id, order_id, happened_at)
    VALUES ('ORDER', NEW.id, 'ORDER_DELIVERED', root_id, NEW.user_id, NEW.subsite_id,
        NEW.id, coalesce(NEW.delivered_at, NEW.updated_at, CURRENT_TIMESTAMP))
    ON CONFLICT (source_type, source_id) DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER support_order_delivery_event AFTER UPDATE OF status ON orders
    FOR EACH ROW EXECUTE FUNCTION support_order_delivery_event();

-- Serialize all new cursors for a customer, including ordinary messages,
-- old order conversations, refunds, and account events. This makes seq order
-- agree with commit order across the aggregated timeline.
CREATE OR REPLACE FUNCTION support_assign_activity_seq() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    target_user uuid;
    target_guest bytea;
    lock_name text;
BEGIN
    SELECT user_id, guest_token_hash INTO target_user, target_guest
    FROM support_conversations WHERE id = NEW.conversation_id;
    IF target_user IS NULL AND target_guest IS NULL THEN
        RAISE EXCEPTION 'support activity has no customer identity' USING ERRCODE = '23514';
    END IF;
    lock_name := CASE WHEN target_user IS NOT NULL
        THEN 'support-user:' || target_user::text
        ELSE 'support-guest:' || encode(target_guest, 'hex') END;
    PERFORM pg_advisory_xact_lock(hashtextextended(lock_name, 724386));
    NEW.seq := nextval('support_activity_seq');
    RETURN NEW;
END $$;
