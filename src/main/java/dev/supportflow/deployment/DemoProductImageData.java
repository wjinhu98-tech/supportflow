package dev.supportflow.deployment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit local learning images only; no catalogue lookup, remote fetch or historical inference. */
public final class DemoProductImageData {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SNAPSHOT_FIELDS = Set.of("productName", "specification", "quantity", "imageUrl");
    private static final List<DemoImage> IMAGES = List.of(
            new DemoImage(102, "数码优选店", "SF102001", "199.90", "/products/demo/black-earbuds.png"),
            new DemoImage(102, "数码优选店", "SF102002", "59.90", "/products/demo/white-mouse.png"),
            new DemoImage(103, "生活家居店", "SF103001", "129.00", "/products/demo/white-mug.png"),
            new DemoImage(103, "生活家居店", "SF103002", "89.00", "/products/demo/cotton-bedding.png"));

    private DemoProductImageData() { }

    /** Caller owns commit/rollback; every identity is verified and locked before any image is written. */
    public static Result seed(Connection connection) throws SQLException {
        if (connection.getAutoCommit()) throw new IllegalArgumentException("Demo image seeding requires an explicit transaction");
        long merchant;
        try (var query = connection.prepareStatement("""
                SELECT shop.merchant_id FROM support_merchant_shop shop
                JOIN support_merchant merchant ON merchant.id=shop.merchant_id
                WHERE shop.shop_id=100 AND shop.status='ACTIVE' AND merchant.status='ACTIVE' FOR UPDATE
                """); var row = query.executeQuery()) {
            if (!row.next()) throw new SQLException("An active demo base shop and merchant are required");
            merchant = row.getLong(1);
        }
        var pending = new ArrayList<Pending>();
        int preserved = 0;
        for (DemoImage image : IMAGES) {
            try (var query = connection.prepareStatement("""
                    SELECT o.id,o.buyer_id,o.customer_id,o.total_amount,o.currency,o.product_items_json,
                           shop.merchant_id,shop.name,shop.status
                    FROM support_order o JOIN support_merchant_shop shop ON shop.shop_id=o.tenant_id
                    WHERE o.tenant_id=? AND o.order_no=? FOR UPDATE
                    """)) {
                query.setLong(1, image.shopId());
                query.setString(2, image.orderNo());
                try (var row = query.executeQuery()) {
                    if (!row.next() || row.getLong("buyer_id") != 200 || row.getLong("customer_id") != 200
                            || row.getBigDecimal("total_amount").compareTo(new BigDecimal(image.amount())) != 0
                            || !"CNY".equals(row.getString("currency")) || row.getLong("merchant_id") != merchant
                            || !image.shopName().equals(row.getString("name")) || !"ACTIVE".equals(row.getString("status"))) {
                        throw new SQLException("An expected learning order or shop does not match; no product images were applied");
                    }
                    String original = row.getString("product_items_json");
                    String updated = addMissingImage(original, image);
                    if (updated == null) preserved++;
                    else pending.add(new Pending(row.getLong("id"), image.shopId(), original, updated));
                }
            }
        }
        try (var update = connection.prepareStatement("""
                UPDATE support_order SET product_items_json=?,updated_at=updated_at
                WHERE id=? AND tenant_id=? AND buyer_id=200 AND customer_id=200 AND product_items_json=CAST(? AS JSON)
                """)) {
            for (Pending candidate : pending) {
                update.setString(1, candidate.updated());
                update.setLong(2, candidate.orderId());
                update.setLong(3, candidate.shopId());
                update.setString(4, candidate.original());
                if (update.executeUpdate() != 1) throw new SQLException("A verified demo image candidate changed unexpectedly");
            }
        }
        return new Result(pending.size(), preserved);
    }

    /** A known order alone is insufficient: preserve any different or unknown purchased product. */
    private static String addMissingImage(String snapshot, DemoImage image) throws SQLException {
        if (snapshot == null) return null;
        try {
            JsonNode items = JSON.readTree(snapshot);
            if (!items.isArray() || items.size() != 1) return null;
            JsonNode item = items.get(0);
            if (!item.isObject() || !SNAPSHOT_FIELDS.containsAll(item.propertyNames())) return null;
            JsonNode existingImage = item.get("imageUrl");
            if (existingImage != null && !existingImage.isNull()) return null;
            JsonNode expected = JSON.readTree(DemoProductData.snapshotFor(image.shopId(), image.orderNo())).get(0);
            if (!expected.get("productName").equals(item.get("productName"))
                    || !expected.get("specification").equals(item.get("specification"))
                    || item.get("quantity") == null || !item.get("quantity").isIntegralNumber()
                    || !item.get("quantity").canConvertToInt() || item.get("quantity").intValue() != 1) return null;
            return JSON.writeValueAsString(List.of(Map.of("productName", item.get("productName").asString(),
                    "specification", item.get("specification").asString(), "quantity", 1, "imageUrl", image.imageUrl())));
        } catch (RuntimeException invalid) {
            throw new SQLException("Invalid persisted demo product snapshot");
        }
    }

    public record Result(int imagesInserted, int imagesPreserved) { }
    private record Pending(long orderId, long shopId, String original, String updated) { }
    private record DemoImage(long shopId, String shopName, String orderNo, String amount, String imageUrl) { }
}
