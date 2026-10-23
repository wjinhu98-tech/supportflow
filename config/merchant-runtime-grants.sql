-- ADMIN ONLY, after V9. The application may read authorization but never change it.
-- Existing database-scoped SELECT already covers these tables; do not add wider permissions.
GRANT SELECT ON `supportflow_rebuild`.`support_merchant`
    TO 'supportflow_app'@'127.0.0.1';
GRANT SELECT ON `supportflow_rebuild`.`support_merchant_shop`
    TO 'supportflow_app'@'127.0.0.1';
GRANT SELECT ON `supportflow_rebuild`.`support_merchant_staff`
    TO 'supportflow_app'@'127.0.0.1';
GRANT SELECT ON `supportflow_rebuild`.`support_merchant_staff_shop_grant`
    TO 'supportflow_app'@'127.0.0.1';
SHOW GRANTS FOR 'supportflow_app'@'127.0.0.1';
