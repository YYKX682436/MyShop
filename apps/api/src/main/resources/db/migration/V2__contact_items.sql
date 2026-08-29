-- V1 seeds the current model for new databases. This forward migration also
-- cleans retired generic config rows from existing databases and creates the
-- single replacement key without touching unrelated configuration.
DELETE FROM site_configs
WHERE config_key IN ('github_url', 'contact_telegram', 'contact_telegram_group');

INSERT INTO site_configs (config_key, config_value, config_group)
VALUES ('contact_items', '[]', 'basic')
ON CONFLICT (config_key) DO NOTHING;
