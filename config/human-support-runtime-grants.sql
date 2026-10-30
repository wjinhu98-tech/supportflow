-- ADMIN ONLY, after V22. Sessions and the buyer/shop admission mutex may change; transcripts are append-only.
-- No DELETE, message UPDATE, DDL, GRANT OPTION, or merchant/account authorization writes.
GRANT SELECT, INSERT, UPDATE ON `supportflow_rebuild`.`support_human_session`
    TO 'supportflow_app'@'127.0.0.1';
GRANT SELECT, INSERT ON `supportflow_rebuild`.`support_human_message`
    TO 'supportflow_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE ON `supportflow_rebuild`.`support_chat_scope`
    TO 'supportflow_app'@'127.0.0.1';
SHOW GRANTS FOR 'supportflow_app'@'127.0.0.1';
