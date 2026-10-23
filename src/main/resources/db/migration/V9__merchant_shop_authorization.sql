-- No historical shop ownership or reviewer authorization is inferred by this migration.
-- Provision merchant/staff/grants explicitly; the runtime application only reads these four tables.
CREATE TABLE support_merchant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT ck_merchant_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_merchant_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE support_merchant_shop (
    shop_id BIGINT NOT NULL COMMENT 'Existing support_order.tenant_id; not a new order identity',
    merchant_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (shop_id),
    KEY idx_merchant_shop_merchant (merchant_id, shop_id),
    CONSTRAINT ck_merchant_shop_ids CHECK (shop_id > 0 AND merchant_id > 0),
    CONSTRAINT ck_merchant_shop_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_merchant_shop_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE support_merchant_staff (
    id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    username VARCHAR(128) NOT NULL COMMENT 'Exact authenticated account username',
    linked_buyer_id BIGINT NOT NULL COMMENT 'Server account identity retained to forbid self-review',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_staff_username (username),
    KEY idx_merchant_staff_merchant (merchant_id, id),
    CONSTRAINT ck_merchant_staff_ids CHECK (merchant_id > 0 AND linked_buyer_id > 0),
    CONSTRAINT ck_merchant_staff_username CHECK (CHAR_LENGTH(TRIM(username)) > 0),
    CONSTRAINT ck_merchant_staff_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE support_merchant_staff_shop_grant (
    staff_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (staff_id, shop_id),
    KEY idx_merchant_staff_grant_shop (shop_id, staff_id),
    CONSTRAINT ck_merchant_staff_grant_ids CHECK (staff_id > 0 AND shop_id > 0),
    CONSTRAINT ck_merchant_staff_grant_enabled CHECK (enabled IN (FALSE, TRUE))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

ALTER TABLE support_after_sale_audit
    ADD COLUMN staff_id BIGINT NULL COMMENT 'Verified merchant staff ID; historical/system/buyer events stay NULL',
    ADD CONSTRAINT ck_after_sale_audit_staff CHECK (staff_id IS NULL OR staff_id > 0);

ALTER TABLE support_after_sale
    ADD KEY idx_after_sale_shop_status_created (tenant_id, status, created_at, id),
    ADD KEY idx_after_sale_shop_updated (tenant_id, updated_at, id);
