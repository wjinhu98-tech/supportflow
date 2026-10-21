-- MANUAL ADMIN STEP ONLY. Not a Flyway migration, never executed automatically.
-- Verify database, account host, and V4/V5 tables before running in your local environment.
-- Keeps the existing read grants; adds only the writes needed by after-sale workflow.
GRANT INSERT, UPDATE ON `supportflow_rebuild`.`support_after_sale`
    TO 'supportflow_app'@'127.0.0.1';
GRANT INSERT ON `supportflow_rebuild`.`support_after_sale_audit`
    TO 'supportflow_app'@'127.0.0.1';
-- V5: durable execution state is mutable; simulated refund receipts are append-only.
GRANT INSERT, UPDATE ON `supportflow_rebuild`.`support_after_sale_execution`
    TO 'supportflow_app'@'127.0.0.1';
GRANT INSERT ON `supportflow_rebuild`.`support_simulated_refund`
    TO 'supportflow_app'@'127.0.0.1';
SHOW GRANTS FOR 'supportflow_app'@'127.0.0.1';
