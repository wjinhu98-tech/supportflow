package dev.supportflow.testsupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Buyer order reads need the visibility marker; this helper never grants business writes. */
public final class OrderVisibilityFixtures {
    private OrderVisibilityFixtures() { }

    public static void grantRead(Connection connection, String database, String runtime) throws SQLException {
        if (database == null || runtime == null || !database.matches("[a-z_]+") || !runtime.matches("[a-z_]+")) {
            throw new IllegalArgumentException("Unexpected isolated test identifier");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("GRANT SELECT ON `" + database + "`.`support_order_buyer_deletion` TO '" + runtime + "'@'%'");
        }
    }
}
