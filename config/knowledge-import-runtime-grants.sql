-- MANUAL ADMIN STEP ONLY. Never executed by Flyway or the application.
-- Run after V19 on the intended local MySQL instance (127.0.0.1:3306).
-- Imported-draft deduplication stores an immutable record; original files are not persisted here.
-- Existing article/revision/audit grants stay unchanged. No DELETE, UPDATE or DDL is granted.
GRANT SELECT, INSERT ON `supportflow_rebuild`.`support_knowledge_import`
    TO 'supportflow_app'@'127.0.0.1';
SHOW GRANTS FOR 'supportflow_app'@'127.0.0.1';
