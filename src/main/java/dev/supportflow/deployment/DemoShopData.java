package dev.supportflow.deployment;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Explicit demo seeding only. Never a Spring bean or automatic database migration; never grants staff access. */
public final class DemoShopData {
    private DemoShopData() { }

    public static Result seed(Connection connection) throws SQLException {
        if (connection.getAutoCommit()) throw new IllegalArgumentException("Demo seeding requires an explicit transaction");
        long merchant;
        try (var query = connection.prepareStatement("SELECT merchant_id FROM support_merchant_shop WHERE shop_id=100 AND status='ACTIVE'");
             var row = query.executeQuery()) {
            if (!row.next()) throw new SQLException("An active demo base shop is required");
            merchant = row.getLong(1);
        }
        int shops = 0;
        int orders = 0;
        for (var shop : new DemoShop[]{new DemoShop(102, "数码优选店", "199.90", "59.90"),
                new DemoShop(103, "生活家居店", "129.00", "89.00")}) {
            try (var check = connection.prepareStatement("SELECT merchant_id,name FROM support_merchant_shop WHERE shop_id=?")) {
                check.setLong(1, shop.id());
                try (var row = check.executeQuery()) {
                    if (row.next()) {
                        if (row.getLong(1) != merchant || !row.getString(2).equals(shop.name()))
                            throw new SQLException("A requested demo shop ID is already in use");
                    } else try (var insert = connection.prepareStatement("INSERT INTO support_merchant_shop(shop_id,merchant_id,name) VALUES (?,?,?)")) {
                        insert.setLong(1, shop.id()); insert.setLong(2, merchant); insert.setString(3, shop.name());
                        shops += insert.executeUpdate();
                    }
                }
            }
            for (int index = 1; index <= 2; index++) {
                String number = "SF" + shop.id() + "00" + index;
                long orderId;
                boolean inserted = false;
                try (var find = connection.prepareStatement("SELECT id,buyer_id FROM support_order WHERE tenant_id=? AND order_no=?")) {
                    find.setLong(1, shop.id()); find.setString(2, number);
                    try (var row = find.executeQuery()) {
                        if (row.next()) {
                            if (row.getLong(2) != 200) throw new SQLException("A demo order belongs to another buyer");
                            orderId = row.getLong(1);
                        } else try (var insert = connection.prepareStatement("""
                                INSERT INTO support_order(tenant_id,customer_id,buyer_id,order_no,status,total_amount,currency,product_items_json)
                                VALUES (?,200,200,?,?,?,'CNY',?)
                                """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                            insert.setLong(1, shop.id()); insert.setString(2, number);
                            insert.setString(3, index == 1 ? "SHIPPED" : "PAID");
                            insert.setBigDecimal(4, new BigDecimal(index == 1 ? shop.shippedAmount() : shop.paidAmount()));
                            insert.setString(5, DemoProductData.snapshotFor(shop.id(), number));
                            orders += insert.executeUpdate();
                            try (var keys = insert.getGeneratedKeys()) {
                                if (!keys.next()) throw new SQLException("Demo order ID unavailable");
                                orderId = keys.getLong(1);
                            }
                            inserted = true;
                        }
                    }
                }
                if (inserted && index == 1) try (var event = connection.prepareStatement("""
                        INSERT INTO support_shipment_event(order_id,occurred_at,description)
                        VALUES (?,TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(3)),?)
                        """)) {
                    event.setLong(1, orderId); event.setString(2, shop.name() + "：演示仓库已出库（学习数据）");
                    event.executeUpdate();
                }
            }
        }
        return new Result(shops, orders);
    }
    public record Result(int shopsInserted, int ordersInserted) { }
    private record DemoShop(long id, String name, String shippedAmount, String paidAmount) { }
}
