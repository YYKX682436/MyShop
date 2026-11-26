-- Keep historical delivery results, but stop turning every DELIVERED status
-- transition into a customer message. The fulfillment service records only
-- the explicit administrator manual-delivery operation from this version on.
ALTER TABLE support_system_events
    ADD COLUMN manual_delivery boolean NOT NULL DEFAULT false;

ALTER TABLE support_system_events
    ADD CONSTRAINT chk_support_system_events_manual_delivery
        CHECK (NOT manual_delivery OR event_type = 'ORDER_DELIVERED');

DROP TRIGGER IF EXISTS support_order_delivery_event ON orders;
DROP FUNCTION IF EXISTS support_order_delivery_event();
