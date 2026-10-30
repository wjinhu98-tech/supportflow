package dev.supportflow.order.infrastructure.persistence.jdbc;

import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.domain.model.OrderItemSnapshot;
import dev.supportflow.order.domain.model.SupportOrder;
import dev.supportflow.testsupport.OrderVisibilityFixtures;
import dev.supportflow.testsupport.HumanSupportFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Actual MySQL JSON_TABLE search and constraints with only order/visibility SELECT privileges. */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class JdbcOrderProductSearchIT {
    private static final String PASSWORD = UUID.randomUUID().toString();
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName("order_product_test").withUsername("root").withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%").withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true").withUrlParam("sslMode", "DISABLED")
            .withUrlParam("allowPublicKeyRetrieval", "true");
    private static JdbcSupportOrderRepository repository;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static String persistedV16Snapshot;

    @BeforeAll
    static void prepare() throws SQLException {
        migration("15");
        try (var connection = admin(); var statement = connection.createStatement();
             var create = connection.prepareStatement("CREATE USER 'product_reader'@'%' IDENTIFIED BY ?")) {
            create.setString(1, PASSWORD);
            create.execute();
            statement.execute("GRANT SELECT ON order_product_test.support_order TO 'product_reader'@'%'");
            statement.executeUpdate("""
                    INSERT INTO support_order(tenant_id,customer_id,buyer_id,order_no,status,total_amount,currency)
                    VALUES (100,200,200,'SFLEGACY','PAID',19.90,'CNY')
                    """);
        }
        assertThat(migration("16")).isEqualTo(1);
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO support_order(tenant_id,customer_id,buyer_id,order_no,status,total_amount,currency,product_items_json)
                    VALUES (100,200,200,'SFV16','PAID',29.90,'CNY','[{"productName":"Legacy headphones","specification":"Black","quantity":2}]')
                    """);
            try (var row = statement.executeQuery("SELECT product_items_json FROM support_order WHERE order_no='SFV16'")) {
                assertThat(row.next()).isTrue();
                persistedV16Snapshot = row.getString(1);
            }
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE support_order SET product_items_json='[{"productName":"Legacy headphones","quantity":2,"imageUrl":"/products/demo/headphones.png"}]'
                    WHERE order_no='SFV16'
                    """)).isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getErrorCode()).isEqualTo(3819));
        }
        assertThat(migration(null)).isEqualTo(6);
        try (var connection = admin()) {
            OrderVisibilityFixtures.grantRead(connection, "order_product_test", "product_reader");
            HumanSupportFixtures.grantAiAdmission(connection, "order_product_test", "product_reader");
        }
        repository = new JdbcSupportOrderRepository(JdbcClient.create(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), "product_reader", PASSWORD)));
        try (var connection = admin()) {
            seed(connection, 100, 200, "SFPROD1", List.of(new OrderItemSnapshot("蓝牙耳机", "白色", 1),
                    new OrderItemSnapshot("蓝牙耳机保护套", "Type_C", 2)));
            seed(connection, 100, 200, "SFPROD2", List.of(new OrderItemSnapshot("蓝牙耳机", "黑色", 2)));
            seed(connection, 101, 200, "SFOTHER_SHOP", List.of(new OrderItemSnapshot("蓝牙耳机", "白色", 1)));
            seed(connection, 100, 201, "SFOTHER_BUYER", List.of(new OrderItemSnapshot("蓝牙耳机", "白色", 1)));
            seed(connection, 100, 200, "SFPERCENT", List.of(new OrderItemSnapshot("100%棉袜", null, 1)));
            seed(connection, 100, 200, "SFNO_PERCENT", List.of(new OrderItemSnapshot("100X棉袜", null, 1)));
            seed(connection, 100, 200, "SFUNDERSCORE", List.of(new OrderItemSnapshot("A_B转接头", "USB_C", 1)));
            seed(connection, 100, 200, "SFNO_UNDERSCORE", List.of(new OrderItemSnapshot("AXB转接头", "USBXC", 1)));
            seed(connection, 100, 200, "SFESCAPE", List.of(new OrderItemSnapshot("USB!耳机", null, 1)));
            seed(connection, 100, 200, "SFSEPARATE_LINES", List.of(new OrderItemSnapshot("耳机", "黑色", 1),
                    new OrderItemSnapshot("手机保护壳", "金色", 1)));
            seed(connection, 100, 200, "SFJADE", List.of(new OrderItemSnapshot("和田玉手镯", "青白玉", 1)));
            seed(connection, 100, 200, "SFTWENTY", Collections.nCopies(20, new OrderItemSnapshot("限定手办", "同单不同明细", 1)));
            for (int i = 0; i < 22; i++) seed(connection, 110, 200, "SFMANY" + i,
                    List.of(new OrderItemSnapshot("连帽卫衣", "尺码" + i, 1)));
            seed(connection, 100, 200, "SFSTABLE_IMAGE", List.of(new OrderItemSnapshot(
                    "Stable headphones", "White", 1, "/products/demo/black-earbuds.png")));
        }
    }

    @Test
    void repeatedSearchesStayCorrectWithUnknownHistoricalAndImagedRowsInTheSameShop() {
        assertThat(repository.findByOrderNo(100, 200, "SFLEGACY").orElseThrow().items()).isEmpty();
        assertThat(repository.findByOrderNo(100, 200, "SFV16").orElseThrow().items().getFirst().imageUrl()).isNull();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(repository.findByProductName(100, 200, "蓝牙耳机", 21)).extracting(SupportOrder::orderNo)
                    .containsExactly("SFPROD1", "SFPROD2");
            assertThat(repository.findByProductName(100, 200, "100%", 21)).extracting(SupportOrder::orderNo)
                    .containsExactly("SFPERCENT");
            assertThat(repository.findByProductName(100, 200, "Stable headphones", 21)).singleElement()
                    .satisfies(order -> assertThat(order.items().getFirst().imageUrl()).isEqualTo("/products/demo/black-earbuds.png"));
            assertThat(repository.findByProductName(100, 200, "Legacy headphones", 21)).extracting(SupportOrder::orderNo)
                    .containsExactly("SFV16");
            assertThat(repository.findByProductName(100, 200, "白色", 21)).extracting(SupportOrder::orderNo)
                    .containsExactly("SFPROD1");
        }
    }

    @Test
    void nameSearchAlwaysUsesBothBuyerAndShopAndDoesNotDuplicateOrdersWithMatchingLines() {
        assertThat(repository.findByProductName(100, 200, "蓝牙耳机", 21)).extracting(SupportOrder::orderNo)
                .containsExactly("SFPROD1", "SFPROD2");
        assertThat(repository.findByProductName(100, 201, "蓝牙耳机", 21)).extracting(SupportOrder::orderNo)
                .containsExactly("SFOTHER_BUYER");
        assertThat(repository.findByProductName(101, 200, "蓝牙耳机", 21)).extracting(SupportOrder::orderNo)
                .containsExactly("SFOTHER_SHOP");
        assertThat(repository.findByProductName(100, 999, "蓝牙耳机", 21)).isEmpty();
        assertThat(repository.findByProductName(100, 200, "限定手办", 21)).hasSize(1);
    }

    @Test
    void specificationsAndAsciiCaseAreSearchableWithoutAddingDuplicateRows() {
        assertThat(repository.findByProductName(100, 200, "白色", 21)).extracting(SupportOrder::orderNo).containsExactly("SFPROD1");
        assertThat(repository.findByProductName(100, 200, "type_c", 21)).extracting(SupportOrder::orderNo).containsExactly("SFPROD1");
        assertThat(repository.findByProductName(100, 200, "不存在商品", 21)).isEmpty();
    }

    @Test
    void colourAndSpecificationTermsMustMatchTheSamePurchasedLine() {
        assertThat(repository.findByProductName(100, 200, "黑色蓝牙耳机", 21)).extracting(SupportOrder::orderNo)
                .containsExactly("SFPROD2");
        assertThat(repository.findByProductName(100, 200, "蓝牙耳机 黑色", 21)).extracting(SupportOrder::orderNo)
                .containsExactly("SFPROD2");
        assertThat(repository.findByProductName(100, 200, "黑色耳机", 21)).extracting(SupportOrder::orderNo)
                .containsExactly("SFPROD2", "SFSEPARATE_LINES");
        assertThat(repository.findByProductName(100, 200, "耳机 金色", 21)).isEmpty();
        assertThat(repository.findByProductName(100, 200, "和田玉", 21)).extracting(SupportOrder::orderNo)
                .containsExactly("SFJADE");
    }

    @Test
    void percentUnderscoreAndEscapeCharactersRemainLiteralAndSqlTextCannotChangeTheScope() {
        assertThat(repository.findByProductName(100, 200, "100%", 21)).extracting(SupportOrder::orderNo).containsExactly("SFPERCENT");
        assertThat(repository.findByProductName(100, 200, "A_B", 21)).extracting(SupportOrder::orderNo).containsExactly("SFUNDERSCORE");
        assertThat(repository.findByProductName(100, 200, "USB!", 21)).extracting(SupportOrder::orderNo).containsExactly("SFESCAPE");
        assertThat(repository.findByProductName(100, 200, "100% 棉袜", 21)).extracting(SupportOrder::orderNo).containsExactly("SFPERCENT");
        assertThat(repository.findByProductName(100, 200, "A_B USB_C", 21)).extracting(SupportOrder::orderNo).containsExactly("SFUNDERSCORE");
        assertThat(repository.findByProductName(100, 200, "耳机' OR '1'='1", 21)).isEmpty();
    }

    @Test
    void twentyOneReadRowsProduceTwentyChoicesAndARefinementFlag() {
        var rows = repository.findByProductName(110, 200, "连帽卫衣", 21);
        assertThat(rows).hasSize(21);
        assertThat(rows).extracting(SupportOrder::id).isSorted().doesNotHaveDuplicates();
        var result = new OrderQueryService(repository).findOwnedOrdersByProduct(new CustomerIdentity(200), 110, "连帽卫衣");
        assertThat(result.items()).hasSize(20);
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void aV15OrderStaysUnknownAndTheOldReadOnlyGrantStillReadsTheNewColumn() throws SQLException {
        var legacy = repository.findByOrderNo(100, 200, "SFLEGACY").orElseThrow();
        assertThat(legacy.items()).isEmpty();
        assertThat(legacy.totalAmount()).isEqualByComparingTo("19.90");
        assertThat(legacy.status().name()).isEqualTo("PAID");
        try (var connection = admin(); var query = connection.prepareStatement(
                "SELECT product_items_json FROM support_order WHERE order_no='SFLEGACY'")) {
            try (var row = query.executeQuery()) { assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isNull(); }
        }
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "product_reader", PASSWORD);
             var change = connection.prepareStatement("UPDATE support_order SET product_items_json=JSON_ARRAY() WHERE order_no='SFLEGACY'")) {
            assertThatThrownBy(change::executeUpdate).isInstanceOfSatisfying(SQLException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(1142));
        }
    }

    @Test
    void receiptLocksAndAllOrderListQueriesKeepThePurchasedSnapshot() {
        var expected = repository.findByOrderNo(100, 200, "SFPROD1").orElseThrow().items();
        assertThat(expected).hasSize(2);
        assertThat(repository.findByShopAndBuyerId(100, 200, 0, 100).stream().filter(o -> o.orderNo().equals("SFPROD1")).findFirst().orElseThrow().items())
                .isEqualTo(expected);
        assertThat(repository.findByBuyerId(200, 0, 100).stream().filter(o -> o.orderNo().equals("SFPROD1")).findFirst().orElseThrow().items())
                .isEqualTo(expected);
        var receipt = new JdbcOrderReceiptRepository(JdbcClient.create(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())));
        assertThat(receipt.lockOwnedOrder(100, 200, "SFPROD1").orElseThrow().items()).isEqualTo(expected);
    }

    @Test
    void v17KeepsV16SnapshotsUnchangedAndTheExistingReadOnlyAccountReadsImages() throws SQLException {
        var legacy = repository.findByOrderNo(100, 200, "SFV16").orElseThrow();
        assertThat(legacy.items()).containsExactly(new OrderItemSnapshot("Legacy headphones", "Black", 2));
        assertThat(legacy.totalAmount()).isEqualByComparingTo("29.90");
        assertThat(legacy.status().name()).isEqualTo("PAID");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "product_reader", PASSWORD);
             var query = connection.prepareStatement("SELECT product_items_json FROM support_order WHERE order_no='SFV16'")) {
            try (var row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo(persistedV16Snapshot);
            }
        }
        var expected = List.of(new OrderItemSnapshot("Image headphones", "Black", 1, "/products/demo/headphones.png"));
        try (var connection = admin()) {
            seed(connection, 100, 200, "SFIMAGE", expected);
        }
        assertThat(repository.findByOrderNo(100, 200, "SFIMAGE").orElseThrow().items()).isEqualTo(expected);
        assertThat(repository.findByShopAndBuyerId(100, 200, 0, 100).stream().filter(o -> o.orderNo().equals("SFIMAGE"))
                .findFirst().orElseThrow().items()).isEqualTo(expected);
        assertThat(repository.findByBuyerId(200, 0, 100).stream().filter(o -> o.orderNo().equals("SFIMAGE"))
                .findFirst().orElseThrow().items()).isEqualTo(expected);
        assertThat(repository.findByProductName(100, 200, "Image headphones", 21)).singleElement()
                .satisfies(order -> assertThat(order.items()).isEqualTo(expected));
        var receipt = new JdbcOrderReceiptRepository(JdbcClient.create(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())));
        assertThat(receipt.lockOwnedOrder(100, 200, "SFIMAGE").orElseThrow().items()).isEqualTo(expected);
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "product_reader", PASSWORD);
             var change = connection.prepareStatement("UPDATE support_order SET product_items_json=JSON_ARRAY() WHERE order_no='SFIMAGE'")) {
            assertThatThrownBy(change::executeUpdate).isInstanceOfSatisfying(SQLException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(1142));
        }
    }

    @Test
    void theDatabaseAcceptsOptionalImagesAndOnlyTheBoundedStaticImageContract() throws SQLException {
        var validImages = List.of("null", "\"/products/demo/headphones-black.png\"", "\"/products/demo/cable_2.webp\"",
                "\"/products/demo/phone.jpg\"", "\"/products/demo/case.jpeg\"",
                "\"/products/demo/" + "a".repeat(221) + ".png\"");
        var invalidImages = List.of("123", "true", "{}", "[]", "\"\"", "\" \"",
                "\"https://example.com/a.png\"", "\"//example.com/a.png\"", "\"data:image/png;base64,private\"",
                "\"javascript:private\"", "\"/products/demo/../private.png\"", "\"/products/demo/%2e%2e/private.png\"",
                "\"/products/demo/nested/private.png\"", "\"/products/demo/a.png?q=1\"", "\"/products/demo/a.png#fragment\"",
                "\"/products/demo/A.png\"", "\"/products/demo/a.PNG\"", "\"/products/demo/a.svg\"",
                "\"/products/other/a.png\"", "\"/products/demo/a.png\\n\"", "\"/products/demo/a.png\\r\"",
                "\" /products/demo/a.png\"", "\"/products/demo/a.png \"", "\"/products/demo/" + "a".repeat(222) + ".png\"");
        try (var connection = admin(); var update = connection.prepareStatement(
                "UPDATE support_order SET product_items_json=? WHERE tenant_id=100 AND order_no='SFLEGACY'")) {
            connection.setAutoCommit(false);
            for (String image : validImages) {
                update.setString(1, "[{\"productName\":\"Headphones\",\"quantity\":1,\"imageUrl\":" + image + "}]");
                assertThat(update.executeUpdate()).isEqualTo(1);
                connection.rollback();
            }
            for (String image : invalidImages) {
                update.setString(1, "[{\"productName\":\"Headphones\",\"quantity\":1,\"imageUrl\":" + image + "}]");
                assertThatThrownBy(update::executeUpdate).as("database must enforce the same-origin image contract")
                        .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getErrorCode()).isEqualTo(3819));
                connection.rollback();
            }
        }
    }

    @Test
    void theDatabaseRejectsInvalidShapesTypesAndUnboundedArrays() throws SQLException {
        String validItem = "{\"productName\":\"耳机\",\"quantity\":1}";
        var invalid = List.of("{}", "null", "[{}]", "[null]", "[{\"productName\":123,\"quantity\":1}]",
                "[{\"productName\":\"耳机\",\"quantity\":0}]", "[{\"productName\":\"耳机\",\"quantity\":1.5}]",
                "[{\"productName\":\"耳机\",\"quantity\":10000}]", "[" + String.join(",", Collections.nCopies(21, validItem)) + "]",
                "[{\"productName\":\"" + "耳".repeat(101) + "\",\"quantity\":1}]",
                "[{\"productName\":\"耳机\",\"specification\":\"" + "白".repeat(81) + "\",\"quantity\":1}]");
        try (var connection = admin(); var update = connection.prepareStatement(
                "UPDATE support_order SET product_items_json=? WHERE tenant_id=100 AND order_no='SFLEGACY'")) {
            connection.setAutoCommit(false);
            for (String value : invalid) {
                update.setString(1, value);
                assertThatThrownBy(update::executeUpdate).as("snapshot schema must reject invalid input")
                        .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getErrorCode()).isEqualTo(3819));
                connection.rollback();
            }
        }
    }

    @Test
    void invalidScopeKeywordAndReadLimitAreRejectedBeforeExecutingSql() {
        assertThatThrownBy(() -> repository.findByProductName(0, 200, "耳机", 21)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByProductName(100, 0, "耳机", 21)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByProductName(100, 200, "耳", 21)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByProductName(100, 200, "耳机", 22)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByProductName(100, 200, "耳机", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findByProductName(100, 200, "一 二 三 四 五", 21)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void seed(Connection connection, long shop, long buyer, String number, List<OrderItemSnapshot> items) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO support_order(tenant_id,customer_id,buyer_id,order_no,status,total_amount,currency,product_items_json)
                VALUES (?,200,?,?,'SHIPPED',99.00,'CNY',?)
                """)) {
            insert.setLong(1, shop); insert.setLong(2, buyer); insert.setString(3, number);
            insert.setString(4, JSON.writeValueAsString(items)); insert.executeUpdate();
        }
    }

    private static int migration(String version) {
        var config = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false).validateOnMigrate(true);
        if (version != null) config.target(version);
        return config.load().migrate().migrationsExecuted;
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
