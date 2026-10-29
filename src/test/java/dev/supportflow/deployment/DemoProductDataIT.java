package dev.supportflow.deployment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Explicit snapshot import only, verified against fresh isolated schemas without removing data. */
@Testcontainers
class DemoProductDataIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName("demo_product_test").withUsername("root").withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%").withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true").withUrlParam("sslMode", "DISABLED")
            .withUrlParam("allowPublicKeyRetrieval", "true");
    private String url;
    private JdbcClient jdbc;

    @BeforeEach
    void prepareIndependentFixture() throws Exception {
        String database = "product_seed_" + UUID.randomUUID().toString().replace("-", "");
        try (var root = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = root.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs");
        }
        url = MYSQL.getJdbcUrl().replace("/demo_product_test?", "/" + database + "?");
        Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.sql("INSERT INTO support_merchant(id,name) VALUES (1,'学习商家')").update();
        jdbc.sql("INSERT INTO support_merchant_shop(shop_id,merchant_id,name) VALUES (100,1,'已有店铺')").update();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            DemoShopData.seed(connection);
            connection.commit();
        }
    }

    @Test
    void explicitlyBackfillsOnlyKnownLearningOrdersAndPreservesAllBusinessMetadataOnRerun() throws Exception {
        makeSnapshotsHistoricallyUnknown();
        List<Map<String, Object>> before = metadata();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            var first = DemoProductData.seed(connection);
            connection.commit();
            assertThat(first.snapshotsInserted()).isEqualTo(4);
            assertThat(first.snapshotsPreserved()).isZero();
            var second = DemoProductData.seed(connection);
            connection.commit();
            assertThat(second.snapshotsInserted()).isZero();
            assertThat(second.snapshotsPreserved()).isEqualTo(4);
        }
        assertThat(metadata()).isEqualTo(before);
        assertThat(productName(102, "SF102001")).isEqualTo("蓝牙耳机");
        assertThat(productName(102, "SF102002")).isEqualTo("无线鼠标");
        assertThat(productName(103, "SF103001")).isEqualTo("陶瓷马克杯");
        assertThat(productName(103, "SF103002")).isEqualTo("棉质四件套");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM support_merchant_staff_shop_grant").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM support_shipment_event").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void preservesEveryNonNullSnapshotIncludingCustomizedAndExplicitlyEmptyValues() throws Exception {
        makeSnapshotsHistoricallyUnknown();
        String customized = """
                [{"productName":"自定义耳机","specification":"金色","quantity":2}]
                """.strip();
        jdbc.sql("UPDATE support_order SET product_items_json=:items WHERE tenant_id=102 AND order_no='SF102001'")
                .param("items", customized).update();
        jdbc.sql("UPDATE support_order SET product_items_json=JSON_ARRAY() WHERE tenant_id=103 AND order_no='SF103002'").update();
        var before = metadata();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            var result = DemoProductData.seed(connection);
            connection.commit();
            assertThat(result.snapshotsInserted()).isEqualTo(2);
            assertThat(result.snapshotsPreserved()).isEqualTo(2);
        }
        assertThat(productName(102, "SF102001")).isEqualTo("自定义耳机");
        assertThat(new ObjectMapper().readTree(snapshot(103, "SF103002")).isEmpty()).isTrue();
        assertThat(metadata()).isEqualTo(before);
    }

    @Test
    void verifiesAllCandidatesBeforeWritingAndRejectsAnotherBuyersCollidingOrder() throws Exception {
        makeSnapshotsHistoricallyUnknown();
        jdbc.sql("UPDATE support_order SET buyer_id=201 WHERE tenant_id=103 AND order_no='SF103002'").update();
        var before = metadata();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            assertThatThrownBy(() -> DemoProductData.seed(connection)).isInstanceOf(SQLException.class);
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM support_order WHERE product_items_json IS NOT NULL");
                 var row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1)).as("Preflight must not partially backfill earlier candidates").isZero();
            }
            connection.rollback();
        }
        assertThat(metadata()).isEqualTo(before);
    }

    @Test
    void newDemoOrdersHaveSnapshotsButShopRerunsDoNotInferHistoricalProducts() throws Exception {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM support_order WHERE product_items_json IS NOT NULL").query(Long.class).single()).isEqualTo(4);
        jdbc.sql("UPDATE support_order SET product_items_json=NULL WHERE tenant_id=102 AND order_no='SF102001'").update();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            var shops = DemoShopData.seed(connection);
            connection.commit();
            assertThat(shops.ordersInserted()).isZero();
            assertThat(snapshot(102, "SF102001")).isNull();
            var products = DemoProductData.seed(connection);
            connection.commit();
            assertThat(products.snapshotsInserted()).isEqualTo(1);
            assertThat(products.snapshotsPreserved()).isEqualTo(3);
        }
        assertThat(productName(102, "SF102001")).isEqualTo("蓝牙耳机");
    }

    @Test
    void autoCommitConnectionsCannotAccidentallyPartiallyImportFixtures() throws Exception {
        makeSnapshotsHistoricallyUnknown();
        try (Connection connection = connection()) {
            assertThat(connection.getAutoCommit()).isTrue();
            assertThatThrownBy(() -> DemoProductData.seed(connection)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM support_order WHERE product_items_json IS NOT NULL").query(Long.class).single()).isZero();
    }

    private void makeSnapshotsHistoricallyUnknown() {
        jdbc.sql("UPDATE support_order SET product_items_json=NULL WHERE tenant_id IN (102,103)").update();
    }

    private List<Map<String, Object>> metadata() {
        return jdbc.sql("SELECT id,tenant_id,customer_id,buyer_id,order_no,status,total_amount,currency,created_at,updated_at,received_at FROM support_order ORDER BY id")
                .query().listOfRows();
    }

    private String productName(long shop, String order) {
        return new ObjectMapper().readTree(snapshot(shop, order)).get(0).path("productName").asString();
    }

    private String snapshot(long shop, String order) {
        return jdbc.sql("SELECT product_items_json FROM support_order WHERE tenant_id=:shop AND order_no=:number")
                .param("shop", shop).param("number", order).query((row, index) -> new Snapshot(row.getString(1))).single().value();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
    }

    private record Snapshot(String value) { }
}
