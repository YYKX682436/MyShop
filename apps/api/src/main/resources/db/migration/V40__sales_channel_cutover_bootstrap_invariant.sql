-- S2.4A R1: every successful cutover certificate must bind an exact first ENSURE.
ALTER TABLE sales_channel_cutover_certifications
    ALTER COLUMN bootstrap_operation_id SET NOT NULL;
