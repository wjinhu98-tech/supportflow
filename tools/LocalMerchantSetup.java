import dev.supportflow.common.security.local.LocalCustomerAccountsProperties;
import dev.supportflow.common.security.model.SecurityPermission;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/** Explicit, local-only enrollment after the operator authorizes shop 100. No passwords are changed or logged. */
public final class LocalMerchantSetup {
    private static final String USERNAME = "support-reviewer";
    private static final String MERCHANT_NAME = "本地学习商家100";
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/supportflow_rebuild"
            + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=5000&socketTimeout=15000";

    public static void main(String[] args) {
        if (args.length != 1 || !java.util.Set.of("inspect", "bootstrap-shop-100").contains(args[0])) {
            System.err.println("Usage: LocalMerchantSetup.java inspect|bootstrap-shop-100");
            System.exit(2);
        }
        try {
            if (!Files.isRegularFile(Path.of("src/main/resources/db/migration/V9__merchant_shop_authorization.sql"))) {
                throw new IllegalStateException("Run from the intended repository after V9");
            }
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("local-merchant-setup",
                    Map.of("spring.config.location", "file:./src/main/resources/")));
            ConfigDataEnvironmentPostProcessor.applyTo(environment, null, null, "local");
            var accounts = Binder.get(environment)
                    .bind("supportflow.security.local", LocalCustomerAccountsProperties.class)
                    .orElseThrow(() -> new IllegalStateException("Local account configuration missing"));
            var reviewer = accounts.accounts().stream().filter(account -> account.username().equals(USERNAME))
                    .findFirst().orElseThrow();
            if (!reviewer.permissions().containsAll(java.util.Set.of(
                    SecurityPermission.ORDER_READ, SecurityPermission.AFTER_SALE_REVIEW))) {
                throw new IllegalStateException("Existing reviewer lacks the required explicit permissions");
            }
            YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new FileSystemResource(".local/secrets.yml"));
            var secrets = yaml.getObject();
            String password = secrets == null ? null : secrets.getProperty("SUPPORTFLOW_MIGRATOR_PASSWORD");
            if (password == null || password.isBlank()) throw new IllegalStateException("Local migrator credential missing");
            try (Connection connection = DriverManager.getConnection(URL, "supportflow_migrator", password)) {
                if (!"supportflow_rebuild".equals(connection.getCatalog())) throw new IllegalStateException("Wrong database");
                if (args[0].equals("bootstrap-shop-100")) bootstrap(connection, reviewer.identity().buyerId());
                inspect(connection, reviewer.identity().buyerId());
            }
            System.out.println("LOCAL_ACCOUNT_TYPE=" + reviewer.accountType() + " CREDENTIALS_CHANGED=false");
        } catch (SQLException failure) {
            System.err.println("Merchant setup SQL failure: state=" + failure.getSQLState() + " code=" + failure.getErrorCode());
            System.exit(1);
        } catch (Exception failure) {
            System.err.println("Merchant setup stopped: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static void bootstrap(Connection connection, long linkedBuyerId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            if (number(connection, "SELECT COUNT(*) FROM support_order WHERE tenant_id=100") == 0) {
                throw new IllegalStateException("The explicitly authorized local shop 100 does not exist");
            }
            Long merchantId = optionalNumber(connection,
                    "SELECT merchant_id FROM support_merchant_shop WHERE shop_id=100 FOR UPDATE");
            if (merchantId != null) {
                try (PreparedStatement statement = statement(connection,
                        "SELECT name, status FROM support_merchant WHERE id=? FOR UPDATE", merchantId);
                     ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || !MERCHANT_NAME.equals(rows.getString(1)) || !"ACTIVE".equals(rows.getString(2))) {
                        throw new IllegalStateException("Shop is already owned by another merchant; nothing is reassigned");
                    }
                }
            } else {
                merchantId = optionalNumber(connection,
                        "SELECT id FROM support_merchant WHERE name=? AND status='ACTIVE' FOR UPDATE", MERCHANT_NAME);
                if (merchantId == null) merchantId = insert(connection,
                        "INSERT INTO support_merchant (name,status) VALUES (?,'ACTIVE')", MERCHANT_NAME);
                execute(connection, "INSERT INTO support_merchant_shop (shop_id,merchant_id,name,status) VALUES (100,?,?,'ACTIVE')",
                        merchantId, "店铺100");
            }
            Long staffId = optionalNumber(connection,
                    "SELECT id FROM support_merchant_staff WHERE username=? FOR UPDATE", USERNAME);
            if (staffId == null) {
                staffId = insert(connection,
                        "INSERT INTO support_merchant_staff (merchant_id,username,linked_buyer_id,status) VALUES (?,?,?,'ACTIVE')",
                        merchantId, USERNAME, linkedBuyerId);
            } else {
                if (number(connection, "SELECT COUNT(*) FROM support_merchant_staff WHERE id=? AND merchant_id=? "
                        + "AND linked_buyer_id=? AND status='ACTIVE'", staffId, merchantId, linkedBuyerId) != 1) {
                    throw new IllegalStateException("Existing employee binding differs; it is preserved");
                }
            }
            if (number(connection, "SELECT COUNT(*) FROM support_merchant_staff_shop_grant "
                    + "WHERE staff_id=? AND shop_id<>100 AND enabled=TRUE", staffId) != 0) {
                throw new IllegalStateException("Other active grants exist; operator must review them first");
            }
            execute(connection, "INSERT INTO support_merchant_staff_shop_grant (staff_id,shop_id,enabled) VALUES (?,100,TRUE) "
                    + "ON DUPLICATE KEY UPDATE enabled=TRUE", staffId);
            connection.commit();
            System.out.println("LOCAL_MERCHANT_ENROLLMENT=COMPLETE AUTHORIZED_SHOP=100 OTHER_SHOPS=NONE");
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void inspect(Connection connection, long buyerId) throws SQLException {
        try (PreparedStatement statement = statement(connection, """
                SELECT s.id, g.shop_id FROM support_merchant_staff s
                JOIN support_merchant m ON m.id=s.merchant_id AND m.status='ACTIVE'
                JOIN support_merchant_staff_shop_grant g ON g.staff_id=s.id AND g.enabled=TRUE
                JOIN support_merchant_shop p ON p.shop_id=g.shop_id AND p.merchant_id=s.merchant_id AND p.status='ACTIVE'
                WHERE s.username=? AND s.linked_buyer_id=? AND s.status='ACTIVE' ORDER BY g.shop_id
                """, USERNAME, buyerId); ResultSet rows = statement.executeQuery()) {
            int count = 0;
            while (rows.next()) {
                count++;
                System.out.println("LOCAL_REVIEWER_STAFF_ID=" + rows.getLong(1) + " AUTHORIZED_SHOP=" + rows.getLong(2));
                if (rows.getLong(2) != 100) throw new IllegalStateException("Unexpected authorization scope");
            }
            System.out.println("AUTHORIZED_SHOP_COUNT=" + count);
        }
    }

    private static PreparedStatement statement(Connection connection, String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
        return statement;
    }

    private static Long optionalNumber(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = statement(connection, sql, values); ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) return null;
            long value = rows.getLong(1);
            if (rows.next()) throw new IllegalStateException("Ambiguous setup records; nothing is overwritten");
            return value;
        }
    }

    private static long number(Connection connection, String sql, Object... values) throws SQLException {
        Long value = optionalNumber(connection, sql, values);
        if (value == null) throw new IllegalStateException("Expected setup result is missing");
        return value;
    }

    private static int execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = statement(connection, sql, values)) { return statement.executeUpdate(); }
    }

    private static long insert(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Enrollment insert did not complete");
            try (ResultSet rows = statement.getGeneratedKeys()) {
                if (!rows.next()) throw new IllegalStateException("Enrollment identifier missing");
                return rows.getLong(1);
            }
        }
    }
}
