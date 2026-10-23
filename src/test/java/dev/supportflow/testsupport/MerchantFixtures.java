package dev.supportflow.testsupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Explicit merchant authorization fixtures, used only inside disposable test databases. */
public final class MerchantFixtures {
    private MerchantFixtures() { }

    public static void grantRuntimeRead(Connection connection, String database, String runtime) throws SQLException {
        if (!database.matches("[a-z_]+") || !runtime.matches("[a-z_]+")) {
            throw new IllegalArgumentException("Unexpected isolated test identifier");
        }
        try (Statement statement = connection.createStatement()) {
            for (String table : List.of("support_merchant", "support_merchant_shop", "support_merchant_staff",
                    "support_merchant_staff_shop_grant")) {
                statement.execute("GRANT SELECT ON " + database + "." + table + " TO '" + runtime + "'@'%'");
            }
        }
    }

    public static void standardReviewers(Connection connection) throws SQLException {
        merchant(connection, 1, "Test merchant", "ACTIVE");
        shop(connection, 100, 1, "Test shop", "ACTIVE");
        staff(connection, 999, 1, "reviewer", 999, "ACTIVE");
        staff(connection, 998, 1, "reviewer-two", 998, "ACTIVE");
        staff(connection, 200, 1, "self-reviewer", 200, "ACTIVE");
        grant(connection, 999, 100, true);
        grant(connection, 998, 100, true);
        grant(connection, 200, 100, true);
    }

    public static void merchant(Connection connection, long id, String name, String status) throws SQLException {
        execute(connection, "INSERT INTO support_merchant (id,name,status) VALUES (?,?,?)", id, name, status);
    }

    public static void shop(Connection connection, long shopId, long merchantId, String name, String status)
            throws SQLException {
        execute(connection, "INSERT INTO support_merchant_shop (shop_id,merchant_id,name,status) VALUES (?,?,?,?)",
                shopId, merchantId, name, status);
    }

    public static void staff(Connection connection, long staffId, long merchantId, String username,
                             long buyerId, String status) throws SQLException {
        execute(connection, """
                INSERT INTO support_merchant_staff (id,merchant_id,username,linked_buyer_id,status)
                VALUES (?,?,?,?,?)
                """, staffId, merchantId, username, buyerId, status);
    }

    public static void grant(Connection connection, long staffId, long shopId, boolean enabled) throws SQLException {
        execute(connection, "INSERT INTO support_merchant_staff_shop_grant (staff_id,shop_id,enabled) VALUES (?,?,?)",
                staffId, shopId, enabled);
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) insert.setObject(index + 1, values[index]);
            insert.executeUpdate();
        }
    }
}
