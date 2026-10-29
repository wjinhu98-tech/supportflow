package dev.supportflow.deployment;

import org.flywaydb.core.Flyway;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import dev.supportflow.common.security.database.DatabaseAccountAdministration;
import dev.supportflow.common.security.local.LocalCustomerAccount;
import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.SecurityPermission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/** Explicit isolated-lab commands. No user-supplied JDBC URL, daily credentials or destructive reset. */
public final class LabDeploymentTool {
    static final String JDBC_URL = "jdbc:mysql://mysql:3306/supportflow_lab?connectionTimeZone=UTC"
            + "&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&sslMode=DISABLED";
    private static final Path MOUNTED_PASSWORD = Path.of("/run/secrets/lab_migrator_password");
    private static final int ORDER_COUNT = 5000;

    private LabDeploymentTool() { }

    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException("Expected one lab command");
            switch (args[0]) {
                case "prepare" -> prepare(Path.of(".local", "deployment-lab").toAbsolutePath().normalize());
                case "migrate" -> migrate();
                case "seed" -> seed();
                case "seed-accounts" -> seedAccounts();
                case "seed-shops" -> seedShops();
                case "seed-products" -> seedProducts();
                case "seed-product-images" -> seedProductImages();
                case "verify" -> verifyRuntime();
                default -> throw new IllegalArgumentException("Unknown lab command");
            }
        } catch (Exception failure) {
            // SQL/provider messages and nested causes may contain credentials; never render them here.
            System.err.println("LAB_COMMAND_FAILED type=" + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    static void prepare(Path directory) throws IOException {
        String[] expected = {"lab_root_password", "lab_app_password", "lab_migrator_password",
                "lab_buyer_password", "lab_merchant_password", "lab_ops_password",
                "lab_buyer_hash", "lab_merchant_hash", "lab_ops_hash", "login.txt"};
        if (Files.exists(directory)) {
            for (String name : expected) {
                Path existing = directory.resolve(name);
                if (!Files.isRegularFile(existing) || Files.size(existing) == 0) {
                    throw new IOException("Incomplete lab credentials; inspect the private directory manually");
                }
            }
            System.out.println("LAB_CREDENTIALS_PRESERVED");
            return;
        }
        Files.createDirectories(directory);
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : expected) {
            if (name.endsWith("_password")) values.put(name, randomPassword());
        }
        for (String role : new String[]{"buyer", "merchant", "ops"}) {
            values.put("lab_" + role + "_hash", encoder.encode(values.get("lab_" + role + "_password")));
        }
        String login = "Local isolated deployment only; do not commit or share this file.\n"
                + "Buyer: lab-buyer\nPassword: " + values.get("lab_buyer_password")
                + "\nMerchant: lab-merchant\nPassword: " + values.get("lab_merchant_password")
                + "\nOperations: lab-ops\nPassword: " + values.get("lab_ops_password") + "\n";
        values.put("login.txt", login);
        for (var value : values.entrySet()) {
            Files.writeString(directory.resolve(value.getKey()), value.getValue(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        System.out.println("LAB_CREDENTIALS_CREATED privatePath=.local/deployment-lab/login.txt");
    }

    private static String randomPassword() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String migrationPassword() throws IOException {
        String value = Files.readString(MOUNTED_PASSWORD, StandardCharsets.UTF_8).strip();
        if (!value.matches("[A-Za-z0-9_-]{24,128}")) throw new IOException("Invalid lab migration credential");
        return value;
    }

    private static void migrate() throws IOException {
        Flyway.configure().dataSource(JDBC_URL, "lab_migrator", migrationPassword())
                .locations("classpath:db/migration").validateOnMigrate(true)
                .baselineOnMigrate(false).cleanDisabled(true).createSchemas(false).load().migrate();
        System.out.println("LAB_MIGRATIONS_COMPLETE database=supportflow_lab");
    }

    private static String accountHash(String name) throws IOException {
        if (!Set.of("lab_buyer_hash", "lab_merchant_hash").contains(name)) {
            throw new IOException("Unsupported business account secret");
        }
        String hash = Files.readString(Path.of("/run/secrets", name), StandardCharsets.UTF_8).strip();
        if (!hash.matches("\\{bcrypt}\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}")) {
            throw new IOException("Invalid lab account hash");
        }
        return hash;
    }

    /** Explicit post-migration job; no ops account, account reset, or merchant-shop authorization write. */
    private static void seedAccounts() throws IOException, SQLException {
        var accounts = List.of(new LocalCustomerAccount("lab-buyer", accountHash("lab_buyer_hash"),
                        new CustomerIdentity(200),
                        Set.of(SecurityPermission.ORDER_READ, SecurityPermission.ORDER_CONFIRM_RECEIPT), AccountType.BUYER),
                new LocalCustomerAccount("lab-merchant", accountHash("lab_merchant_hash"), new CustomerIdentity(999),
                        Set.of(SecurityPermission.ORDER_READ, SecurityPermission.AFTER_SALE_REVIEW,
                                SecurityPermission.KNOWLEDGE_MANAGE), AccountType.MERCHANT_STAFF));
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "lab_migrator", migrationPassword())) {
            connection.setAutoCommit(false);
            try {
                try (Statement check = connection.createStatement(); ResultSet rows = check.executeQuery("SELECT DATABASE()")) {
                    if (!rows.next() || !"supportflow_lab".equals(rows.getString(1))) {
                        throw new SQLException("Lab database identity did not match");
                    }
                }
                var administration = new DatabaseAccountAdministration(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
                int inserted = administration.importMissingConfiguredAccounts(accounts);
                connection.commit();
                System.out.println("LAB_ACCOUNTS_READY accountsInserted=" + inserted
                        + " existingAccountsPreserved=true staffGrantsChanged=0 opsBusinessAccounts=0");
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void seed() throws IOException, SQLException {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "lab_migrator", migrationPassword())) {
            connection.setAutoCommit(false);
            try {
                try (Statement check = connection.createStatement();
                     ResultSet row = check.executeQuery("SELECT DATABASE()")) {
                    if (!row.next() || !"supportflow_lab".equals(row.getString(1))) {
                        throw new SQLException("Lab database identity did not match");
                    }
                }
                try (Statement check = connection.createStatement();
                     ResultSet row = check.executeQuery("SELECT COUNT(*) FROM support_order")) {
                    row.next();
                    if (row.getLong(1) != 0) {
                        verifyExistingFixture(connection);
                        connection.rollback();
                        System.out.println("LAB_EXISTING_DATA_PRESERVED");
                        return;
                    }
                }
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO support_merchant (id,name) VALUES (1,'Isolated Lab Merchant')");
                    statement.executeUpdate("INSERT INTO support_merchant_shop (shop_id,merchant_id,name) "
                            + "VALUES (100,1,'Isolated Lab Shop')");
                    statement.executeUpdate("INSERT INTO support_merchant_staff (id,merchant_id,username,linked_buyer_id) "
                            + "VALUES (1,1,'lab-merchant',999)");
                    statement.executeUpdate("INSERT INTO support_merchant_staff_shop_grant (staff_id,shop_id,enabled) "
                            + "VALUES (1,100,TRUE)");
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO support_order
                          (tenant_id,customer_id,buyer_id,order_no,status,total_amount,currency)
                        VALUES (100,200,200,?,'SHIPPED',?,'CNY')
                        """)) {
                    for (int index = 1; index <= ORDER_COUNT; index++) {
                        insert.setString(1, "LAB-%06d".formatted(index));
                        insert.setBigDecimal(2, new BigDecimal("199.90"));
                        insert.addBatch();
                        if (index % 500 == 0) insert.executeBatch();
                    }
                }
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("""
                            INSERT INTO support_order (tenant_id,customer_id,buyer_id,order_no,status,total_amount,currency)
                            VALUES (101,200,200,'LAB-000001','PAID',9.90,'CNY'),
                                   (100,201,201,'LAB-OTHER-OWNER','PAID',1.00,'CNY')
                            """);
                }
                connection.commit();
                System.out.println("LAB_FIXTURE_CREATED shop100Buyer200Orders=5000 isolationOrders=2");
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void verifyExistingFixture(Connection connection) throws SQLException {
        try (PreparedStatement check = connection.prepareStatement("""
                SELECT COUNT(*) FROM support_order
                WHERE tenant_id=100 AND buyer_id=200 AND order_no='LAB-000001'
                """); ResultSet row = check.executeQuery()) {
            if (!row.next() || row.getLong(1) != 1) throw new SQLException("Existing data is not the expected lab fixture");
        }
    }

    private static void seedShops() throws IOException, SQLException {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "lab_migrator", migrationPassword())) {
            connection.setAutoCommit(false);
            try {
                verifyExistingFixture(connection);
                var seeded = DemoShopData.seed(connection);
                connection.commit();
                System.out.println("LAB_DEMO_SHOPS_READY ids=102,103 shopsInserted=" + seeded.shopsInserted()
                        + " ordersInserted=" + seeded.ordersInserted() + " staffGrantsChanged=0");
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void seedProducts() throws IOException, SQLException {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "lab_migrator", migrationPassword())) {
            connection.setAutoCommit(false);
            try {
                verifyExistingFixture(connection);
                var seeded = DemoProductData.seed(connection);
                connection.commit();
                System.out.println("LAB_DEMO_PRODUCTS_READY shops=102,103 snapshotsInserted=" + seeded.snapshotsInserted()
                        + " snapshotsPreserved=" + seeded.snapshotsPreserved() + " staffGrantsChanged=0");
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void seedProductImages() throws IOException, SQLException {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "lab_migrator", migrationPassword())) {
            connection.setAutoCommit(false);
            try {
                verifyExistingFixture(connection);
                var products = DemoProductData.seed(connection);
                var images = DemoProductImageData.seed(connection);
                connection.commit();
                System.out.println("LAB_DEMO_PRODUCT_IMAGES_READY shops=102,103 snapshotsInserted=" + products.snapshotsInserted()
                        + " imagesInserted=" + images.imagesInserted() + " imagesPreserved=" + images.imagesPreserved()
                        + " staffGrantsChanged=0");
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void verifyRuntime() throws IOException, SQLException {
        String password = Files.readString(Path.of("/run/secrets/lab_app_password"), StandardCharsets.UTF_8).strip();
        if (!password.matches("[A-Za-z0-9_-]{24,128}")) throw new IOException("Invalid lab application credential");
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "lab_app", password);
             Statement query = connection.createStatement()) {
            boolean buyerDeletionInsert = false;
            boolean knowledgeImportInsert = false;
            boolean knowledgeVectorInsert = false;
            try (ResultSet grants = query.executeQuery("SHOW GRANTS")) {
                while (grants.next()) {
                    String grant = grants.getString(1).replace("\\_", "_");
                    if (grant.startsWith("GRANT USAGE ON *.*")) continue;
                    if (!grant.contains(" ON `supportflow_lab`.") || grant.matches(".*\\b(DELETE|DROP|CREATE|ALTER|INDEX)\\b.*")
                            || grant.contains("GRANT OPTION") || grant.contains("ALL PRIVILEGES")) {
                        throw new SQLException("Lab runtime privileges exceed the configured boundary");
                    }
                    if (grant.contains(" ON `supportflow_lab`.`support_order_buyer_deletion` ")
                            && grant.substring(0, grant.indexOf(" ON ")).matches(".*\\bINSERT\\b.*")) {
                        buyerDeletionInsert = true;
                    }
                    if (grant.contains(" ON `supportflow_lab`.`support_knowledge_import` ")
                            && grant.substring(0, grant.indexOf(" ON ")).matches(".*\\bINSERT\\b.*")) {
                        knowledgeImportInsert = true;
                    }
                    if (grant.contains(" ON `supportflow_lab`.`support_knowledge_vector` ")
                            && grant.substring(0, grant.indexOf(" ON ")).matches(".*\\bINSERT\\b.*")) {
                        knowledgeVectorInsert = true;
                    }
                }
            }
            if (!buyerDeletionInsert) throw new SQLException("Buyer deletion insert privilege is missing");
            if (!knowledgeImportInsert) throw new SQLException("Knowledge import insert privilege is missing");
            if (!knowledgeVectorInsert) throw new SQLException("Knowledge vector insert privilege is missing");
            // All denied statements have a false predicate: even a misconfigured grant cannot change rows.
            for (String denied : new String[]{
                    "UPDATE support_order SET total_amount=total_amount WHERE 1=0",
                    "UPDATE support_order SET buyer_id=buyer_id WHERE 1=0",
                    "UPDATE support_merchant_staff SET username=username WHERE 1=0",
                    "UPDATE support_order_buyer_deletion SET order_id=order_id WHERE 1=0",
                    "DELETE FROM support_order_buyer_deletion WHERE 1=0",
                    "UPDATE support_knowledge_import SET tenant_id=tenant_id WHERE 1=0",
                    "DELETE FROM support_knowledge_import WHERE 1=0",
                    "UPDATE support_knowledge_vector SET dimensions=dimensions WHERE 1=0",
                    "DELETE FROM support_knowledge_vector WHERE 1=0",
                    "UPDATE support_security_account SET enabled=enabled WHERE 1=0",
                    "DELETE FROM support_security_account WHERE 1=0",
                    "INSERT INTO support_security_account (username,password_hash,principal_id,account_type) "
                            + "SELECT username,password_hash,principal_id,account_type FROM support_security_account WHERE 1=0",
                    "DELETE FROM support_order WHERE 1=0"}) {
                boolean rejected = false;
                try {
                    query.executeUpdate(denied);
                } catch (SQLException expected) {
                    if (expected.getErrorCode() != 1142 && expected.getErrorCode() != 1143) throw expected;
                    rejected = true;
                }
                if (!rejected) throw new SQLException("A forbidden runtime operation was authorized");
            }
            try (ResultSet ignored = query.executeQuery("SELECT order_id FROM support_order_buyer_deletion WHERE 1=0")) {
                if (ignored.next()) throw new SQLException("Empty privilege probe returned a row");
            }
            try (ResultSet ignored = query.executeQuery("SELECT tenant_id FROM support_knowledge_import WHERE 1=0")) {
                if (ignored.next()) throw new SQLException("Empty privilege probe returned a row");
            }
            try (ResultSet ignored = query.executeQuery("SELECT username,enabled,security_version FROM support_security_account WHERE 1=0")) {
                if (ignored.next()) throw new SQLException("Empty privilege probe returned a row");
            }
            int vectorRows = query.executeUpdate("""
                    INSERT INTO support_knowledge_vector
                    (tenant_id,source_id,source_version,content_hash,model_fingerprint,chunk_no,dimensions,vector_bytes)
                    SELECT tenant_id,source_id,source_version,content_hash,model_fingerprint,chunk_no,dimensions,vector_bytes
                    FROM support_knowledge_vector WHERE 1=0
                    """);
            if (vectorRows != 0) throw new SQLException("Empty vector insert privilege probe changed a row");
            try (ResultSet count = query.executeQuery("SELECT COUNT(*) FROM support_order WHERE tenant_id=100 AND buyer_id=200")) {
                if (!count.next() || count.getLong(1) != ORDER_COUNT) throw new SQLException("Lab fixture count differs");
            }
            System.out.println("LAB_RUNTIME_PRIVILEGES=PASS shop100Buyer200Orders=5000 buyerDeletionImmutable=true"
                    + " knowledgeImportsImmutable=true securityAccountsReadOnly=true knowledgeVectorsAppendOnly=true");
        }
    }
}
