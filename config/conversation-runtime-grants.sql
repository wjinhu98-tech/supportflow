-- MANUAL ADMIN STEP ONLY. Never executed by Flyway or the application.
-- Run after V8 on the intended local MySQL instance. Existing business grants stay unchanged.
-- Conversation reservation/finalization requires short transactions; history is append-only.
GRANT SELECT, INSERT, UPDATE ON `supportflow_rebuild`.`support_chat_conversation`
    TO 'supportflow_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE ON `supportflow_rebuild`.`support_chat_turn`
    TO 'supportflow_app'@'127.0.0.1';
GRANT SELECT, INSERT ON `supportflow_rebuild`.`support_chat_message`
    TO 'supportflow_app'@'127.0.0.1';
SHOW GRANTS FOR 'supportflow_app'@'127.0.0.1';
