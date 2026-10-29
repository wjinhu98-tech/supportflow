package dev.supportflow.deployment;

import dev.supportflow.order.domain.model.OrderItemSnapshot;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Explicitly chosen learning products for LocalDemoData fixtures, not recovered purchase history. */
public final class LegacyDemoProductData {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 15, 1, 0);
    private static final List<DemoProduct> PRODUCTS = List.of(
            new DemoProduct(100, "01", "59.90", "无线鼠标", "白色", "white-mouse.png"),
            new DemoProduct(100, "02", "199.00", "蓝牙耳机", "黑色 标准款", "black-earbuds.png"),
            new DemoProduct(100, "03", "299.90", "蓝牙耳机", "黑色 降噪款", "black-earbuds.png"),
            new DemoProduct(100, "04", "89.00", "棉质四件套", "1.8米床", "cotton-bedding.png"),
            new DemoProduct(100, "05", "39.90", "陶瓷马克杯", "白色", "white-mug.png"),
            new DemoProduct(100, "06", "1299.00", "棉质四件套", "1.8米床 高支棉", "cotton-bedding.png"),
            new DemoProduct(100, "07", "26.80", "陶瓷马克杯", "白色", "white-mug.png"),
            new DemoProduct(100, "08", "168.00", "棉质四件套", "1.8米床", "cotton-bedding.png"),
            new DemoProduct(101, "03", "399.90", "蓝牙耳机", "黑色", "black-earbuds.png"),
            new DemoProduct(101, "09", "78.00", "无线鼠标", "白色", "white-mouse.png"));

    private LegacyDemoProductData() { }

    /** Caller owns commit/rollback; all ten fixture identities are verified and locked before any write. */
    public static Result seed(Connection connection) throws SQLException {
        if (connection.getAutoCommit()) throw new IllegalArgumentException("Legacy demo products require an explicit transaction");
        var pending = new ArrayList<Pending>();
        int preserved = 0;
        for (DemoProduct product : PRODUCTS) {
            try (var query = connection.prepareStatement("""
                    SELECT id,customer_id,buyer_id,total_amount,currency,created_at,product_items_json
                    FROM support_order WHERE tenant_id=? AND order_no=? FOR UPDATE
                    """)) {
                query.setLong(1, product.shopId());
                query.setString(2, product.orderNo());
                try (var row = query.executeQuery()) {
                    if (!row.next() || row.getLong("customer_id") != 200 || row.getLong("buyer_id") != 200
                            || row.getBigDecimal("total_amount").compareTo(new BigDecimal(product.amount())) != 0
                            || !"CNY".equals(row.getString("currency"))
                            || !CREATED_AT.equals(row.getObject("created_at", LocalDateTime.class))) {
                        throw new SQLException("An expected legacy learning order does not match; no product snapshots were applied");
                    }
                    if (row.getString("product_items_json") == null) pending.add(new Pending(row.getLong("id"), product));
                    else preserved++;
                }
            }
        }
        try (var update = connection.prepareStatement("""
                UPDATE support_order SET product_items_json=?,updated_at=updated_at
                WHERE id=? AND tenant_id=? AND buyer_id=200 AND customer_id=200 AND product_items_json IS NULL
                """)) {
            for (Pending candidate : pending) {
                DemoProduct product = candidate.product();
                var item = new OrderItemSnapshot(product.productName(), product.specification(), 1,
                        "/products/demo/" + product.imageFile());
                update.setString(1, JSON.writeValueAsString(List.of(item)));
                update.setLong(2, candidate.orderId());
                update.setLong(3, product.shopId());
                if (update.executeUpdate() != 1) throw new SQLException("A verified legacy demo product candidate changed unexpectedly");
            }
        }
        return new Result(pending.size(), preserved);
    }

    public record Result(int inserted, int preserved) { }
    private record Pending(long orderId, DemoProduct product) { }
    private record DemoProduct(long shopId, String suffix, String amount,
                               String productName, String specification, String imageFile) {
        String orderNo() { return "SFDEMO20261021" + suffix; }
    }
}
