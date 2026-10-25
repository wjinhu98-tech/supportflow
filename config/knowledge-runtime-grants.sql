-- ADMIN ONLY, after V10. Runtime edits FAQ drafts, appends immutable publications and audit.
-- No DELETE, DDL, GRANT OPTION or writes to merchant authorization tables.
GRANT SELECT, INSERT, UPDATE ON `supportflow_rebuild`.`support_knowledge_article`
    TO 'supportflow_app'@'127.0.0.1';
GRANT SELECT, INSERT ON `supportflow_rebuild`.`support_knowledge_revision`
    TO 'supportflow_app'@'127.0.0.1';
GRANT SELECT, INSERT ON `supportflow_rebuild`.`support_knowledge_audit`
    TO 'supportflow_app'@'127.0.0.1';
SHOW GRANTS FOR 'supportflow_app'@'127.0.0.1';
