-- Supplier evidence permits images up to 10 MiB; retain the private attachment store.
ALTER TABLE after_sale_attachments DROP CONSTRAINT after_sale_attachments_file_size_check;
ALTER TABLE after_sale_attachments ADD CONSTRAINT after_sale_attachments_file_size_check
    CHECK (file_size > 0 AND file_size <= 10485760);
