package dev.supportflow.infrastructure.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MySqlConnectivityIT {

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
    @DisplayName("能够连接独立的 MySQL 测试库并执行查询")
    void shouldConnectToIsolatedMySqlDatabase() throws SQLException {
        try (
                Connection connection = DriverManager.getConnection(
                        MYSQL.getJdbcUrl(),
                        MYSQL.getUsername(),
                        MYSQL.getPassword()
                );
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT DATABASE() AS current_database, 1 AS probe"
                )
        ) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("current_database"))
                    .isEqualTo(MYSQL.getDatabaseName());
            assertThat(resultSet.getInt("probe")).isEqualTo(1);
            assertThat(resultSet.next()).isFalse();
        }
    }
}