-- Read-only agent runs own bounded, durable steps. No business write is replayed by this workflow.
ALTER TABLE support_chat_turn
    ADD COLUMN processing_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;

CREATE TABLE support_agent_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id BIGINT NOT NULL,
    client_message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_json JSON NOT NULL,
    planning_message VARCHAR(1000) NOT NULL,
    selected_order_no VARCHAR(64) NULL,
    phase VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    order_no VARCHAR(64) NULL,
    result_text MEDIUMTEXT NULL,
    attempts INT NOT NULL DEFAULT 0,
    max_steps INT NOT NULL,
    deadline_at DATETIME(3) NOT NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(3) NULL,
    failure_code VARCHAR(32) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_run_public (public_id),
    UNIQUE KEY uk_agent_run_turn (conversation_id, client_message_id),
    KEY ix_agent_run_owner (buyer_id, tenant_id, conversation_id),
    KEY ix_agent_run_lease (status, lease_until),
    CONSTRAINT ck_agent_run_owner CHECK (buyer_id > 0 AND tenant_id > 0),
    CONSTRAINT ck_agent_run_phase CHECK (phase IN ('PLAN', 'ORDER_LOOKUP', 'LOGISTICS_LOOKUP', 'COMPLETE')),
    CONSTRAINT ck_agent_run_status CHECK (status IN ('PAUSED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'EXPIRED', 'LIMIT_REACHED')),
    CONSTRAINT ck_agent_run_budget CHECK (max_steps BETWEEN 1 AND 32 AND attempts BETWEEN 0 AND max_steps),
    CONSTRAINT ck_agent_run_generation CHECK (generation >= 0),
    CONSTRAINT ck_agent_run_result CHECK (result_text IS NULL OR
        (CHAR_LENGTH(result_text) <= 20000 AND OCTET_LENGTH(result_text) <= 65536)),
    CONSTRAINT ck_agent_run_failure CHECK (failure_code IS NULL OR failure_code IN
        ('EXECUTION_INTERRUPTED', 'MODEL_UNAVAILABLE', 'TOOL_UNAVAILABLE', 'VALIDATION_REJECTED',
         'DEADLINE_EXCEEDED', 'STEP_LIMIT_REACHED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE support_agent_step (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt INT NOT NULL,
    phase VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    result_text MEDIUMTEXT NULL,
    started_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_step_attempt (run_id, attempt),
    KEY ix_agent_step_operation (run_id, operation_id),
    CONSTRAINT ck_agent_step_attempt CHECK (attempt BETWEEN 1 AND 32),
    CONSTRAINT ck_agent_step_phase CHECK (phase IN ('PLAN', 'ORDER_LOOKUP', 'LOGISTICS_LOOKUP')),
    CONSTRAINT ck_agent_step_status CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'ABANDONED')),
    CONSTRAINT ck_agent_step_result CHECK (result_text IS NULL OR
        (CHAR_LENGTH(result_text) <= 20000 AND OCTET_LENGTH(result_text) <= 65536))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
