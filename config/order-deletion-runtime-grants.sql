-- MANUAL ADMIN STEP ONLY. Never executed by Flyway or the application.
-- Run after V18 on the intended local MySQL instance (127.0.0.1:3306).
-- Buyer deletion is an immutable visibility/audit record; the order is never physically deleted.
-- Do not grant DELETE, UPDATE, DDL or additional privileges on support_order.
GRANT SELECT, INSERT ON `supportflow_rebuild`.`support_order_buyer_deletion`
    TO 'supportflow_app'@'127.0.0.1';
SHOW GRANTS FOR 'supportflow_app'@'127.0.0.1';
