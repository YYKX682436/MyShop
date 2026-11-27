INSERT INTO site_configs (config_key, config_value, config_group)
VALUES (
    'app_shell_config',
    '{"enabled":true,"rules":[{"pattern":"*.mc520.top","siteType":"MYSHOP","enabled":true,"note":"默认 MyShop 商城域名"}]}',
    'site'
)
ON CONFLICT (config_key) DO NOTHING;
