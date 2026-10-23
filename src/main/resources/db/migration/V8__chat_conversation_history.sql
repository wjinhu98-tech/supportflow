-- All dates are UTC; conversation and message UUIDs are identifiers, never authorization.
CREATE TABLE support_chat_conversation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_conversation_public (public_id),
    KEY ix_chat_conversation_owner (buyer_id, tenant_id, id),
    CONSTRAINT ck_chat_conversation_owner CHECK (buyer_id > 0 AND tenant_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE support_chat_turn (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    client_message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_turn_client (conversation_id, client_message_id),
    KEY ix_chat_turn_processing (conversation_id, status, expires_at),
    CONSTRAINT ck_chat_turn_status CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE support_chat_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id BIGINT NOT NULL,
    turn_id BIGINT NOT NULL,
    role VARCHAR(9) NOT NULL,
    text TEXT NOT NULL,
    response_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_message_public (public_id),
    UNIQUE KEY uk_chat_message_turn_role (turn_id, role),
    KEY ix_chat_message_conversation (conversation_id, id),
    CONSTRAINT ck_chat_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_chat_message_text CHECK (CHAR_LENGTH(text) BETWEEN 1 AND 20000),
    CONSTRAINT ck_chat_message_response CHECK (
        (role = 'USER' AND response_json IS NULL) OR (role = 'ASSISTANT' AND response_json IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
