package dev.supportflow.infrastructure.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import java.sql.*;
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
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("1")
                .cleanDisabled(true)
                .load()
                .migrate();

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
        assertThat(firstMigration.migrationsExecuted).isEqualTo(1);

        MigrateResult secondMigration = flyway.migrate();

        assertThat(secondMigration.success).isTrue();
        assertThat(secondMigration.migrationsExecuted).isZero();

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
                               COUNT(buyer_id) AS mapped_buyer_count
                        FROM support_order
                        """
                )
        ) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong("order_count")).isEqualTo(2);
            assertThat(resultSet.getLong("mapped_buyer_count")).isZero();
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
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    statement.executeUpdate("UPDATE support_order SET buyer_id = 0")
            ).isInstanceOf(SQLException.class);
        }
    }
}
