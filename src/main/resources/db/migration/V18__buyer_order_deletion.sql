-- Buyer visibility only: original orders, after-sale records and historical evidence remain intact.
-- Runtime grants are SELECT + INSERT; a buyer's deletion is one append-only audit fact per order.
CREATE TABLE support_order_buyer_deletion (
    order_id BIGINT NOT NULL COMMENT 'Original support_order internal ID and idempotency key',
    tenant_id BIGINT NOT NULL COMMENT 'Shop of the original order',
    buyer_id BIGINT NOT NULL COMMENT 'Authenticated buyer who deleted their order record',
    order_no VARCHAR(64) NOT NULL COMMENT 'Business order number at deletion',
    actor VARCHAR(128) NOT NULL COMMENT 'Authenticated account that requested deletion',
    deleted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Deletion record time, UTC',
    PRIMARY KEY (order_id),
    KEY idx_order_buyer_deletion_scope (buyer_id, tenant_id, order_id),
    CONSTRAINT ck_order_buyer_deletion_order CHECK (order_id > 0),
    CONSTRAINT ck_order_buyer_deletion_shop CHECK (tenant_id > 0),
    CONSTRAINT ck_order_buyer_deletion_buyer CHECK (buyer_id > 0),
    CONSTRAINT ck_order_buyer_deletion_number CHECK (CHAR_LENGTH(TRIM(order_no)) > 0),
    CONSTRAINT ck_order_buyer_deletion_actor CHECK (CHAR_LENGTH(TRIM(actor)) > 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs
  COMMENT = 'Append-only buyer order visibility deletion; original business records retained';
