ALTER TABLE users DROP COLUMN IF EXISTS failed_login_attempts;
ALTER TABLE users DROP COLUMN IF EXISTS lock_until;

DELETE FROM site_configs
WHERE config_key = 'login_attempt_limit';
