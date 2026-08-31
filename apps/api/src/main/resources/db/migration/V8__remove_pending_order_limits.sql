-- Remove the retired pending-order creation limits from existing databases.
DELETE FROM site_configs
WHERE config_key IN ('max_pending_orders_per_ip', 'max_pending_orders_per_user');
