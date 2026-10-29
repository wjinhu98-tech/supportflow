package dev.supportflow.deployment;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explicit learning fixtures only; never a migration, Spring bean, product catalogue or historical inference. */
public final class DemoProductData {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<DemoProduct> PRODUCTS = List.of(
            new DemoProduct(102, "数码优选店", "SF102001", "蓝牙耳机", "黑色", "199.90"),
            new DemoProduct(102, "数码优选店", "SF102002", "无线鼠标", "白色", "59.90"),
            new DemoProduct(103, "生活家居店", "SF103001", "陶瓷马克杯", "白色", "129.00"),
            new DemoProduct(103, "生活家居店", "SF103002", "棉质四件套", "1.8米床", "89.00"));

    private DemoProductData() { }

    /** The caller owns commit/rollback; every candidate is verified and locked before any update. */
    public static Result seed(Connection connection) throws SQLException {
        if (connection.getAutoCommit()) throw new IllegalArgumentException("Demo product seeding requires an explicit transaction");
        long merchant;
        try (var query = connection.prepareStatement(
                "SELECT merchant_id FROM support_merchant_shop WHERE shop_id=100 AND status='ACTIVE' FOR UPDATE");
             var row = query.executeQuery()) {
            if (!row.next()) throw new SQLException("An active demo base shop is required");
            merchant = row.getLong(1);
        }
        var pending = new ArrayList<Pending>();
        int preserved = 0;
        for (DemoProduct product : PRODUCTS) {
            try (var query = connection.prepareStatement("""
                    SELECT o.id,o.buyer_id,o.customer_id,o.total_amount,o.currency,o.product_items_json,
                           shop.merchant_id,shop.name,shop.status
                    FROM support_order o JOIN support_merchant_shop shop ON shop.shop_id=o.tenant_id
                    WHERE o.tenant_id=? AND o.order_no=? FOR UPDATE
                    """)) {
                query.setLong(1, product.shopId());
                query.setString(2, product.orderNo());
                try (var row = query.executeQuery()) {
                    if (!row.next() || row.getLong("buyer_id") != 200 || row.getLong("customer_id") != 200
                            || row.getBigDecimal("total_amount").compareTo(new BigDecimal(product.amount())) != 0
                            || !"CNY".equals(row.getString("currency")) || row.getLong("merchant_id") != merchant
                            || !product.shopName().equals(row.getString("name")) || !"ACTIVE".equals(row.getString("status"))) {
                        throw new SQLException("An expected learning order or shop does not match; no product snapshots were applied");
                    }
                    if (row.getString("product_items_json") == null) pending.add(new Pending(row.getLong("id"), product));
                    else preserved++;
                }
            }
        }
        int updated = 0;
        try (var update = connection.prepareStatement("""
                UPDATE support_order SET product_items_json=?,updated_at=updated_at
                WHERE id=? AND tenant_id=? AND buyer_id=200 AND product_items_json IS NULL
                """)) {
            for (Pending candidate : pending) {
                update.setString(1, snapshot(candidate.product()));
                update.setLong(2, candidate.orderId());
                update.setLong(3, candidate.product().shopId());
                if (update.executeUpdate() != 1) throw new SQLException("A verified demo product candidate changed unexpectedly");
                updated++;
            }
        }
        return new Result(updated, preserved);
    }

    /** Only the four explicit fixture identities have purchase-time product values. */
    static String snapshotFor(long shopId, String orderNo) {
        return PRODUCTS.stream().filter(product -> product.shopId() == shopId && product.orderNo().equals(orderNo))
                .findFirst().map(DemoProductData::snapshot)
                .orElseThrow(() -> new IllegalArgumentException("Unknown demo product identity"));
    }

    private static String snapshot(DemoProduct product) {
        return JSON.writeValueAsString(List.of(Map.of("productName", product.productName(),
                "specification", product.specification(), "quantity", 1)));
    }

    public record Result(int snapshotsInserted, int snapshotsPreserved) { }
    private record Pending(long orderId, DemoProduct product) { }
    private record DemoProduct(long shopId, String shopName, String orderNo,
                               String productName, String specification, String amount) { }
}
