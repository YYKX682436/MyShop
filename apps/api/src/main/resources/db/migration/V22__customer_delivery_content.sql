ALTER TABLE order_items
    ADD COLUMN customer_delivery_content TEXT;

COMMENT ON COLUMN order_items.customer_delivery_content IS
    'Optional buyer-visible markdown content for a delivered manual order item';
