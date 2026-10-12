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
    @DisplayName("能够通过迁移创建订单表，重复迁移不重新执行脚本")
    void shouldCreateOrderTableAndSkipAppliedMigrations() throws SQLException {
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
                        "SELECT COUNT(*) AS order_count FROM support_order"
                )
        ) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong("order_count")).isZero();
            assertThat(resultSet.next()).isFalse();
        }
    }
}