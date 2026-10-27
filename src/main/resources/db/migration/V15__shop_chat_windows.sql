-- Preserve all old conversations and messages. The buyer's shop window reads their combined visible history.
ALTER TABLE support_chat_conversation
    ADD COLUMN window_slot INT NULL,
    ADD COLUMN deleted_at DATETIME(3) NULL,
    ADD UNIQUE KEY uk_chat_shop_window (buyer_id, tenant_id, window_slot),
    ADD KEY ix_chat_shop_window_history (buyer_id, tenant_id, deleted_at, id),
    ADD CONSTRAINT ck_chat_shop_window CHECK (window_slot IS NULL OR (window_slot=1 AND deleted_at IS NULL));
