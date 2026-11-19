-- NULL means no successful import; 0 preserves and flags conflicting history.
ALTER TABLE products ADD COLUMN card_content_column_count integer
    CHECK (card_content_column_count IS NULL OR card_content_column_count >= 0);

WITH historical_widths AS (
    SELECT product_id, jsonb_array_length(content_parts) AS width FROM card_keys
    UNION
    SELECT product_id, column_count AS width FROM card_import_batches WHERE success_count > 0
), product_widths AS (
    SELECT product_id, CASE WHEN count(*) = 1 THEN min(width) ELSE 0 END AS width
    FROM historical_widths GROUP BY product_id
)
UPDATE products p SET card_content_column_count = product_widths.width
FROM product_widths WHERE p.id = product_widths.product_id;

-- Report only product identifiers and widths; never print or change card contents.
DO $$
DECLARE conflict record;
BEGIN
    FOR conflict IN
        SELECT p.id, p.product_no, string_agg(history.width::text, '、' ORDER BY history.width) AS widths
        FROM products p
        JOIN (
            SELECT product_id, jsonb_array_length(content_parts) AS width FROM card_keys
            UNION
            SELECT product_id, column_count AS width FROM card_import_batches WHERE success_count > 0
        ) history ON history.product_id = p.id
        WHERE p.card_content_column_count = 0
        GROUP BY p.id, p.product_no
    LOOP
        RAISE WARNING 'Mixed card content widths: product_id=%, product_no=%, widths=%',
            conflict.id, conflict.product_no, conflict.widths;
    END LOOP;
END $$;
