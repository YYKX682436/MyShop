ALTER TABLE product_categories
    ADD COLUMN parent_id uuid;

ALTER TABLE product_categories
    ADD CONSTRAINT product_categories_parent_fk
        FOREIGN KEY (parent_id) REFERENCES product_categories(id) ON DELETE RESTRICT;

CREATE INDEX idx_product_categories_parent_deleted_sort
    ON product_categories (parent_id, is_deleted, sort_order, id);

CREATE UNIQUE INDEX uq_product_categories_root_name_active
    ON product_categories (name)
    WHERE parent_id IS NULL AND is_deleted = 0;

CREATE UNIQUE INDEX uq_product_categories_child_name_active
    ON product_categories (parent_id, name)
    WHERE parent_id IS NOT NULL AND is_deleted = 0;
