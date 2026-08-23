INSERT INTO currencies (code, name, symbol, is_enabled, sort_order)
VALUES ('CNY', '人民币', '¥', true, 1)
ON CONFLICT (code) DO NOTHING;

INSERT INTO site_configs (config_key, config_value, config_group)
VALUES
    ('site_name', 'MyShop', 'basic'),
    ('site_slogan', '安全、清晰、可追踪的数字商品交付', 'basic'),
    ('site_description', 'MyShop 数字商品商店，订单、支付和卡密发货全链路可追踪。', 'basic'),
    ('announcement_enabled', 'false', 'announcement'),
    ('announcement', '', 'announcement'),
    ('popup_enabled', 'false', 'popup'),
    ('popup_content', '', 'popup'),
    ('maintenance_enabled', 'false', 'maintenance'),
    ('maintenance_message', '站点维护中，请稍后再试。', 'maintenance'),
    ('points_enabled', 'false', 'points'),
    ('points_rate', '0', 'points'),
    ('footer_text', 'MyShop 数字商品商店', 'basic')
ON CONFLICT (config_key) DO NOTHING;
