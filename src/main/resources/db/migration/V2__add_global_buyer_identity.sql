-- buyer_id 是平台全局买家ID；customer_id 保留为历史店铺内客户编号。
-- 不能从 customer_id 推断跨商铺的同一买家。历史数据保持 NULL，
-- 必须依据可信账号映射显式回填后才允许买家查询。
ALTER TABLE support_order
    ADD COLUMN buyer_id BIGINT NULL COMMENT '平台全局买家ID，NULL表示历史归属尚未确认'
        AFTER customer_id,
    ADD CONSTRAINT ck_support_order_buyer_id CHECK (buyer_id > 0),
    ADD INDEX idx_support_order_buyer_id_id (buyer_id, id);
