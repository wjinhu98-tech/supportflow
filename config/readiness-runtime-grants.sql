-- Execute all statements as root on 127.0.0.1:3306, after V20 and V21 migrate.
-- Accounts are read-only at runtime; vectors are immutable, derived local data.
GRANT SELECT ON `supportflow_rebuild`.`support_security_account` TO 'supportflow_app'@'127.0.0.1';
GRANT SELECT, INSERT ON `supportflow_rebuild`.`support_knowledge_vector` TO 'supportflow_app'@'127.0.0.1';
SHOW GRANTS FOR 'supportflow_app'@'127.0.0.1';
