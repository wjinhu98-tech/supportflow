-- Human replies are immutable and separate from AI turns. No permission is granted automatically.
ALTER TABLE support_security_account ADD COLUMN chat_support BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE support_chat_conversation
    ADD COLUMN human_context_reset_after_message_id BIGINT NOT NULL DEFAULT 0;

-- One mutex per buyer/shop also coordinates legacy stateless AI requests.
CREATE TABLE support_chat_scope (
    buyer_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    legacy_processing_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    legacy_expires_at DATETIME(3) NULL,
    PRIMARY KEY (buyer_id, tenant_id),
    CONSTRAINT ck_chat_scope_owner CHECK (buyer_id > 0 AND tenant_id > 0),
    CONSTRAINT ck_chat_scope_lease CHECK (
      (legacy_processing_token IS NULL AND legacy_expires_at IS NULL) OR
      (legacy_processing_token IS NOT NULL AND legacy_expires_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE support_human_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    buyer_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
    assigned_staff_id BIGINT NULL,
    active_slot TINYINT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    closed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_human_session_public (public_id),
    UNIQUE KEY uk_human_session_request (conversation_id, request_id),
    UNIQUE KEY uk_human_session_active (conversation_id, active_slot),
    UNIQUE KEY uk_human_session_buyer_shop_active (buyer_id, tenant_id, active_slot),
    KEY ix_human_session_shop (tenant_id, status, id),
    KEY ix_human_session_staff (tenant_id, assigned_staff_id, status, id),
    CONSTRAINT ck_human_session_owner CHECK (conversation_id > 0 AND tenant_id > 0 AND buyer_id > 0),
    CONSTRAINT ck_human_session_status CHECK (
      (status='WAITING' AND assigned_staff_id IS NULL AND active_slot IS NOT NULL AND active_slot=1 AND closed_at IS NULL) OR
      (status='ACTIVE' AND assigned_staff_id IS NOT NULL AND assigned_staff_id > 0 AND active_slot IS NOT NULL AND active_slot=1 AND closed_at IS NULL) OR
      (status='CLOSED' AND active_slot IS NULL AND closed_at IS NOT NULL)),
    CONSTRAINT ck_human_session_staff CHECK (assigned_staff_id IS NULL OR assigned_staff_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE support_human_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    role VARCHAR(8) NOT NULL,
    sender_staff_id BIGINT NULL,
    text TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_human_message_public (public_id),
    UNIQUE KEY uk_human_message_client (session_id, client_message_id),
    KEY ix_human_message_conversation (conversation_id, id),
    KEY ix_human_message_session (session_id, id),
    CONSTRAINT ck_human_message_owner CHECK (conversation_id > 0 AND session_id > 0),
    CONSTRAINT ck_human_message_role CHECK (
      (role='BUYER' AND sender_staff_id IS NULL) OR (role='STAFF' AND sender_staff_id IS NOT NULL AND sender_staff_id > 0)),
    CONSTRAINT ck_human_message_text CHECK (CHAR_LENGTH(TRIM(text)) BETWEEN 1 AND 2000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
