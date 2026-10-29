package dev.supportflow.order.infrastructure.persistence.jdbc;

import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.domain.OrderQueryLimits;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.repository.SupportOrderRepository;
import dev.supportflow.testsupport.OrderVisibilityFixtures;
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
            OrderVisibilityFixtures.grantRead(connection, "supportflow_test", QUERY_USERNAME);

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

            // Interleaved out-of-scope rows expose filtering after LIMIT.
            // Legacy customer numbers intentionally overlap the global buyer id.
            statement.executeUpdate(
                    """
                    INSERT INTO support_order (
                        id, tenant_id, customer_id, buyer_id,
                        order_no, status, total_amount
                    ) VALUES
                    (1003, 100, 5101, 800, 'SCOPED-A', 'PAID', 10.00),
                    (1004, 100, 800, 900, 'SCOPED-OTHER-BUYER', 'PAID', 20.00),
                    (1005, 300, 5101, 800, 'SCOPED-OTHER-SHOP', 'PAID', 30.00),
                    (1006, 100, 5102, 800, 'SCOPED-B', 'SHIPPED', 40.00),
                    (1007, 100, 800, NULL, 'SCOPED-UNMAPPED', 'PAID', 50.00),
                    (1008, 100, 5103, 800, 'SCOPED-C', 'COMPLETED', 60.00)
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

    @Test
    @DisplayName("Shop and buyer filters run before the SQL limit")
    void shouldFilterShopAndBuyerBeforeApplyingLimit() {
        var rows = repository.findByShopAndBuyerId(100L, 800L, 0L, 2);

        assertThat(rows).extracting(order -> order.id())
                .containsExactly(1003L, 1006L);
        assertThat(rows).allSatisfy(order -> {
            assertThat(order.tenantId()).isEqualTo(100L);
            assertThat(order.buyerId()).isEqualTo(800L);
        });
        assertThat(repository.findByShopAndBuyerId(100L, 800L, 1003L, 1))
                .extracting(order -> order.id())
                .containsExactly(1006L);
    }

    @Test
    @DisplayName("Shop queries exclude other buyers and unmapped legacy ownership")
    void shouldExcludeOtherBuyersAndUnmappedLegacyRowsWithinShop() {
        var rows = repository.findByShopAndBuyerId(
                100L, 800L, 0L, OrderQueryLimits.MAX_FETCH_SIZE);

        assertThat(rows).extracting(order -> order.id())
                .containsExactly(1003L, 1006L, 1008L);
        assertThat(rows).extracting(order -> order.customerId())
                .containsExactly(5101L, 5102L, 5103L);
        assertThat(repository.findByShopAndBuyerId(100L, 900L, 0L, 10))
                .extracting(order -> order.id())
                .containsExactly(1004L);
        assertThat(repository.findByShopAndBuyerId(100L, 801L, 0L, 10)).isEmpty();
    }

    @Test
    @DisplayName("Shop-scoped queries do not change account-wide order access")
    void shouldKeepSameBuyerSeparatedAcrossShops() {
        assertThat(repository.findByShopAndBuyerId(300L, 800L, 0L, 10))
                .extracting(order -> order.id())
                .containsExactly(1005L);
        assertThat(repository.findByShopAndBuyerId(999L, 800L, 0L, 10)).isEmpty();
        assertThat(repository.findByBuyerId(800L, 0L, 10))
                .extracting(order -> order.id())
                .containsExactly(1003L, 1005L, 1006L, 1008L);
    }

    @Test
    @DisplayName("Real shop pages retain the lookahead row and finish without duplicates")
    void shouldBuildScopedPagesAgainstRealMySqlWithoutDroppingLookahead() {
        OrderQueryService service = new OrderQueryService(repository);
        CustomerIdentity identity = new CustomerIdentity(800L);

        var firstPage = service.listOwnedOrdersInShop(identity, 100L, 0L, 2);
        assertThat(firstPage.items()).extracting(order -> order.id())
                .containsExactly(1003L, 1006L);
        assertThat(firstPage.nextAfterId()).isEqualTo(1006L);

        var secondPage = service.listOwnedOrdersInShop(
                identity, 100L, firstPage.nextAfterId(), 2);
        assertThat(secondPage.items()).extracting(order -> order.id())
                .containsExactly(1008L);
        assertThat(secondPage.nextAfterId()).isNull();

        var emptyPage = service.listOwnedOrdersInShop(identity, 100L, 1008L, 2);
        assertThat(emptyPage.items()).isEmpty();
        assertThat(emptyPage.nextAfterId()).isNull();
    }

    @Test
    @DisplayName("Shop cursor bounds are exclusive and accept the maximum fetch size")
    void shouldHonorShopCursorAndFetchBounds() {
        assertThat(repository.findByShopAndBuyerId(
                100L, 800L, 1006L, OrderQueryLimits.MAX_FETCH_SIZE))
                .extracting(order -> order.id())
                .containsExactly(1008L);
        assertThat(repository.findByShopAndBuyerId(100L, 800L, 1008L, 1)).isEmpty();
        assertThat(repository.findByShopAndBuyerId(
                100L, 800L, Long.MAX_VALUE, OrderQueryLimits.MAX_FETCH_SIZE)).isEmpty();
    }

    @Test
    @DisplayName("Invalid shop, buyer and scoped pagination ranges are rejected")
    void shouldRejectInvalidShopBuyerAndScopedLimits() {
        assertThatThrownBy(() -> repository.findByShopAndBuyerId(0L, 800L, 0L, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByShopAndBuyerId(-1L, 800L, 0L, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByShopAndBuyerId(100L, 0L, 0L, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByShopAndBuyerId(100L, -1L, 0L, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByShopAndBuyerId(100L, 800L, -1L, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByShopAndBuyerId(100L, 800L, 0L, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByShopAndBuyerId(100L, 800L, 0L, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByShopAndBuyerId(
                100L, 800L, 0L, OrderQueryLimits.MAX_FETCH_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByShopAndBuyerId(
                100L, 800L, 0L, Integer.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The scoped query account cannot update orders")
    void shouldUseSelectOnlyReaderForShopQueries() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), QUERY_USERNAME, QUERY_PASSWORD
        ); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE support_order SET status = status WHERE id = 1003"))
                    .isInstanceOfSatisfying(SQLException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(1142));
        }
    }
}
