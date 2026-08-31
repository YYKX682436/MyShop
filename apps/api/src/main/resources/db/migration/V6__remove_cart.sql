-- V1 already owns orders.order_type. Keep that column available for V7 to
-- normalize into the current NORMAL / ADMIN_PICKUP model; this migration only
-- removes the retired cart table without deleting orders or order items.
DROP TABLE IF EXISTS cart_items;
