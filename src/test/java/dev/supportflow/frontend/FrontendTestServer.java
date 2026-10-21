package dev.supportflow.frontend;

import dev.supportflow.SupportFlowApplication;
import org.flywaydb.core.Flyway;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only real HTTP backend for browser integration tests.
 *
 * <p>It owns a disposable MySQL container and deliberately replaces Spring's
 * configuration locations. No application-local.yml, .local secrets, business
 * database, or external model provider is used. Public demo credentials are
 * intentionally usable only while this loopback-bound process is running.</p>
 */
public final class FrontendTestServer {

    private static final String PUBLIC_TEST_PASSWORD = "Frontend-Test-Only!";
    private static final String DATABASE_NAME = "supportflow_frontend_test";

    private FrontendTestServer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("This isolated test launcher accepts no overrides");
        }

        MySQLContainer mysql = new MySQLContainer("mysql:8.0.45")
                .withDatabaseName(DATABASE_NAME)
                .withUsername("root")
                .withPassword(UUID.randomUUID().toString())
                .withEnv("MYSQL_ROOT_HOST", "%")
                .withUrlParam("connectionTimeZone", "UTC")
                .withUrlParam("forceConnectionTimeZoneToSession", "true")
                .withUrlParam("sslMode", "DISABLED")
                .withUrlParam("allowPublicKeyRetrieval", "true");
        AtomicReference<ConfigurableApplicationContext> application = new AtomicReference<>();
        AtomicBoolean stopped = new AtomicBoolean();
        CountDownLatch shutdown = new CountDownLatch(1);
        Runnable close = () -> {
            if (stopped.compareAndSet(false, true)) {
                try {
                    ConfigurableApplicationContext context = application.get();
                    if (context != null) {
                        context.close();
                    }
                } finally {
                    try {
                        mysql.stop();
                    } finally {
                        shutdown.countDown();
                    }
                }
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(close, "frontend-test-cleanup"));

        try {
            mysql.start();
            String readerPassword = UUID.randomUUID().toString();
            prepareDatabase(mysql, readerPassword);

            List<String> settings = new ArrayList<>(List.of(
                    "--spring.config.location=classpath:/application-day4-test.yml",
                    "--spring.config.additional-location=",
                    "--spring.config.import=",
                    "--spring.profiles.active=local",
                    "--spring.profiles.include=",
                    "--spring.main.banner-mode=off",
                    "--spring.sql.init.mode=never",
                    "--spring.flyway.enabled=false",
                    "--server.address=127.0.0.1",
                    "--server.port=18081",
                    "--spring.datasource.url=" + mysql.getJdbcUrl(),
                    "--spring.datasource.username=frontend_reader",
                    "--spring.datasource.password=" + readerPassword,
                    "--supportflow.ai.deepseek.enabled=false",
                    "--supportflow.after-sale.execution.enabled=true",
                    "--supportflow.ai.deepseek.api-key=",
                    "--logging.level.root=WARN"
            ));
            String passwordHash = PasswordEncoderFactories.createDelegatingPasswordEncoder()
                    .encode(PUBLIC_TEST_PASSWORD);
            addAccount(settings, 0, "frontend-buyer", 200L, passwordHash);
            addAccount(settings, 1, "frontend-other", 201L, passwordHash);
            addAccount(settings, 2, "frontend-reviewer", 999L, passwordHash,
                    "ORDER_READ,AFTER_SALE_REVIEW");

            application.set(new SpringApplicationBuilder(SupportFlowApplication.class)
                    .registerShutdownHook(false)
                    .run(settings.toArray(String[]::new)));
            System.out.println("FRONTEND_TEST_BACKEND_READY http://127.0.0.1:18081");
            shutdown.await();
        } finally {
            close.run();
        }
    }

    private static void addAccount(
            List<String> settings, int index, String username, long buyerId, String passwordHash
    ) {
        addAccount(settings, index, username, buyerId, passwordHash, "ORDER_READ");
    }

    private static void addAccount(List<String> settings, int index, String username, long buyerId,
                                   String passwordHash, String permissions) {
        String prefix = "--supportflow.security.local.accounts[" + index + "].";
        settings.add(prefix + "username=" + username);
        settings.add(prefix + "password-hash=" + passwordHash);
        settings.add(prefix + "identity.buyer-id=" + buyerId);
        settings.add(prefix + "permissions=" + permissions);
    }

    private static void prepareDatabase(MySQLContainer mysql, String readerPassword) throws Exception {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .createSchemas(false)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             PreparedStatement createReader = connection.prepareStatement(
                     "CREATE USER 'frontend_reader'@'%' IDENTIFIED BY ?");
             Statement statement = connection.createStatement()) {
            createReader.setString(1, readerPassword);
            createReader.execute();
            statement.execute("GRANT SELECT ON " + DATABASE_NAME
                    + ".support_order TO 'frontend_reader'@'%'");
            statement.execute("GRANT SELECT ON " + DATABASE_NAME
                    + ".support_shipment_event TO 'frontend_reader'@'%'");
            statement.execute("GRANT SELECT, INSERT, UPDATE ON " + DATABASE_NAME
                    + ".support_after_sale TO 'frontend_reader'@'%'");
            statement.execute("GRANT SELECT, INSERT ON " + DATABASE_NAME
                    + ".support_after_sale_audit TO 'frontend_reader'@'%'");
            statement.execute("GRANT SELECT, INSERT, UPDATE ON " + DATABASE_NAME
                    + ".support_after_sale_execution TO 'frontend_reader'@'%'");
            statement.execute("GRANT SELECT, INSERT ON " + DATABASE_NAME
                    + ".support_simulated_refund TO 'frontend_reader'@'%'");
            statement.executeUpdate("""
                    INSERT INTO support_order (
                        id, tenant_id, customer_id, buyer_id, order_no, status,
                        total_amount, currency, created_at, updated_at
                    ) VALUES
                    (1001, 100, 200, 200, 'SF10001', 'SHIPPED', 199.90, 'CNY',
                     '2026-10-01 02:03:04.123', '2026-10-02 05:06:07.456'),
                    (1002, 100, 200, 200, 'SF10002', 'COMPLETED', 9.90, 'CNY',
                     '2026-10-01 02:03:04.123', '2026-10-02 05:06:07.456'),
                    (2001, 101, 700, 200, 'SF10001', 'PAID', 899.00, 'CNY',
                     '2026-10-03 01:02:03.123', '2026-10-04 04:05:06.456'),
                    (3001, 100, 201, 201, 'SF20001', 'PAID', 49.50, 'CNY',
                     '2026-10-01 02:03:04.123', '2026-10-02 05:06:07.456')
                    """);
            statement.executeUpdate("""
                    INSERT INTO support_shipment_event (order_id, occurred_at, description)
                    VALUES (1001, '2026-10-02 05:06:07.456', '学习版仓库：已出库'),
                           (1001, '2026-10-03 05:06:07.456', '学习版配送中心：运输中')
                    """);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO support_order (
                        id, tenant_id, customer_id, buyer_id, order_no, status,
                        total_amount, currency, created_at, updated_at
                    ) VALUES (?, 100, 200, 200, ?, 'PAID', 10.00, 'CNY',
                              '2026-10-01 02:03:04.123', '2026-10-02 05:06:07.456')
                    """)) {
                for (int sequence = 1; sequence <= 21; sequence++) {
                    insert.setLong(1, 1100L + sequence);
                    insert.setString(2, "PAGE-%03d".formatted(sequence));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }
}
