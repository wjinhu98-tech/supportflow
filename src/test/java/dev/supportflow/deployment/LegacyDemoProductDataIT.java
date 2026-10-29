package dev.supportflow.deployment;

import dev.supportflow.order.domain.model.OrderItemSnapshot;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Legacy fixtures are isolated by exact identity; no directory cleanup or live database is involved. */
@Testcontainers
class LegacyDemoProductDataIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName("legacy_product_test").withUsername("root").withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%").withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true").withUrlParam("sslMode", "DISABLED")
            .withUrlParam("allowPublicKeyRetrieval", "true");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ExpectedProduct> PRODUCTS = List.of(
            new ExpectedProduct(100, "01", "59.90", "CREATED", "无线鼠标", "白色", "white-mouse.png"),
            new ExpectedProduct(100, "02", "199.00", "PAID", "蓝牙耳机", "黑色 标准款", "black-earbuds.png"),
            new ExpectedProduct(100, "03", "299.90", "SHIPPED", "蓝牙耳机", "黑色 降噪款", "black-earbuds.png"),
            new ExpectedProduct(100, "04", "89.00", "COMPLETED", "棉质四件套", "1.8米床", "cotton-bedding.png"),
            new ExpectedProduct(100, "05", "39.90", "CANCELLED", "陶瓷马克杯", "白色", "white-mug.png"),
            new ExpectedProduct(100, "06", "1299.00", "SHIPPED", "棉质四件套", "1.8米床 高支棉", "cotton-bedding.png"),
            new ExpectedProduct(100, "07", "26.80", "PAID", "陶瓷马克杯", "白色", "white-mug.png"),
            new ExpectedProduct(100, "08", "168.00", "COMPLETED", "棉质四件套", "1.8米床", "cotton-bedding.png"),
            new ExpectedProduct(101, "03", "399.90", "SHIPPED", "蓝牙耳机", "黑色", "black-earbuds.png"),
            new ExpectedProduct(101, "09", "78.00", "PAID", "无线鼠标", "白色", "white-mouse.png"));
    private String url;
    private JdbcClient jdbc;

    @BeforeEach
    void prepareIndependentLegacyFixtureWithoutMerchantRegistrations() throws Exception {
        String database = "legacy_products_" + UUID.randomUUID().toString().replace("-", "");
        try (var root = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = root.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs");
        }
        url = MYSQL.getJdbcUrl().replace("/legacy_product_test?", "/" + database + "?");
        Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword()));
        for (ExpectedProduct product : PRODUCTS) {
            jdbc.sql("""
                    INSERT INTO support_order(tenant_id,customer_id,buyer_id,order_no,status,total_amount,currency,created_at,updated_at)
                    VALUES (:shop,200,200,:number,:status,:amount,'CNY','2026-10-15 01:00:00','2020-01-01 00:00:00.123')
                    """).param("shop", product.shop()).param("number", product.orderNo()).param("status", product.status())
                    .param("amount", new java.math.BigDecimal(product.amount())).update();
        }
        jdbc.sql("""
                INSERT INTO support_order(tenant_id,customer_id,buyer_id,order_no,status,total_amount,currency,created_at,updated_at)
                VALUES (100,201,201,'SFDEMO2026102191','PAID',888.00,'CNY','2026-10-15 01:00:00','2020-01-01 00:00:00.123'),
                       (100,201,201,'SFDEMO2026102192','SHIPPED',666.00,'CNY','2026-10-15 01:00:00','2020-01-01 00:00:00.123'),
                       (100,200,200,'LAB-000001','SHIPPED',199.90,'CNY','2026-10-15 01:00:00','2020-01-01 00:00:00.123'),
                       (100,200,200,'OTHER-ORDER','PAID',59.90,'CNY','2026-10-15 01:00:00','2020-01-01 00:00:00.123'),
                       (102,200,200,'SFDEMO2026102101','PAID',59.90,'CNY','2026-10-15 01:00:00','2020-01-01 00:00:00.123')
                """).update();
        jdbc.sql("""
                INSERT INTO support_shipment_event(order_id,occurred_at,description)
                SELECT id,'2026-10-16 01:00:00','Existing learning shipment'
                FROM support_order WHERE tenant_id=100 AND order_no='SFDEMO2026102103'
                """).update();
    }

    @Test
    void exactlyTenLegacyOrdersReceiveChosenProductsAndImagesWithMetadataAndSentinelsPreservedOnRerun() throws Exception {
        jdbc.sql("""
                UPDATE support_order SET status='COMPLETED',received_at='2026-10-16 01:00:00'
                WHERE tenant_id=100 AND order_no='SFDEMO2026102107'
                """).update();
        var before = metadata();
        var shipments = jdbc.sql("SELECT * FROM support_shipment_event ORDER BY id").query().listOfRows();
        assertThat(seed()).isEqualTo(new LegacyDemoProductData.Result(10, 0));
        assertThat(seed()).isEqualTo(new LegacyDemoProductData.Result(0, 10));
        assertThat(metadata()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT * FROM support_shipment_event ORDER BY id").query().listOfRows()).isEqualTo(shipments);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM support_merchant_shop").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM support_merchant_staff_shop_grant").query(Long.class).single()).isZero();
        try (var connection = connection()) {
            var snapshots = snapshots(connection);
            for (ExpectedProduct product : PRODUCTS) {
                var items = JSON.readValue(snapshots.get(product.shop() + "/" + product.orderNo()), OrderItemSnapshot[].class);
                assertThat(items).containsExactly(new OrderItemSnapshot(product.name(), product.specification(), 1,
                        "/products/demo/" + product.image()));
            }
            for (String sentinel : List.of("100/SFDEMO2026102191", "100/SFDEMO2026102192", "100/LAB-000001",
                    "100/OTHER-ORDER", "102/SFDEMO2026102101")) assertThat(snapshots.get(sentinel)).isNull();
        }
    }

    @Test
    void allNonNullSnapshotsIncludingCustomProductsEmptyArraysAndMissingImagesArePreservedExactly() throws Exception {
        setSnapshot(100, "SFDEMO2026102101", "[{\"productName\":\"Customized\",\"specification\":\"Red\",\"quantity\":2,\"imageUrl\":\"/products/demo/custom.webp\"}]");
        setSnapshot(101, "SFDEMO2026102103", "[]");
        setSnapshot(100, "SFDEMO2026102103", "[{\"productName\":\"Existing product without image\",\"quantity\":1}]");
        var before = metadata();
        try (var connection = connection()) {
            var original = snapshots(connection);
            assertThat(seed()).isEqualTo(new LegacyDemoProductData.Result(7, 3));
            var updated = snapshots(connection);
            for (String identity : List.of("100/SFDEMO2026102101", "101/SFDEMO2026102103", "100/SFDEMO2026102103"))
                assertThat(updated.get(identity)).isEqualTo(original.get(identity));
        }
        assertThat(metadata()).isEqualTo(before);
    }

    @Test
    void theLastCandidatesIdentityIsVerifiedBeforeAnyEarlierOrderIsUpdated() throws Exception {
        var before = metadata();
        for (String mismatch : List.of(
                "UPDATE support_order SET buyer_id=201 WHERE tenant_id=101 AND order_no='SFDEMO2026102109'",
                "UPDATE support_order SET customer_id=201 WHERE tenant_id=101 AND order_no='SFDEMO2026102109'",
                "UPDATE support_order SET total_amount=79.00 WHERE tenant_id=101 AND order_no='SFDEMO2026102109'",
                "UPDATE support_order SET created_at='2026-10-15 01:00:00.001' WHERE tenant_id=101 AND order_no='SFDEMO2026102109'",
                "UPDATE support_order SET order_no='CUSTOM' WHERE tenant_id=101 AND order_no='SFDEMO2026102109'",
                "UPDATE support_order SET tenant_id=102 WHERE tenant_id=101 AND order_no='SFDEMO2026102109'")) {
            try (var connection = connection(); var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.executeUpdate(mismatch);
                var original = snapshots(connection);
                assertThatThrownBy(() -> LegacyDemoProductData.seed(connection)).isInstanceOf(SQLException.class);
                assertThat(snapshots(connection)).as("A failed preflight must not partially fill earlier products").isEqualTo(original);
                connection.rollback();
            }
        }
        assertThat(metadata()).isEqualTo(before);
    }

    @Test
    void autoCommitConnectionsAreRejectedBeforeAnyWrite() throws Exception {
        var before = metadata();
        try (var connection = connection()) {
            var original = snapshots(connection);
            assertThat(connection.getAutoCommit()).isTrue();
            assertThatThrownBy(() -> LegacyDemoProductData.seed(connection)).isInstanceOf(IllegalArgumentException.class);
            assertThat(snapshots(connection)).isEqualTo(original);
        }
        assertThat(metadata()).isEqualTo(before);
    }

    @Test
    void callersRetainTransactionOwnershipAndCanRollBackAllTenProductSnapshots() throws Exception {
        var before = metadata();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            var original = snapshots(connection);
            assertThat(LegacyDemoProductData.seed(connection)).isEqualTo(new LegacyDemoProductData.Result(10, 0));
            assertThat(connection.getAutoCommit()).isFalse();
            connection.rollback();
            assertThat(snapshots(connection)).isEqualTo(original);
        }
        assertThat(metadata()).isEqualTo(before);
    }

    private LegacyDemoProductData.Result seed() throws SQLException {
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try {
                var result = LegacyDemoProductData.seed(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void setSnapshot(long shop, String orderNo, String value) {
        jdbc.sql("UPDATE support_order SET product_items_json=:items WHERE tenant_id=:shop AND order_no=:number")
                .param("items", value).param("shop", shop).param("number", orderNo).update();
    }

    private List<Map<String, Object>> metadata() {
        return jdbc.sql("SELECT id,tenant_id,customer_id,buyer_id,order_no,status,total_amount,currency,created_at,updated_at,received_at FROM support_order ORDER BY id")
                .query().listOfRows();
    }

    private static Map<String, String> snapshots(Connection connection) throws SQLException {
        var snapshots = new LinkedHashMap<String, String>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT tenant_id,order_no,product_items_json FROM support_order ORDER BY id")) {
            while (rows.next()) snapshots.put(rows.getLong(1) + "/" + rows.getString(2), rows.getString(3));
        }
        return snapshots;
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
    }

    private record ExpectedProduct(long shop, String suffix, String amount, String status,
                                   String name, String specification, String image) {
        String orderNo() { return "SFDEMO20261021" + suffix; }
    }
}
