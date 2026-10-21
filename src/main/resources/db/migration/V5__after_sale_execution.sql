-- Durable execution and append-only simulated refund receipts for approved after-sale requests.
-- No live payment provider is called. All DATETIME values follow the UTC session convention.
-- Java validates ownership and approval before executing; foreign keys are intentionally absent.
CREATE TABLE support_after_sale_execution (
    request_id CHAR(36) NOT NULL COMMENT 'Approved after-sale request UUID and task identity',
    order_id BIGINT NOT NULL COMMENT 'Validated support_order internal ID',
    buyer_id BIGINT NOT NULL COMMENT 'Authenticated global buyer ID',
    tenant_id BIGINT NOT NULL COMMENT 'Shop ID',
    amount DECIMAL(12,2) NOT NULL COMMENT 'Server-recorded simulated refund amount in yuan',
    currency CHAR(3) NOT NULL DEFAULT 'CNY' COMMENT 'Current supported currency',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'Durable execution state',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT 'Claimed execution attempts, never reset on retry',
    max_attempts INT NOT NULL DEFAULT 5 COMMENT 'Bounded retry budget',
    available_at DATETIME(3) NOT NULL COMMENT 'Next eligible processing time, UTC',
    lease_until DATETIME(3) NULL COMMENT 'Current worker lease expiry, UTC',
    claim_token CHAR(36) NULL COMMENT 'Server-generated current worker token',
    last_error_code VARCHAR(32) NULL COMMENT 'Stable safe failure classification',
    last_error VARCHAR(256) NULL COMMENT 'Safe customer-facing failure explanation',
    reference VARCHAR(64) NULL COMMENT 'Successful simulated refund reference',
    completed_at DATETIME(3) NULL COMMENT 'Successful completion time, UTC',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Created time, UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Updated time, UTC',
    PRIMARY KEY (request_id),
    KEY idx_after_sale_execution_status_available (status, available_at, request_id),
    KEY idx_after_sale_execution_status_lease (status, lease_until, request_id),
    CONSTRAINT ck_execution_request_id CHECK (CHAR_LENGTH(TRIM(request_id)) = 36),
    CONSTRAINT ck_execution_order_id CHECK (order_id > 0),
    CONSTRAINT ck_execution_buyer_id CHECK (buyer_id > 0),
    CONSTRAINT ck_execution_tenant_id CHECK (tenant_id > 0),
    CONSTRAINT ck_execution_amount CHECK (amount >= 0),
    CONSTRAINT ck_execution_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_execution_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_execution_attempts CHECK (
        max_attempts BETWEEN 1 AND 5 AND attempt_count BETWEEN 0 AND max_attempts),
    CONSTRAINT ck_execution_claim CHECK (
        (status = 'PROCESSING' AND lease_until IS NOT NULL AND claim_token IS NOT NULL
            AND CHAR_LENGTH(TRIM(claim_token)) = 36 AND attempt_count > 0)
        OR (status <> 'PROCESSING' AND lease_until IS NULL AND claim_token IS NULL)),
    CONSTRAINT ck_execution_receipt CHECK (
        (status = 'SUCCEEDED' AND reference IS NOT NULL AND CHAR_LENGTH(TRIM(reference)) > 0
            AND completed_at IS NOT NULL AND attempt_count > 0)
        OR (status <> 'SUCCEEDED' AND reference IS NULL AND completed_at IS NULL)),
    CONSTRAINT ck_execution_error CHECK (
        (status = 'FAILED' AND last_error_code IS NOT NULL AND last_error IS NOT NULL
            AND last_error_code IN ('TRANSIENT_FAILURE', 'BUSINESS_RULE_VIOLATION', 'ATTEMPTS_EXHAUSTED')
            AND CHAR_LENGTH(TRIM(last_error)) > 0)
        OR (status <> 'FAILED' AND last_error_code IS NULL AND last_error IS NULL))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs
  COMMENT = 'Durable simulated refund tasks created only after human approval';

CREATE TABLE support_simulated_refund (
    request_id CHAR(36) NOT NULL COMMENT 'After-sale request UUID and idempotency key',
    reference VARCHAR(64) NOT NULL COMMENT 'Server-generated simulated refund reference',
    amount DECIMAL(12,2) NOT NULL COMMENT 'Recorded simulated refund amount in yuan',
    currency CHAR(3) NOT NULL DEFAULT 'CNY' COMMENT 'Current supported currency',
    completed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Simulated completion time, UTC',
    PRIMARY KEY (request_id),
    UNIQUE KEY uk_simulated_refund_reference (reference),
    CONSTRAINT ck_simulated_refund_request_id CHECK (CHAR_LENGTH(TRIM(request_id)) = 36),
    CONSTRAINT ck_simulated_refund_reference CHECK (CHAR_LENGTH(TRIM(reference)) > 0),
    CONSTRAINT ck_simulated_refund_amount CHECK (amount >= 0),
    CONSTRAINT ck_simulated_refund_currency CHECK (currency = 'CNY')
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs
  COMMENT = 'Append-only simulated refund receipts; no external payment was made';
