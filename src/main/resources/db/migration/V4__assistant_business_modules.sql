-- Internal learning modules only: no live carrier connection or business seeds.
-- UTC DATETIME values follow the same session convention as support_order.
-- Ownership and order consistency are checked in Java before tool execution.
CREATE TABLE support_shipment_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Internal shipment event ID',
    order_id BIGINT NOT NULL COMMENT 'Validated support_order internal ID',
    occurred_at DATETIME(3) NOT NULL COMMENT 'Recorded event time, UTC',
    description VARCHAR(500) NOT NULL COMMENT 'Event recorded by this project logistics module',
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_event_order_time_description (order_id, occurred_at, description),
    KEY idx_shipment_event_order_time_id (order_id, occurred_at, id),
    CONSTRAINT ck_shipment_event_order_id CHECK (order_id > 0),
    CONSTRAINT ck_shipment_event_description CHECK (CHAR_LENGTH(TRIM(description)) > 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs
  COMMENT = 'Internal recorded shipment events; not live carrier data';

CREATE TABLE support_after_sale (
    id CHAR(36) NOT NULL COMMENT 'After-sale request UUID',
    request_key CHAR(64) NOT NULL COMMENT 'Server-generated idempotency digest',
    buyer_id BIGINT NOT NULL COMMENT 'Authenticated global buyer ID',
    tenant_id BIGINT NOT NULL COMMENT 'Current shop ID',
    order_id BIGINT NOT NULL COMMENT 'Validated support_order internal ID',
    order_no VARCHAR(64) NOT NULL COMMENT 'Business order number in current shop',
    reason VARCHAR(500) NOT NULL COMMENT 'Customer-provided after-sale reason',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT 'Human review state',
    reviewer VARCHAR(128) NULL COMMENT 'Authenticated human reviewer',
    review_note VARCHAR(500) NULL COMMENT 'Human review note',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Created time, UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Updated time, UTC',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic concurrency version',
    PRIMARY KEY (id),
    UNIQUE KEY uk_after_sale_request_key (request_key),
    KEY idx_after_sale_buyer_shop_created (buyer_id, tenant_id, created_at, id),
    KEY idx_after_sale_status_created (status, created_at, id),
    KEY idx_after_sale_order_id (order_id),
    CONSTRAINT ck_after_sale_buyer_id CHECK (buyer_id > 0),
    CONSTRAINT ck_after_sale_tenant_id CHECK (tenant_id > 0),
    CONSTRAINT ck_after_sale_order_id CHECK (order_id > 0),
    CONSTRAINT ck_after_sale_order_no CHECK (CHAR_LENGTH(TRIM(order_no)) > 0),
    CONSTRAINT ck_after_sale_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0),
    CONSTRAINT ck_after_sale_status CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_after_sale_version CHECK (version >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs
  COMMENT = 'After-sale applications awaiting explicit human review';

CREATE TABLE support_after_sale_audit (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Internal audit event ID',
    request_id CHAR(36) NOT NULL COMMENT 'After-sale request UUID',
    actor VARCHAR(128) NOT NULL COMMENT 'Authenticated actor or explicit system identity',
    action VARCHAR(32) NOT NULL COMMENT 'Audited application or human review action',
    notes VARCHAR(500) NULL COMMENT 'Audit note without credentials',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Audit event time, UTC',
    PRIMARY KEY (id),
    KEY idx_after_sale_audit_request_created (request_id, created_at, id),
    CONSTRAINT ck_after_sale_audit_actor CHECK (CHAR_LENGTH(TRIM(actor)) > 0),
    CONSTRAINT ck_after_sale_audit_action CHECK (CHAR_LENGTH(TRIM(action)) > 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs
  COMMENT = 'Append-only after-sale application and human review audit';
