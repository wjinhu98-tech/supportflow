-- Receipt confirmation is an explicit authenticated buyer action. No historical dates are backfilled.
-- Application grants make this an append-only table; an order can have only one confirmation audit.
CREATE TABLE support_order_receipt_audit (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Internal receipt confirmation audit ID',
    order_id BIGINT NOT NULL COMMENT 'Confirmed support_order internal ID',
    tenant_id BIGINT NOT NULL COMMENT 'Shop at confirmation',
    buyer_id BIGINT NOT NULL COMMENT 'Authenticated buyer at confirmation',
    order_no VARCHAR(64) NOT NULL COMMENT 'Business order number at confirmation',
    actor VARCHAR(128) NOT NULL COMMENT 'Authenticated account that confirmed receipt',
    received_at DATETIME(3) NOT NULL COMMENT 'Persisted confirmation time, UTC',
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Audit record time, UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_receipt_audit_order (order_id),
    KEY idx_order_receipt_audit_buyer_shop_time (buyer_id, tenant_id, recorded_at),
    CONSTRAINT ck_order_receipt_audit_order CHECK (order_id > 0),
    CONSTRAINT ck_order_receipt_audit_shop CHECK (tenant_id > 0),
    CONSTRAINT ck_order_receipt_audit_buyer CHECK (buyer_id > 0),
    CONSTRAINT ck_order_receipt_audit_number CHECK (CHAR_LENGTH(TRIM(order_no)) > 0),
    CONSTRAINT ck_order_receipt_audit_actor CHECK (CHAR_LENGTH(TRIM(actor)) > 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs
  COMMENT = 'Append-only audit of explicit buyer receipt confirmation';
