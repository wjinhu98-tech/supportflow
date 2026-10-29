package dev.supportflow.deployment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Explicit image import uses isolated schemas and preserves all existing business data. */
@Testcontainers
class DemoProductImageDataIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName("demo_image_test").withUsername("root").withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%").withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true").withUrlParam("sslMode", "DISABLED")
            .withUrlParam("allowPublicKeyRetrieval", "true");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> IMAGES = Map.of(
            "SF102001", "/products/demo/black-earbuds.png", "SF102002", "/products/demo/white-mouse.png",
            "SF103001", "/products/demo/white-mug.png", "SF103002", "/products/demo/cotton-bedding.png");
    private String url;
    private JdbcClient jdbc;

    @BeforeEach
    void prepareIndependentFixture() throws Exception {
        String database = "image_seed_" + UUID.randomUUID().toString().replace("-", "");
        try (var root = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = root.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs");
        }
        url = MYSQL.getJdbcUrl().replace("/demo_image_test?", "/" + database + "?");
        Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.sql("INSERT INTO support_merchant(id,name) VALUES (1,'Learning merchant')").update();
        jdbc.sql("INSERT INTO support_merchant_shop(shop_id,merchant_id,name) VALUES (100,1,'Existing base shop')").update();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            DemoShopData.seed(connection);
            connection.commit();
        }
        jdbc.sql("UPDATE support_order SET updated_at='2020-01-01 00:00:00.123' WHERE tenant_id IN (102,103)").update();
    }

    @Test
    void oldMissingAndNullImagesAreAddedOnceWithoutChangingPurchasedDataOrBusinessMetadata() throws Exception {
        jdbc.sql("UPDATE support_order SET product_items_json=JSON_SET(product_items_json,'$[0].imageUrl',NULL) WHERE order_no='SF102002'").update();
        var before = metadata();
        Map<String, String> original;
        try (var connection = connection()) { original = snapshots(connection); }
        assertThat(seed()).isEqualTo(new DemoProductImageData.Result(4, 0));
        assertThat(seed()).isEqualTo(new DemoProductImageData.Result(0, 4));
        assertThat(metadata()).isEqualTo(before);
        try (var connection = connection()) {
            snapshots(connection).forEach((number, value) -> {
                JsonNode item = JSON.readTree(value).get(0);
                assertThat(item.get("imageUrl").asString()).isEqualTo(IMAGES.get(number));
                JsonNode old = JSON.readTree(original.get(number)).get(0);
                for (String field : List.of("productName", "specification", "quantity"))
                    assertThat(item.get(field)).isEqualTo(old.get(field));
            });
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM support_merchant_staff_shop_grant").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM support_shipment_event").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void unknownEmptyCustomizedAndAlreadyImagedSnapshotsArePreservedExactly() throws Exception {
        jdbc.sql("UPDATE support_order SET product_items_json=NULL WHERE order_no='SF102001'").update();
        setSnapshot("SF102002", "[]");
        setSnapshot("SF103001", "[{\"productName\":\"Custom mug\",\"specification\":\"Red\",\"quantity\":1}]");
        jdbc.sql("UPDATE support_order SET product_items_json=JSON_SET(product_items_json,'$[0].imageUrl','/products/demo/custom-bedding.webp') WHERE order_no='SF103002'").update();
        var before = metadata();
        Map<String, String> original;
        try (var connection = connection()) { original = snapshots(connection); }
        assertThat(seed()).isEqualTo(new DemoProductImageData.Result(0, 4));
        try (var connection = connection()) { assertThat(snapshots(connection)).isEqualTo(original); }
        assertThat(metadata()).isEqualTo(before);
    }

    @Test
    void knownOrderNumbersDoNotAuthorizeChangesToDifferentNamesSpecificationsQuantitiesOrMultipleLines() throws Exception {
        JsonNode headphones = JSON.readTree(DemoProductData.snapshotFor(102, "SF102001")).get(0);
        setSnapshot("SF102001", JSON.writeValueAsString(List.of(Map.of("productName", headphones.get("productName").asString(),
                "specification", headphones.get("specification").asString(), "quantity", 2))));
        JsonNode mouse = JSON.readTree(DemoProductData.snapshotFor(102, "SF102002")).get(0);
        setSnapshot("SF102002", JSON.writeValueAsString(List.of(Map.of("productName", mouse.get("productName").asString(),
                "specification", "Customized", "quantity", 1))));
        JsonNode mug = JSON.readTree(DemoProductData.snapshotFor(103, "SF103001")).get(0);
        setSnapshot("SF103001", JSON.writeValueAsString(List.of(Map.of("productName", "Customized",
                "specification", mug.get("specification").asString(), "quantity", 1))));
        String bedding = DemoProductData.snapshotFor(103, "SF103002");
        setSnapshot("SF103002", "[" + bedding.substring(1, bedding.length() - 1) + "," + bedding.substring(1, bedding.length() - 1) + "]");
        var before = metadata();
        Map<String, String> original;
        try (var connection = connection()) { original = snapshots(connection); }
        assertThat(seed()).isEqualTo(new DemoProductImageData.Result(0, 4));
        try (var connection = connection()) { assertThat(snapshots(connection)).isEqualTo(original); }
        assertThat(metadata()).isEqualTo(before);
    }

    @Test
    void everyIdentityIsCheckedBeforeAnyImageWrite() throws Exception {
        var before = metadata();
        // Non-CNY values cannot reach the importer: the existing currency CHECK rejects them first.
        for (String mismatch : List.of(
                "UPDATE support_order SET buyer_id=201 WHERE order_no='SF103002'",
                "UPDATE support_order SET customer_id=201 WHERE order_no='SF103002'",
                "UPDATE support_order SET total_amount=90.00 WHERE order_no='SF103002'",
                "UPDATE support_order SET order_no='CUSTOM' WHERE order_no='SF103002'",
                "UPDATE support_merchant_shop SET name='Another shop' WHERE shop_id=103",
                "UPDATE support_merchant_shop SET status='DISABLED' WHERE shop_id=103",
                "UPDATE support_merchant_shop SET merchant_id=2 WHERE shop_id=103",
                "UPDATE support_merchant_shop SET status='DISABLED' WHERE shop_id=100",
                "UPDATE support_merchant SET status='DISABLED' WHERE id=1")) {
            try (var connection = connection(); var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.executeUpdate(mismatch);
                var original = snapshots(connection);
                assertThatThrownBy(() -> DemoProductImageData.seed(connection)).isInstanceOf(SQLException.class);
                assertThat(snapshots(connection)).as("Preflight must not partially image earlier orders").isEqualTo(original);
                connection.rollback();
            }
        }
        assertThat(metadata()).isEqualTo(before);
    }

    @Test
    void anExplicitCombinedImportCanFillOnlyTheKnownDemoSnapshotsAndImagesInOneTransaction() throws Exception {
        jdbc.sql("UPDATE support_order SET product_items_json=NULL WHERE tenant_id IN (102,103)").update();
        var before = metadata();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            assertThat(DemoProductData.seed(connection).snapshotsInserted()).isEqualTo(4);
            assertThat(DemoProductImageData.seed(connection).imagesInserted()).isEqualTo(4);
            connection.commit();
            snapshots(connection).forEach((number, value) ->
                    assertThat(JSON.readTree(value).get(0).get("imageUrl").asString()).isEqualTo(IMAGES.get(number)));
        }
        assertThat(metadata()).isEqualTo(before);
    }

    @Test
    void autoCommitConnectionsCannotPartiallyImportImages() throws Exception {
        var before = metadata();
        try (var connection = connection()) {
            var original = snapshots(connection);
            assertThat(connection.getAutoCommit()).isTrue();
            assertThatThrownBy(() -> DemoProductImageData.seed(connection)).isInstanceOf(IllegalArgumentException.class);
            assertThat(snapshots(connection)).isEqualTo(original);
        }
        assertThat(metadata()).isEqualTo(before);
    }

    private DemoProductImageData.Result seed() throws SQLException {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                var result = DemoProductImageData.seed(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void setSnapshot(String orderNo, String value) {
        jdbc.sql("UPDATE support_order SET product_items_json=:items WHERE order_no=:number")
                .param("items", value).param("number", orderNo).update();
    }

    private List<Map<String, Object>> metadata() {
        return jdbc.sql("SELECT id,tenant_id,customer_id,buyer_id,order_no,status,total_amount,currency,created_at,updated_at,received_at FROM support_order ORDER BY id")
                .query().listOfRows();
    }

    private static Map<String, String> snapshots(Connection connection) throws SQLException {
        var snapshots = new LinkedHashMap<String, String>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT order_no,product_items_json FROM support_order ORDER BY id")) {
            while (rows.next()) snapshots.put(rows.getString(1), rows.getString(2));
        }
        return snapshots;
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
    }
}
