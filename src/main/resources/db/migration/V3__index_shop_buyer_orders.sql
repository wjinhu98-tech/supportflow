-- Keep the cross-shop buyer/id index for account-wide order queries.
CREATE INDEX idx_support_order_buyer_shop_id
    ON support_order (buyer_id, tenant_id, id);
