-- MANUAL ADMIN STEP ONLY. Never executed by Flyway or the application.
-- Run after V7 on the intended local MySQL instance and verify the account host first.
-- Existing SELECT grants remain unchanged. Only the two receipt-transition columns are writable.
GRANT UPDATE (status, received_at) ON `supportflow_rebuild`.`support_order`
    TO 'supportflow_app'@'127.0.0.1';
GRANT INSERT ON `supportflow_rebuild`.`support_order_receipt_audit`
    TO 'supportflow_app'@'127.0.0.1';
SHOW GRANTS FOR 'supportflow_app'@'127.0.0.1';
