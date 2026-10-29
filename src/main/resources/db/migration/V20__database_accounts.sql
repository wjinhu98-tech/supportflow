CREATE TABLE support_security_account (
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    principal_id BIGINT NOT NULL COMMENT 'Existing buyer/staff identity; never inferred from username',
    account_type VARCHAR(32) NOT NULL,
    order_read BOOLEAN NOT NULL DEFAULT FALSE,
    order_confirm_receipt BOOLEAN NOT NULL DEFAULT FALSE,
    after_sale_review BOOLEAN NOT NULL DEFAULT FALSE,
    knowledge_manage BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    security_version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (username),
    CONSTRAINT ck_security_account_identity CHECK (principal_id > 0),
    CONSTRAINT ck_security_account_version CHECK (security_version > 0),
    CONSTRAINT ck_security_account_type CHECK (account_type IN ('BUYER', 'MERCHANT_STAFF')),
    CONSTRAINT ck_security_account_username CHECK (CHAR_LENGTH(TRIM(username)) > 0),
    CONSTRAINT ck_security_account_hash CHECK (password_hash REGEXP '^\\{bcrypt\\}\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='Browser login accounts; runtime SELECT only; explicit operator provisioning';
