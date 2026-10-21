import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explicit local development fixture loader; never part of application startup or Flyway. */
public final class LocalDemoData {
    private static final String PREFIX = "SFDEMO20261021";
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/supportflow_rebuild"
            + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=5000&socketTimeout=15000";
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 15, 1, 0);
    private static final List<Seed> SEEDS = List.of(
            new Seed(100, 200, "01", "CREATED", "59.90", List.of()),
            new Seed(100, 200, "02", "PAID", "199.00", List.of()),
            new Seed(100, 200, "03", "SHIPPED", "299.90", List.of("学习版仓库：已出库", "学习版配送中心：运输中")),
            new Seed(100, 200, "04", "COMPLETED", "89.00", List.of("学习版仓库：已出库", "学习版配送站：派送中", "学习版配送记录：已签收")),
            new Seed(100, 200, "05", "CANCELLED", "39.90", List.of()),
            new Seed(100, 200, "06", "SHIPPED", "1299.00", List.of("学习版仓库：已出库", "学习版转运中心：运输中", "学习版配送站：待派送")),
            new Seed(100, 200, "07", "PAID", "26.80", List.of()),
            new Seed(100, 200, "08", "COMPLETED", "168.00", List.of("学习版仓库：已出库", "学习版配送记录：已签收")),
            new Seed(101, 200, "03", "SHIPPED", "399.90", List.of("学习版店铺101仓库：已出库", "学习版店铺101配送中心：运输中")),
            new Seed(101, 200, "09", "PAID", "78.00", List.of()),
            new Seed(100, 201, "91", "PAID", "888.00", List.of()),
            new Seed(100, 201, "92", "SHIPPED", "666.00", List.of("学习版另一买家订单：已出库"))
    );

    public static void main(String[] args) {
        if (args.length != 1 || !(args[0].equals("inspect") || args[0].equals("apply"))) {
            System.err.println("Usage: LocalDemoData.java inspect|apply (run from repository root)");
            System.exit(2);
        }
        try {
            Path root = Path.of("").toAbsolutePath().normalize();
            if (!Files.isRegularFile(root.resolve("pom.xml"))
                    || !Files.isRegularFile(root.resolve("src/main/resources/db/migration/V4__assistant_business_modules.sql"))) {
                throw new IllegalStateException("Run only from the supportflow-rebuild repository root");
            }
            // Credentials are consumed locally, never logged or copied into source/command arguments.
            String password = System.getenv("SUPPORTFLOW_MIGRATOR_PASSWORD");
            if (password == null || password.isBlank()) {
                try (InputStream input = Files.newInputStream(root.resolve(".local/secrets.yml"))) {
                    Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
                    if (!(document instanceof Map<?, ?> values)
                            || !(values.get("SUPPORTFLOW_MIGRATOR_PASSWORD") instanceof String value)
                            || value.isBlank()) {
                        throw new IllegalStateException("Local migrator password property is missing");
                    }
                    password = value;
                }
            }
            try (Connection connection = DriverManager.getConnection(URL, "supportflow_migrator", password)) {
                verifyDatabase(connection);
                if (args[0].equals("apply")) apply(connection);
                report(connection);
            }
        } catch (SQLException failure) {
            System.err.printf("Database operation failed: SQLState=%s, code=%d. Transaction rolled back.%n",
                    failure.getSQLState(), failure.getErrorCode());
            System.exit(1);
        } catch (Exception failure) {
            // Do not print parser exceptions, which might include lines from a private YAML file.
            System.err.println("Seed operation stopped: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static void verifyDatabase(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement();
             ResultSet rows = sql.executeQuery("SELECT DATABASE(), CURRENT_USER(), VERSION()")) {
            rows.next();
            if (!"supportflow_rebuild".equals(rows.getString(1))) throw new SQLException("Unexpected database");
            System.out.printf("Target: 127.0.0.1:3306/%s; account=%s; MySQL=%s%n",
                    rows.getString(1), rows.getString(2), rows.getString(3));
        }
        try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='4' AND success=1")) {
            rows.next();
            if (rows.getInt(1) != 1) throw new SQLException("V4 migration must be applied first");
        }
    }

    private static void apply(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        int insertedOrders = 0;
        int insertedEvents = 0;
        List<String> output = new ArrayList<>();
        try {
            for (Seed seed : SEEDS) {
                long id = existingOrder(connection, seed);
                if (id == 0) {
                    id = insertOrder(connection, seed);
                    insertedOrders++;
                }
                for (int index = 0; index < seed.events().size(); index++) {
                    insertedEvents += insertEvent(connection, id, BASE.plusDays(index + 1), seed.events().get(index));
                }
                output.add("shop=" + seed.shop() + ", buyer=" + seed.buyer() + ", order=" + seed.orderNo()
                        + ", status=" + seed.status() + ", amount=" + seed.amount());
            }
            connection.commit();
            System.out.printf("COMMITTED: insertedOrders=%d, insertedShipmentEvents=%d%n", insertedOrders, insertedEvents);
            output.forEach(System.out::println);
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static long existingOrder(Connection connection, Seed seed) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement("""
                SELECT id, customer_id, buyer_id, status, total_amount, currency
                FROM support_order WHERE tenant_id=? AND order_no=? FOR UPDATE
                """)) {
            sql.setLong(1, seed.shop());
            sql.setString(2, seed.orderNo());
            try (ResultSet rows = sql.executeQuery()) {
                if (!rows.next()) return 0;
                if (rows.getLong("customer_id") != seed.buyer() || rows.getLong("buyer_id") != seed.buyer()
                        || !seed.status().equals(rows.getString("status"))
                        || seed.amount().compareTo(rows.getBigDecimal("total_amount")) != 0
                        || !"CNY".equals(rows.getString("currency"))) {
                    throw new SQLException("Seed order collision; existing row is preserved", "45000");
                }
                return rows.getLong("id");
            }
        }
    }

    private static long insertOrder(Connection connection, Seed seed) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement("""
                INSERT INTO support_order
                  (tenant_id, customer_id, buyer_id, order_no, status, total_amount, currency, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'CNY', ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            sql.setLong(1, seed.shop());
            sql.setLong(2, seed.buyer());
            sql.setLong(3, seed.buyer());
            sql.setString(4, seed.orderNo());
            sql.setString(5, seed.status());
            sql.setBigDecimal(6, seed.amount());
            sql.setObject(7, BASE);
            sql.setObject(8, seed.events().isEmpty() ? BASE : BASE.plusDays(seed.events().size()));
            sql.executeUpdate();
            try (ResultSet keys = sql.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing generated ID");
                return keys.getLong(1);
            }
        }
    }

    private static int insertEvent(Connection connection, long orderId, LocalDateTime time, String description)
            throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement("""
                INSERT INTO support_shipment_event (order_id, occurred_at, description)
                SELECT ?, ?, ? WHERE NOT EXISTS (
                  SELECT 1 FROM support_shipment_event WHERE order_id=? AND occurred_at=? AND description=?
                )
                """)) {
            sql.setLong(1, orderId);
            sql.setObject(2, time);
            sql.setString(3, description);
            sql.setLong(4, orderId);
            sql.setObject(5, time);
            sql.setString(6, description);
            return sql.executeUpdate();
        }
    }

    private static void report(Connection connection) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement("""
                SELECT tenant_id, buyer_id, COUNT(*) AS orders_count
                FROM support_order WHERE order_no LIKE ? GROUP BY tenant_id, buyer_id ORDER BY tenant_id, buyer_id
                """)) {
            sql.setString(1, PREFIX + "%");
            try (ResultSet rows = sql.executeQuery()) {
                while (rows.next()) System.out.printf("Verified: shop=%d, buyer=%d, demoOrders=%d%n",
                        rows.getLong(1), rows.getLong(2), rows.getLong(3));
            }
        }
        try (PreparedStatement sql = connection.prepareStatement("""
                SELECT COUNT(*) FROM support_shipment_event event
                JOIN support_order orders ON orders.id=event.order_id WHERE orders.order_no LIKE ?
                """)) {
            sql.setString(1, PREFIX + "%");
            try (ResultSet rows = sql.executeQuery()) {
                rows.next();
                System.out.println("Verified: demoShipmentEvents=" + rows.getInt(1));
            }
        }
    }

    private record Seed(long shop, long buyer, String suffix, String status, String price, List<String> events) {
        String orderNo() { return PREFIX + suffix; }
        BigDecimal amount() { return new BigDecimal(price); }
    }
}
