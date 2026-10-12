-- 适用于 MySQL 8.0.16 及以上版本。
-- 时间统一按 UTC 保存，连接配置将在下一步落实。

CREATE TABLE support_order (
                               id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
                               tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
                               customer_id BIGINT NOT NULL COMMENT '所属客户ID',
                               order_no VARCHAR(64) NOT NULL COMMENT '业务订单号',
                               status VARCHAR(32) NOT NULL COMMENT '订单状态编码',
                               total_amount DECIMAL(12, 2) NOT NULL COMMENT '订单总金额，单位元',
                               currency CHAR(3) NOT NULL DEFAULT 'CNY' COMMENT '币种，当前仅支持CNY',
                               created_at DATETIME(3) NOT NULL
                                   DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
                               updated_at DATETIME(3) NOT NULL
                                   DEFAULT CURRENT_TIMESTAMP(3)
                                   ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间，UTC',

                               PRIMARY KEY (id),

                               UNIQUE KEY uk_support_order_tenant_order_no (
                                   tenant_id, order_no
                                   ),

                               CONSTRAINT ck_support_order_order_no
                                   CHECK (CHAR_LENGTH(TRIM(order_no)) > 0),

                               CONSTRAINT ck_support_order_status
                                   CHECK (
                                       status IN (
                                                  'CREATED',
                                                  'PAID',
                                                  'SHIPPED',
                                                  'COMPLETED',
                                                  'CANCELLED'
                                           )
                                       ),

                               CONSTRAINT ck_support_order_amount
                                   CHECK (total_amount >= 0),

                               CONSTRAINT ck_support_order_currency
                                   CHECK (currency = 'CNY')
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_as_cs
  COMMENT = '客服订单主表';