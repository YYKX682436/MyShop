-- This forward migration creates the current contact model for fresh and
-- existing databases, cleans retired generic config rows, and leaves all
-- unrelated configuration untouched. V1 remains immutable after deployment.
DELETE FROM site_configs
WHERE config_key IN ('github_url', 'contact_telegram', 'contact_telegram_group');

INSERT INTO site_configs (config_key, config_value, config_group)
VALUES ('contact_items', '[]', 'basic')
ON CONFLICT (config_key) DO NOTHING;
