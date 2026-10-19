package dev.supportflow.order.infrastructure.persistence.jdbc;

import dev.supportflow.order.domain.OrderQueryLimits;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.repository.SupportOrderRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcSupportOrderRepositoryIT {

    private static final String QUERY_USERNAME = "order_reader";

    private static final String QUERY_PASSWORD =
            UUID.randomUUID().toString();

    @Container
    private static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.0.45")
                    .withDatabaseName("supportflow_test")
                    .withUsername("root")
                    .withPassword(UUID.randomUUID().toString())
                    .withEnv("MYSQL_ROOT_HOST", "%")
                    .withUrlParam("connectionTimeZone", "UTC")
                    .withUrlParam("forceConnectionTimeZoneToSession", "true")
                    .withUrlParam("sslMode", "DISABLED")
                    .withUrlParam("allowPublicKeyRetrieval", "true");

    private static SupportOrderRepository repository;

    @BeforeAll
    static void setUpDatabase() throws SQLException {
        migrateSchema();
        prepareFixtureAndQueryAccount();

        DriverManagerDataSource queryDataSource =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(),
                        QUERY_USERNAME,
                        QUERY_PASSWORD
                );

        repository = new JdbcSupportOrderRepository(
                JdbcClient.create(queryDataSource)
        );
    }

    private static void migrateSchema() {
        Flyway.configure()
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
                .load()
                .migrate();
    }

    private static void prepareFixtureAndQueryAccount()
            throws SQLException {
        try (
                Connection connection = DriverManager.getConnection(
                        MYSQL.getJdbcUrl(),
                        MYSQL.getUsername(),
                        MYSQL.getPassword()
                );
                PreparedStatement createUser = connection.prepareStatement(
                        """
                        CREATE USER 'order_reader'@'%'
                        IDENTIFIED BY ?
                        """
                );
                Statement statement = connection.createStatement()
        ) {
            createUser.setString(1, QUERY_PASSWORD);
            createUser.execute();

            statement.execute(
                    """
                    GRANT SELECT
                    ON `supportflow_test`.`support_order`
                    TO 'order_reader'@'%'
                    """
            );

            statement.executeUpdate(
                    """
                    INSERT INTO support_order (
                        id,
                        tenant_id,
                        customer_id,
                        buyer_id,
                        order_no,
                        status,
                        total_amount,
                        currency,
                        created_at,
                        updated_at
                    ) VALUES (
                        1001,
                        100,
                        200,
                        200,
                        'SF10001',
                        'SHIPPED',
                        199.90,
                        'CNY',
                        '2026-10-01 02:03:04.123',
                        '2026-10-02 05:06:07.456'
                    )
                    """
            );

            statement.executeUpdate(
                    """
                    INSERT INTO support_order (
                        id,
                        tenant_id,
                        customer_id,
                        buyer_id,
                        order_no,
                        status,
                        total_amount,
                        currency,
                        created_at,
                        updated_at
                    ) VALUES (
                        1002,
                        300,
                        777,
                        200,
                        'SF10001',
                        'PAID',
                        899.00,
                        'CNY',
                        '2026-10-03 01:02:03.123',
                        '2026-10-04 04:05:06.456'
                    )
                    """
            );
        }
    }

    @Test
    @DisplayName("能够查询当前租户和客户的订单并正确映射全部字段")
    void shouldFindOwnedOrderAndMapAllFields() {
        var result = repository.findByOrderNo(
                100L,
                200L,
                "SF10001"
        );

        assertThat(result).hasValueSatisfying(order -> {
            assertThat(order.id()).isEqualTo(1001L);
            assertThat(order.tenantId()).isEqualTo(100L);
            assertThat(order.customerId()).isEqualTo(200L);
            assertThat(order.buyerId()).isEqualTo(200L);
            assertThat(order.orderNo()).isEqualTo("SF10001");
            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
            assertThat(order.totalAmount())
                    .isEqualByComparingTo(new BigDecimal("199.90"));
            assertThat(order.currency()).isEqualTo("CNY");
            assertThat(order.createdAt())
                    .isEqualTo(Instant.parse("2026-10-01T02:03:04.123Z"));
            assertThat(order.updatedAt())
                    .isEqualTo(Instant.parse("2026-10-02T05:06:07.456Z"));
        });
    }

    @Test
    @DisplayName("订单号不存在时返回空结果")
    void shouldReturnEmptyWhenOrderDoesNotExist() {
        var result = repository.findByOrderNo(
                100L,
                200L,
                "SF_NOT_EXISTS"
        );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("租户不匹配时不能查询到订单")
    void shouldReturnEmptyWhenTenantDoesNotMatch() {
        var result = repository.findByOrderNo(
                999L,
                200L,
                "SF10001"
        );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("平台买家不匹配时不能查询到订单")
    void shouldReturnEmptyWhenCustomerDoesNotMatch() {
        var result = repository.findByOrderNo(
                100L,
                999L,
                "SF10001"
        );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("不同租户使用相同订单号时分别返回所属租户的订单")
    void shouldDistinguishSameOrderNumberAcrossTenants() {
        var firstTenantResult = repository.findByOrderNo(
                100L,
                200L,
                "SF10001"
        );

        var secondTenantResult = repository.findByOrderNo(
                300L,
                200L,
                "SF10001"
        );

        assertThat(firstTenantResult).hasValueSatisfying(order -> {
            assertThat(order.id()).isEqualTo(1001L);
            assertThat(order.tenantId()).isEqualTo(100L);
            assertThat(order.customerId()).isEqualTo(200L);
            assertThat(order.orderNo()).isEqualTo("SF10001");
            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
            assertThat(order.totalAmount())
                    .isEqualByComparingTo(new BigDecimal("199.90"));
        });

        assertThat(secondTenantResult).hasValueSatisfying(order -> {
            assertThat(order.id()).isEqualTo(1002L);
            assertThat(order.tenantId()).isEqualTo(300L);
            assertThat(order.customerId()).isEqualTo(777L);
            assertThat(order.buyerId()).isEqualTo(200L);
            assertThat(order.orderNo()).isEqualTo("SF10001");
            assertThat(order.status()).isEqualTo(OrderStatus.PAID);
            assertThat(order.totalAmount())
                    .isEqualByComparingTo(new BigDecimal("899.00"));
        });
    }


    @Test
    @DisplayName("订单号为 null 时拒绝查询")
    void shouldRejectNullOrderNumber() {
        assertThatThrownBy(
                () -> repository.findByOrderNo(100L, 200L, null)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("orderNo must not be null or blank");
    }

    @Test
    @DisplayName("订单号为空字符串时拒绝查询")
    void shouldRejectEmptyOrderNumber() {
        assertThatThrownBy(
                () -> repository.findByOrderNo(100L, 200L, "")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("orderNo must not be null or blank");
    }

    @Test
    @DisplayName("订单号仅包含空白字符时拒绝查询")
    void shouldRejectWhitespaceOnlyOrderNumber() {
        assertThatThrownBy(
                () -> repository.findByOrderNo(100L, 200L, " \t\r\n")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("orderNo must not be null or blank");
    }


    @Test
    @DisplayName("按平台买家查询跨商铺列表，支持游标与数量限制")
    void shouldListAcrossShopsWithBoundedCursor() {
        assertThat(repository.findByBuyerId(200L, 0L, OrderQueryLimits.MAX_FETCH_SIZE))
                .extracting(order -> order.id())
                .containsExactly(1001L, 1002L);
        assertThat(repository.findByBuyerId(200L, 0L, 1))
                .extracting(order -> order.id())
                .containsExactly(1001L);
        assertThat(repository.findByBuyerId(200L, 1001L, 10))
                .extracting(order -> order.id())
                .containsExactly(1002L);
        assertThat(repository.findByBuyerId(999L, 0L, 10)).isEmpty();
        assertThat(repository.findByBuyerId(200L, Long.MAX_VALUE, 10)).isEmpty();
    }

    @Test
    @DisplayName("仓储拒绝无效身份和超出上限的查询数量")
    void shouldRejectInvalidBuyerAndLimits() {
        assertThatThrownBy(() -> repository.findByBuyerId(200L, 0L, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByBuyerId(0L, 0L, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByBuyerId(200L, -1L, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByBuyerId(200L, 0L, OrderQueryLimits.MAX_FETCH_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByOrderNo(0L, 200L, "SF10001"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
