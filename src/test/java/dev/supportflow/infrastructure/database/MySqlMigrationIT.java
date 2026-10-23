package dev.supportflow.infrastructure.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MySqlMigrationIT {

    @Container
    private static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.0.45")
                    .withDatabaseName("supportflow_test")
                    .withUsername("test_owner")
                    .withPassword(UUID.randomUUID().toString())
                    .withUrlParam("connectionTimeZone", "UTC")
                    .withUrlParam("forceConnectionTimeZoneToSession", "true")
                    .withUrlParam("sslMode", "DISABLED")
                    .withUrlParam("allowPublicKeyRetrieval", "true");

    @Test
    @DisplayName("V1存量订单升级后保留数据但不自动推断买家，重复迁移不执行")
    void shouldCreateOrderTableAndSkipAppliedMigrations() throws SQLException {
        MigrateResult initialMigration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("1")
                .cleanDisabled(true)
                .load()
                .migrate();

        assertThat(initialMigration.success).isTrue();
        assertThat(initialMigration.migrationsExecuted).isEqualTo(1);

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO support_order (
                        tenant_id, customer_id, order_no, status, total_amount
                    ) VALUES
                    (100, 200, 'LEGACY', 'PAID', 10.00),
                    (300, 200, 'LEGACY', 'PAID', 20.00)
                    """);
        }

        List<List<Object>> legacyOrders = readLegacyOrders();

        Flyway flyway = Flyway.configure()
                .dataSource(
                        MYSQL.getJdbcUrl(),
                        MYSQL.getUsername(),
                        MYSQL.getPassword()
                )
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .createSchemas(false)
                .load();

        MigrateResult firstMigration = flyway.migrate();

        assertThat(firstMigration.success).isTrue();
        assertThat(firstMigration.migrationsExecuted).isEqualTo(6);

        MigrateResult secondMigration = flyway.migrate();

        assertThat(secondMigration.success).isTrue();
        assertThat(secondMigration.migrationsExecuted).isZero();

        assertThat(readLegacyOrders()).containsExactlyElementsOf(legacyOrders);

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement();
             ResultSet audits = statement.executeQuery("SELECT COUNT(*) FROM support_order_receipt_audit")) {
            assertThat(audits.next()).isTrue();
            assertThat(audits.getLong(1)).as("Migration must not invent receipt confirmation events").isZero();
        }

        try (
                Connection connection = DriverManager.getConnection(
                        MYSQL.getJdbcUrl(),
                        MYSQL.getUsername(),
                        MYSQL.getPassword()
                );
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        """
                        SELECT COUNT(*) AS order_count,
                               COUNT(buyer_id) AS mapped_buyer_count,
                               COUNT(received_at) AS recorded_receipt_count
                        FROM support_order
                        """
                )
        ) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong("order_count")).isEqualTo(2);
            assertThat(resultSet.getLong("mapped_buyer_count")).isZero();
            assertThat(resultSet.getLong("recorded_receipt_count")).isZero();
            assertThat(resultSet.next()).isFalse();
        }

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ); Statement statement = connection.createStatement()) {
            try (ResultSet columns = statement.executeQuery("""
                    SELECT COLUMN_NAME
                    FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'support_order'
                      AND INDEX_NAME = 'idx_support_order_buyer_id_id'
                    ORDER BY SEQ_IN_INDEX
                    """)) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getString(1)).isEqualTo("buyer_id");
                assertThat(columns.next()).isTrue();
                assertThat(columns.getString(1)).isEqualTo("id");
                assertThat(columns.next()).isFalse();
            }
            try (ResultSet columns = statement.executeQuery("""
                    SELECT COLUMN_NAME
                    FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'support_order'
                      AND INDEX_NAME = 'idx_support_order_buyer_shop_id'
                    ORDER BY SEQ_IN_INDEX
                    """)) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getString(1)).isEqualTo("buyer_id");
                assertThat(columns.next()).isTrue();
                assertThat(columns.getString(1)).isEqualTo("tenant_id");
                assertThat(columns.next()).isTrue();
                assertThat(columns.getString(1)).isEqualTo("id");
                assertThat(columns.next()).isFalse();
            }
            try (ResultSet history = statement.executeQuery("""
                    SELECT version, success
                    FROM flyway_schema_history
                    ORDER BY installed_rank
                    """)) {
                for (String version : List.of("1", "2", "3", "4", "5", "6", "7")) {
                    assertThat(history.next()).isTrue();
                    assertThat(history.getString("version")).isEqualTo(version);
                    assertThat(history.getBoolean("success")).isTrue();
                }
                assertThat(history.next()).isFalse();
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    statement.executeUpdate("UPDATE support_order SET buyer_id = 0")
            ).isInstanceOf(SQLException.class);
        }
    }

    private static List<List<Object>> readLegacyOrders() throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT id, tenant_id, customer_id, order_no, status,
                            total_amount, currency, created_at, updated_at
                     FROM support_order
                     ORDER BY id ASC
                     """)) {
            while (resultSet.next()) {
                rows.add(List.of(
                        resultSet.getLong("id"),
                        resultSet.getLong("tenant_id"),
                        resultSet.getLong("customer_id"),
                        resultSet.getString("order_no"),
                        resultSet.getString("status"),
                        resultSet.getBigDecimal("total_amount"),
                        resultSet.getString("currency"),
                        resultSet.getObject("created_at", LocalDateTime.class),
                        resultSet.getObject("updated_at", LocalDateTime.class)
                ));
            }
        }
        return List.copyOf(rows);
    }
}
