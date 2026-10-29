-- Derived local retrieval data only. Current published snapshots remain the authority for visibility.
CREATE TABLE support_knowledge_vector (
    tenant_id BIGINT NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    source_version VARCHAR(40) NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    model_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunk_no SMALLINT NOT NULL,
    dimensions SMALLINT NOT NULL,
    vector_bytes VARBINARY(2048) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id, source_id, source_version, content_hash, model_fingerprint, chunk_no),
    CONSTRAINT ck_knowledge_vector_scope CHECK (tenant_id > 0),
    CONSTRAINT ck_knowledge_vector_chunk CHECK (chunk_no >= 0 AND chunk_no < 128),
    CONSTRAINT ck_knowledge_vector_shape CHECK (dimensions = 512 AND OCTET_LENGTH(vector_bytes) = 2048)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='Append-only versioned local BGE vectors; no source text or query storage';
