-- Merchant-authored public shop information. No automatic seeds or changes to protected business rules.
CREATE TABLE support_knowledge_article (
    id CHAR(36) NOT NULL,
    sequence_id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    keywords_json JSON NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 0,
    last_revision INT NOT NULL DEFAULT 0,
    published_revision INT NULL,
    publication_slot TINYINT UNSIGNED NULL COMMENT '1..100: database-enforced concurrent publication bound per shop',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_article_sequence (sequence_id),
    UNIQUE KEY uk_knowledge_article_publication_slot (tenant_id, publication_slot),
    KEY idx_knowledge_article_shop_sequence (tenant_id, sequence_id),
    CONSTRAINT ck_knowledge_article_id CHECK (CHAR_LENGTH(id)=36 AND tenant_id>0),
    CONSTRAINT ck_knowledge_article_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 120),
    CONSTRAINT ck_knowledge_article_keywords CHECK (JSON_TYPE(keywords_json)='ARRAY' AND JSON_LENGTH(keywords_json) BETWEEN 1 AND 12),
    CONSTRAINT ck_knowledge_article_content CHECK (CHAR_LENGTH(TRIM(content)) BETWEEN 1 AND 4000),
    CONSTRAINT ck_knowledge_article_version CHECK (version BETWEEN 0 AND 1000000000 AND last_revision BETWEEN 0 AND version),
    CONSTRAINT ck_knowledge_article_publication CHECK (
      (status='DRAFT' AND last_revision=0 AND published_revision IS NULL AND publication_slot IS NULL)
      OR (status='OFFLINE' AND last_revision>0 AND published_revision IS NULL AND publication_slot IS NULL)
      OR (status='PUBLISHED' AND published_revision=last_revision AND last_revision>0 AND publication_slot BETWEEN 1 AND 100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE support_knowledge_revision (
    article_id CHAR(36) NOT NULL,
    revision INT NOT NULL,
    tenant_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    keywords_json JSON NOT NULL,
    content TEXT NOT NULL,
    actor VARCHAR(128) NOT NULL,
    staff_id BIGINT NOT NULL,
    published_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (article_id, revision),
    KEY idx_knowledge_revision_shop_article (tenant_id, article_id, revision),
    CONSTRAINT ck_knowledge_revision_identity CHECK (CHAR_LENGTH(article_id)=36 AND tenant_id>0 AND revision>0 AND staff_id>0),
    CONSTRAINT ck_knowledge_revision_actor CHECK (CHAR_LENGTH(TRIM(actor))>0),
    CONSTRAINT ck_knowledge_revision_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 120),
    CONSTRAINT ck_knowledge_revision_keywords CHECK (JSON_TYPE(keywords_json)='ARRAY' AND JSON_LENGTH(keywords_json) BETWEEN 1 AND 12),
    CONSTRAINT ck_knowledge_revision_content CHECK (CHAR_LENGTH(TRIM(content)) BETWEEN 1 AND 4000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE support_knowledge_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    article_id CHAR(36) NOT NULL,
    tenant_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    staff_id BIGINT NOT NULL,
    version INT NOT NULL,
    published_revision INT NULL,
    title VARCHAR(120) NOT NULL,
    keywords_json JSON NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_knowledge_audit_shop_article_id (tenant_id, article_id, id),
    CONSTRAINT ck_knowledge_audit_identity CHECK (CHAR_LENGTH(article_id)=36 AND tenant_id>0 AND staff_id>0),
    CONSTRAINT ck_knowledge_audit_action CHECK (action IN ('CREATED','SAVED','PUBLISHED','OFFLINE')),
    CONSTRAINT ck_knowledge_audit_version CHECK (version BETWEEN 0 AND 1000000000 AND (published_revision IS NULL OR published_revision>0)),
    CONSTRAINT ck_knowledge_audit_actor CHECK (CHAR_LENGTH(TRIM(actor))>0),
    CONSTRAINT ck_knowledge_audit_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 120),
    CONSTRAINT ck_knowledge_audit_keywords CHECK (JSON_TYPE(keywords_json)='ARRAY' AND JSON_LENGTH(keywords_json) BETWEEN 1 AND 12),
    CONSTRAINT ck_knowledge_audit_content CHECK (CHAR_LENGTH(TRIM(content)) BETWEEN 1 AND 4000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
