ALTER TABLE products
    ADD COLUMN agent_notice_text TEXT,
    ADD COLUMN agent_notice_show_in_detail BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN agent_notice_version BIGINT NOT NULL DEFAULT 0;
