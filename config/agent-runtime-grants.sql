-- Run after V12 using the administrator connection to 127.0.0.1:3306.
-- Existing schema privileges are unchanged. No DELETE, DDL, or grant authority is added.
GRANT SELECT, INSERT, UPDATE ON `supportflow_rebuild`.`support_agent_run`
    TO 'supportflow_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE ON `supportflow_rebuild`.`support_agent_step`
    TO 'supportflow_app'@'127.0.0.1';
SHOW GRANTS FOR 'supportflow_app'@'127.0.0.1';
