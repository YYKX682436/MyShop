ALTER TABLE subsites ADD COLUMN favicon_url varchar(500);
ALTER TABLE subsites DROP COLUMN logo_url;
ALTER TABLE subsites DROP COLUMN customer_service;

DELETE FROM site_configs WHERE config_key IN (
    'site_slogan', 'site_description', 'logo_url', 'footer_text',
    'contact_email', 'contact_items', 'customer_service', 'main_site_has_contact'
);
