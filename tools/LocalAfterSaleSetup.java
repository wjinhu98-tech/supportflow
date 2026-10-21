import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import dev.supportflow.common.security.local.LocalCustomerAccountsProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.StringReader;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** Explicit local setup operations; credentials remain inside this process. */
public final class LocalAfterSaleSetup {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/supportflow_rebuild"
            + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=5000&socketTimeout=15000";

    public static void main(String[] args) {
        if (args.length != 1 || !java.util.Set.of("inspect", "migrate", "configure-reviewer", "verify-demo").contains(args[0])) {
            System.err.println("Usage: LocalAfterSaleSetup.java inspect|migrate|configure-reviewer|verify-demo");
            System.exit(2);
        }
        try {
            if (!Files.isRegularFile(ROOT.resolve("src/main/resources/db/migration/V5__after_sale_execution.sql"))) {
                throw new IllegalStateException("Run from the supportflow-rebuild repository root");
            }
            if (args[0].equals("configure-reviewer")) {
                configureReviewer();
                return;
            }
            Properties secrets = yaml(".local/secrets.yml");
            String migratorPassword = credential(secrets, "SUPPORTFLOW_MIGRATOR_PASSWORD");
            String appPassword = credential(secrets, "SUPPORTFLOW_APP_PASSWORD");
            if (args[0].equals("verify-demo")) {
                verifyDemo(appPassword);
                return;
            }
            if (args[0].equals("migrate")) {
                var result = Flyway.configure().dataSource(URL, "supportflow_migrator", migratorPassword)
                        .locations("filesystem:src/main/resources/db/migration")
                        .validateOnMigrate(true).baselineOnMigrate(false).cleanDisabled(true).createSchemas(false)
                        .load().migrate();
                System.out.println("MIGRATIONS_APPLIED=" + result.migrationsExecuted);
            }
            try (Connection connection = DriverManager.getConnection(URL, "supportflow_migrator", migratorPassword);
                 Statement sql = connection.createStatement()) {
                try (ResultSet rows = sql.executeQuery("SELECT DATABASE(), CURRENT_USER(), VERSION()")) {
                    rows.next();
                    if (!"supportflow_rebuild".equals(rows.getString(1))) throw new IllegalStateException("Wrong database");
                    System.out.printf("DATABASE=%s ACCOUNT=%s MYSQL=%s%n", rows.getString(1), rows.getString(2), rows.getString(3));
                }
                try (ResultSet rows = sql.executeQuery("SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
                    while (rows.next()) System.out.printf("MIGRATION=%s SUCCESS=%s%n", rows.getString(1), rows.getBoolean(2));
                }
                try (ResultSet rows = sql.executeQuery("SHOW GRANTS")) {
                    boolean grantOption = false;
                    while (rows.next()) grantOption |= rows.getString(1).contains("WITH GRANT OPTION");
                    System.out.println("MIGRATOR_CAN_GRANT=" + grantOption);
                }
            }
            try (Connection connection = DriverManager.getConnection(URL, "supportflow_app", appPassword);
                 Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery("SHOW GRANTS")) {
                while (rows.next()) System.out.println("APP_PERMISSION=" + rows.getString(1));
            }
            // Only report non-secret account metadata; never print hashes or private YAML contents.
            for (var account : accounts().accounts()) {
                System.out.printf("LOCAL_ACCOUNT=%s BUYER_ID=%s PERMISSIONS=%s%n",
                        account.username(), account.identity().buyerId(), account.permissions());
            }
            boolean adminConfigured = (System.getenv("SUPPORTFLOW_ADMIN_PASSWORD") != null)
                    || secrets.containsKey("SUPPORTFLOW_ADMIN_PASSWORD") || secrets.containsKey("MYSQL_ROOT_PASSWORD");
            System.out.println("ADMIN_CREDENTIAL_CONFIGURED=" + adminConfigured);
        } catch (SQLException failure) {
            System.err.printf("Local setup SQL error: state=%s code=%d%n", failure.getSQLState(), failure.getErrorCode());
            System.exit(1);
        } catch (Exception failure) {
            System.err.println("Local setup stopped: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static void verifyDemo(String appPassword) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "supportflow_app", appPassword)) {
            String query = "SELECT s.id, s.status, s.reviewer, s.reason, e.status, e.amount, e.currency, "
                    + "e.attempt_count, e.reference, r.reference, r.amount, r.currency, o.status, o.total_amount "
                    + "FROM support_after_sale s "
                    + "JOIN support_order o ON o.id=s.order_id "
                    + "JOIN support_after_sale_execution e ON e.request_id=s.id "
                    + "JOIN support_simulated_refund r ON r.request_id=s.id "
                    + "WHERE s.tenant_id=100 AND s.buyer_id=200 AND s.order_no='SFDEMO2026102107'";
            String id;
            String reference;
            try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(query)) {
                if (!rows.next()) throw new IllegalStateException("Completed demonstration receipt not found");
                id = rows.getString(1);
                reference = rows.getString(9);
                if (!"APPROVED".equals(rows.getString(2)) || !"support-reviewer".equals(rows.getString(3))
                        || !rows.getString(4).startsWith("LOCAL_ACCEPTANCE_20261021:")
                        || !"SUCCEEDED".equals(rows.getString(5)) || rows.getInt(8) != 1
                        || !reference.startsWith("SIM-") || !reference.equals(rows.getString(10))
                        || !"CNY".equals(rows.getString(7)) || !"CNY".equals(rows.getString(12))
                        || !"PAID".equals(rows.getString(13))
                        || rows.getBigDecimal(6).compareTo(new java.math.BigDecimal("26.80")) != 0
                        || rows.getBigDecimal(11).compareTo(rows.getBigDecimal(6)) != 0
                        || rows.getBigDecimal(14).compareTo(rows.getBigDecimal(6)) != 0
                        || rows.next()) throw new IllegalStateException("Demonstration receipt or original order verification failed");
            }
            java.util.Set<String> actions = new java.util.HashSet<>();
            try (var sql = connection.prepareStatement("SELECT action, COUNT(*) FROM support_after_sale_audit WHERE request_id=? GROUP BY action")) {
                sql.setString(1, id);
                try (ResultSet rows = sql.executeQuery()) {
                    while (rows.next()) {
                        if (rows.getLong(2) != 1) throw new IllegalStateException("Duplicate demonstration audit action");
                        actions.add(rows.getString(1));
                    }
                }
            }
            if (!actions.equals(java.util.Set.of("SUBMITTED", "APPROVED", "EXECUTION_QUEUED", "EXECUTION_STARTED", "EXECUTION_SUCCEEDED"))) {
                throw new IllegalStateException("Incomplete demonstration audit chain");
            }
            System.out.printf("DATABASE_RECEIPT=PASS CASE=%s REFERENCE=%s AMOUNT=26.80 CNY ATTEMPTS=1%n", id, reference);
            System.out.println("REFUND_RECEIPTS=1 EXECUTION_TASKS=1 AUDIT_ACTIONS=5 EACH_ACTION_ONCE=true ORDER_UNCHANGED=true");
        }
    }

    private static LocalCustomerAccountsProperties accounts() {
        StandardEnvironment environment = new StandardEnvironment();
        // Load source configuration rather than potentially stale target/classes copies.
        environment.getPropertySources().addFirst(new MapPropertySource("local-setup",
                Map.of("spring.config.location", "file:./src/main/resources/")));
        ConfigDataEnvironmentPostProcessor.applyTo(environment, null, null, "local");
        return Binder.get(environment).bind("supportflow.security.local", LocalCustomerAccountsProperties.class)
                .orElseThrow(() -> new IllegalStateException("Local accounts are missing"));
    }

    private static void configureReviewer() throws Exception {
        String configPath = ".local/reviewer-security.yml";
        String loginPath = ".local/reviewer-login.txt";
        var existing = accounts().accounts();
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        if (Files.exists(ROOT.resolve(configPath)) || Files.exists(ROOT.resolve(loginPath))) {
            if (!Files.isRegularFile(ROOT.resolve(configPath)) || !Files.isRegularFile(ROOT.resolve(loginPath))) {
                throw new IllegalStateException("Incomplete existing reviewer setup; existing files are preserved");
            }
            Properties login = new Properties();
            login.load(new StringReader(Files.readString(ROOT.resolve(loginPath), StandardCharsets.UTF_8)));
            var reviewer = existing.stream().filter(account -> account.username().equals("support-reviewer"))
                    .findFirst().orElseThrow();
            if (!"support-reviewer".equals(login.getProperty("username"))
                    || reviewer.identity().buyerId() != 999
                    || !reviewer.permissions().contains(dev.supportflow.common.security.model.SecurityPermission.AFTER_SALE_REVIEW)
                    || !encoder.matches(login.getProperty("password", ""), reviewer.passwordHash().substring(8))) {
                throw new IllegalStateException("Existing reviewer credentials differ; preserved without overwrite");
            }
            System.out.println("REVIEWER_SETUP=ALREADY_CONFIGURED");
            return;
        }
        if (existing.stream().anyMatch(account -> account.username().equals("support-reviewer")
                || account.identity().buyerId() == 999)) {
            throw new IllegalStateException("Reviewer identity is already used; existing accounts are preserved");
        }
        for (String relative : new String[] {configPath, loginPath}) {
            Process check = new ProcessBuilder("git", "check-ignore", "-q", "--", relative)
                    .directory(ROOT.toFile()).redirectErrorStream(true).start();
            check.getInputStream().readAllBytes();
            if (check.waitFor() != 0) throw new IllegalStateException("Reviewer credentials must be git-ignored");
        }
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String hash = "{bcrypt}" + encoder.encode(password);
        StringBuilder config = new StringBuilder("# Local-only account snapshot; retain existing buyer accounts.\nsupportflow:\n  security:\n    local:\n      accounts:\n");
        for (var account : existing) {
            config.append("        - username: ").append(quoted(account.username())).append('\n')
                    .append("          password-hash: ").append(quoted(account.passwordHash())).append('\n')
                    .append("          identity:\n            buyer-id: ").append(account.identity().buyerId()).append('\n')
                    .append("          permissions:\n");
            for (var permission : account.permissions()) config.append("            - ").append(permission.name()).append('\n');
        }
        config.append("        - username: support-reviewer\n          password-hash: ").append(quoted(hash))
                .append("\n          identity:\n            buyer-id: 999\n          permissions:\n            - ORDER_READ\n            - AFTER_SALE_REVIEW\n");
        StringBuilder patch = new StringBuilder("*** Begin Patch\n");
        addPrivateFile(patch, configPath, config.toString());
        addPrivateFile(patch, loginPath, "# Local reviewer login; do not commit or share.\nusername=support-reviewer\npassword=" + password + "\n");
        patch.append("*** End Patch\n");
        String patchTool = System.getenv("SUPPORTFLOW_APPLY_PATCH");
        if (patchTool == null || !Files.isRegularFile(Path.of(patchTool))) {
            throw new IllegalStateException("The apply_patch executable is required to create local configuration");
        }
        // Never log the generated patch, password, hash, or subprocess output.
        Process edit = new ProcessBuilder(patchTool, "--codex-run-as-apply-patch", patch.toString())
                .directory(ROOT.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!edit.waitFor(15, TimeUnit.SECONDS) || edit.exitValue() != 0) {
            edit.destroyForcibly();
            throw new IllegalStateException("Private configuration patch failed; inspect file existence before retrying");
        }
        var configured = accounts().accounts();
        if (configured.size() != existing.size() + 1) throw new IllegalStateException("Account preservation check failed");
        for (var account : existing) {
            if (!configured.contains(account)) throw new IllegalStateException("Existing account changed unexpectedly");
        }
        System.out.println("REVIEWER_SETUP=CONFIGURED BUYER_ID=999 PERMISSIONS=ORDER_READ,AFTER_SALE_REVIEW");
        System.out.println("EXISTING_BUYER_ACCOUNTS=PRESERVED");
        System.out.println("REVIEWER_LOGIN_FILE=" + ROOT.resolve(loginPath));
        System.out.println("REVIEWER_SECRET_CONFIG=" + ROOT.resolve(configPath));
    }

    private static String quoted(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static void addPrivateFile(StringBuilder patch, String relative, String contents) {
        patch.append("*** Add File: ").append(relative).append('\n');
        contents.lines().forEach(line -> patch.append('+').append(line).append('\n'));
    }

    private static Properties yaml(String relative) {
        Path file = ROOT.resolve(relative);
        if (!Files.isRegularFile(file)) return new Properties();
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource(file));
        Properties value = factory.getObject();
        return value == null ? new Properties() : value;
    }

    private static String credential(Properties secrets, String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) value = secrets.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required local credential is missing");
        return value;
    }
}
