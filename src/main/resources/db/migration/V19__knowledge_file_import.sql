-- Original upload bytes are never persisted. Only the normalized-text fingerprint and draft identity are retained.
-- The existing article row serializes concurrent imports; this append-only table needs SELECT + INSERT only.
CREATE TABLE support_knowledge_import (
    tenant_id BIGINT NOT NULL COMMENT 'Authorized shop receiving the draft',
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA256 of normalized UTF8 text',
    article_id CHAR(36) NOT NULL COMMENT 'Stable draft UUID for this shop and normalized text',
    actor VARCHAR(128) NOT NULL COMMENT 'Authenticated first importer account',
    staff_id BIGINT NOT NULL COMMENT 'Authorized first importer staff ID',
    normalization_version VARCHAR(32) NOT NULL COMMENT 'Fixed UTF8_TEXT_V1 normalization contract',
    imported_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'First import time, UTC',
    PRIMARY KEY (tenant_id, content_hash),
    UNIQUE KEY uk_knowledge_import_article (article_id),
    CONSTRAINT ck_knowledge_import_shop CHECK (tenant_id > 0),
    CONSTRAINT ck_knowledge_import_hash CHECK (CHAR_LENGTH(content_hash) = 64),
    CONSTRAINT ck_knowledge_import_article CHECK (CHAR_LENGTH(article_id) = 36),
    CONSTRAINT ck_knowledge_import_actor CHECK (CHAR_LENGTH(TRIM(actor)) > 0),
    CONSTRAINT ck_knowledge_import_staff CHECK (staff_id > 0),
    CONSTRAINT ck_knowledge_import_normalization CHECK (normalization_version = 'UTF8_TEXT_V1')
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs
  COMMENT = 'Append-only normalized shop knowledge import identity; no original file storage';
