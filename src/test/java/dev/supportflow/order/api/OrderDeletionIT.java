package dev.supportflow.order.api;

import dev.supportflow.SupportFlowApplication;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.domain.model.OrderStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real transactions and least privileges in a disposable MySQL; no daily records or external model calls. */
@SpringBootTest(classes = SupportFlowApplication.class, properties = {
        "spring.config.location=classpath:/application-day4-test.yml", "spring.main.banner-mode=off",
        "supportflow.ai.deepseek.enabled=false"})
@ActiveProfiles("local")
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class OrderDeletionIT {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);
    private static final String DB_PASSWORD = UUID.randomUUID().toString();
    @Container static final org.testcontainers.mysql.MySQLContainer MYSQL =
            new org.testcontainers.mysql.MySQLContainer("mysql:8.0.45")
                    .withDatabaseName("order_deletion_test").withUsername("root")
                    .withPassword(UUID.randomUUID().toString()).withEnv("MYSQL_ROOT_HOST", "%")
                    .withUrlParam("connectionTimeZone", "UTC").withUrlParam("forceConnectionTimeZoneToSession", "true")
                    .withUrlParam("sslMode", "DISABLED").withUrlParam("allowPublicKeyRetrieval", "true");
    @Autowired WebApplicationContext context;
    @MockitoSpyBean OrderQueryService queries;
    private OrderQueryService queryTarget;
    private MockMvc mvc;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        try (var connection = admin(); var create = connection.prepareStatement("CREATE USER 'deletion_runtime'@'%' IDENTIFIED BY ?");
             var grants = connection.createStatement()) {
            create.setString(1, DB_PASSWORD); create.execute();
            grants.execute("GRANT SELECT ON order_deletion_test.* TO 'deletion_runtime'@'%'");
            // Existing V7 receipt privileges; deletion adds no new order-column update permission.
            grants.execute("GRANT UPDATE(status,received_at) ON order_deletion_test.support_order TO 'deletion_runtime'@'%'");
            for (String table : List.of("support_order_buyer_deletion", "support_after_sale", "support_after_sale_audit"))
                grants.execute("GRANT INSERT ON order_deletion_test." + table + " TO 'deletion_runtime'@'%'");
            // The pre-existing idempotent after-sale INSERT uses ON DUPLICATE KEY UPDATE id=id.
            grants.execute("GRANT UPDATE(id) ON order_deletion_test.support_after_sale TO 'deletion_runtime'@'%'");
        }
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "deletion_runtime");
        registry.add("spring.datasource.password", () -> DB_PASSWORD);
        account(registry, 0, "buyer", 200, "BUYER", "ORDER_READ");
        account(registry, 1, "other", 201, "BUYER", "ORDER_READ");
        account(registry, 2, "staff", 200, "MERCHANT_STAFF", "ORDER_READ");
        account(registry, 3, "no-read", 200, "BUYER", "");
    }

    private static void account(DynamicPropertyRegistry registry, int index, String name, long buyer,
                                String type, String permissions) {
        String prefix = "supportflow.security.local.accounts[" + index + "].";
        registry.add(prefix + "username", () -> name); registry.add(prefix + "password-hash", () -> HASH);
        registry.add(prefix + "identity.buyer-id", () -> buyer); registry.add(prefix + "account-type", () -> type);
        registry.add(prefix + "permissions", () -> permissions);
    }

    @BeforeEach void setup() {
        // Stub the Mockito target, not the method-security proxy: HTTP still exercises the full proxy chain.
        queryTarget = AopTestUtils.getUltimateTargetObject(queries);
        reset(queryTarget);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"COMPLETED", "CANCELLED"})
    void endedOrdersHideForBuyerAndRepeatedDeletionPreservesFirstAudit(OrderStatus state) throws Exception {
        var order = order(state);
        String before = orderSnapshot(order);
        LocalDateTime beforeTime = now();
        remove("buyer", order).andExpect(status().isNoContent()).andExpect(result ->
                assertThat(result.getResponse().getContentAsByteArray()).isEmpty());
        LocalDateTime afterTime = now();
        String firstAudit = auditSnapshot(order);
        remove("buyer", order).andExpect(status().isNoContent());
        assertThat(auditSnapshot(order)).isEqualTo(firstAudit);
        assertThat(count("support_order_buyer_deletion", "order_id", order.id())).isEqualTo(1);
        assertThat(orderSnapshot(order)).isEqualTo(before);
        try (var connection = admin(); var query = connection.prepareStatement("""
                SELECT actor,deleted_at,tenant_id,buyer_id,order_no FROM support_order_buyer_deletion WHERE order_id=?
                """)) {
            query.setLong(1, order.id());
            try (var row = query.executeQuery()) {
                assertThat(row.next()).isTrue(); assertThat(row.getString("actor")).isEqualTo("buyer");
                assertThat(row.getObject("deleted_at", LocalDateTime.class)).isBetween(beforeTime, afterTime);
                assertThat(row.getLong("tenant_id")).isEqualTo(100); assertThat(row.getLong("buyer_id")).isEqualTo(200);
                assertThat(row.getString("order_no")).isEqualTo(order.number());
            }
        }
        mvc.perform(get(url(order)).with(httpBasic("buyer", PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(post(url(order) + "/after-sales").with(httpBasic("buyer", PASSWORD)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"商品破损\"}"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CREATED", "PAID", "SHIPPED"})
    void activeOrdersReturnConflictWithoutChangingFacts(OrderStatus state) throws Exception {
        var order = order(state); String before = orderSnapshot(order);
        remove("buyer", order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_DELETION_CONFLICT"));
        assertThat(count("support_order_buyer_deletion", "order_id", order.id())).isZero();
        assertThat(orderSnapshot(order)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_REVIEW", "APPROVED_MISSING", "PENDING", "PROCESSING", "FAILED"})
    void unfinishedAfterSalesBlockDeletionIncludingFailedExecution(String state) throws Exception {
        var order = order(OrderStatus.COMPLETED); afterSale(order, state);
        String before = orderSnapshot(order);
        remove("buyer", order).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_DELETION_CONFLICT"))
                .andExpect(jsonPath("$.message").value("该订单仍有待处理售后，请处理完成后再删除。"));
        assertThat(count("support_order_buyer_deletion", "order_id", order.id())).isZero();
        assertThat(orderSnapshot(order)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECTED", "SUCCEEDED"})
    void terminalAfterSaleFactsAreRetainedAfterDeletion(String state) throws Exception {
        var order = order(OrderStatus.COMPLETED); String request = afterSale(order, state);
        long tasksBefore = count("support_after_sale_execution", "order_id", order.id());
        remove("buyer", order).andExpect(status().isNoContent());
        assertThat(count("support_order", "id", order.id())).isEqualTo(1);
        assertThat(count("support_after_sale", "order_id", order.id())).isEqualTo(1);
        assertThat(count("support_after_sale_execution", "order_id", order.id())).isEqualTo(tasksBefore);
        try (var connection = admin(); var query = connection.prepareStatement("SELECT status FROM support_after_sale WHERE id=?")) {
            query.setString(1, request);
            try (var row = query.executeQuery()) { assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo(state.equals("REJECTED") ? "REJECTED" : "APPROVED"); }
        }
    }

    @Test void securityOwnershipCsrfAndValidationCannotBeBypassed() throws Exception {
        var order = order(OrderStatus.COMPLETED);
        mvc.perform(delete(url(order)).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete(url(order)).with(httpBasic("buyer", PASSWORD))).andExpect(status().isForbidden());
        remove("staff", order).andExpect(status().isForbidden());
        remove("no-read", order).andExpect(status().isForbidden());
        remove("other", order).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/shops/101/orders/" + order.number()).with(httpBasic("buyer", PASSWORD)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/shops/0/orders/" + order.number()).with(httpBasic("buyer", PASSWORD)).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/shops/100/orders/" + "X".repeat(65)).with(httpBasic("buyer", PASSWORD)).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(url(order) + "/receipt-confirmation").with(httpBasic("buyer", PASSWORD)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(count("support_order_buyer_deletion", "order_id", order.id())).isZero();
    }

    @Test void concurrentDuplicateRequestsCreateExactlyOneImmutableDeletion() throws Exception {
        var order = order(OrderStatus.COMPLETED);
        var ready = new CountDownLatch(4); var go = new CountDownLatch(1);
        // Blocking start gates need one dedicated worker per participant, regardless of CPU count.
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            List<CompletableFuture<MvcResult>> calls = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(ignored -> CompletableFuture.supplyAsync(() -> {
                        ready.countDown(); await(go);
                        try { return remove("buyer", order).andReturn(); } catch (Exception failure) { throw new AssertionError(failure); }
                    }, workers)).toList();
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                go.countDown();
            }
            for (var call : calls) assertThat(call.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(204);
            assertThat(count("support_order_buyer_deletion", "order_id", order.id())).isEqualTo(1);
            assertThat(count("support_order", "id", order.id())).isEqualTo(1);
        }
    }

    @Test void afterSaleCommittedWhileDeleteWaitsForOrderLockIsObserved() throws Exception {
        var order = order(OrderStatus.COMPLETED);
        CompletableFuture<MvcResult> deletion;
        try (var connection = admin(); var lock = connection.prepareStatement("SELECT id FROM support_order WHERE id=? FOR UPDATE")) {
            connection.setAutoCommit(false); lock.setLong(1, order.id());
            try (var rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
            deletion = CompletableFuture.supplyAsync(() -> {
                try { return remove("buyer", order).andReturn(); } catch (Exception failure) { throw new AssertionError(failure); }
            });
            assertThat(awaitOrderLockWait()).isTrue();
            insertAfterSale(connection, order, "PENDING_REVIEW"); connection.commit();
        }
        assertThat(deletion.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        assertThat(count("support_order_buyer_deletion", "order_id", order.id())).isZero();
        assertThat(count("support_after_sale", "order_id", order.id())).isEqualTo(1);
    }

    @Test void submissionWithAnOldOrderSnapshotCannotCreateCaseAfterDeletionCommits() throws Exception {
        var order = order(OrderStatus.COMPLETED);
        var read = new CountDownLatch(1); var resume = new CountDownLatch(1);
        doAnswer(call -> {
            var result = call.callRealMethod();
            if (order.number().equals(call.getArgument(2))) { read.countDown(); await(resume); }
            return result;
        }).when(queryTarget).getOwnedOrder(any(), anyLong(), anyString());
        var submission = CompletableFuture.supplyAsync(() -> {
            try { return mvc.perform(post(url(order) + "/after-sales").with(httpBasic("buyer", PASSWORD)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"商品破损\"}")).andReturn(); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
        try {
            assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
            remove("buyer", order).andExpect(status().isNoContent());
        } finally { resume.countDown(); }
        assertThat(submission.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(404);
        assertThat(count("support_after_sale", "order_id", order.id())).isZero();
        assertThat(count("support_order_buyer_deletion", "order_id", order.id())).isEqualTo(1);
    }

    @Test void missingInsertGrantCannotPretendThatDeletionSucceededAndRuntimeCannotEraseAudit() throws Exception {
        var order = order(OrderStatus.COMPLETED);
        try (var connection = admin(); var statement = connection.createStatement()) {
            statement.execute("REVOKE INSERT ON order_deletion_test.support_order_buyer_deletion FROM 'deletion_runtime'@'%'");
        }
        try { remove("buyer", order).andExpect(status().isInternalServerError()); }
        finally {
            try (var connection = admin(); var statement = connection.createStatement()) {
                statement.execute("GRANT INSERT ON order_deletion_test.support_order_buyer_deletion TO 'deletion_runtime'@'%'");
            }
        }
        assertThat(count("support_order_buyer_deletion", "order_id", order.id())).isZero();
        remove("buyer", order).andExpect(status().isNoContent());
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "deletion_runtime", DB_PASSWORD);
             var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM support_order_buyer_deletion WHERE 1=0"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE support_order_buyer_deletion SET actor=actor WHERE 1=0"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM support_order WHERE 1=0"))
                    .isInstanceOf(SQLException.class);
        }
    }

    private ResultActions remove(String user, Fixture order) throws Exception {
        return mvc.perform(delete(url(order)).with(httpBasic(user, PASSWORD)).with(csrf()));
    }
    private String url(Fixture order) { return "/api/v1/shops/100/orders/" + order.number(); }

    private Fixture order(OrderStatus state) throws SQLException {
        String number = "SFD" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = admin(); var insert = connection.prepareStatement("""
                INSERT INTO support_order(tenant_id,customer_id,buyer_id,order_no,status,total_amount,received_at)
                VALUES(100,200,200,?,?,199.90,DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 DAY))
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, number); insert.setString(2, state.name()); insert.executeUpdate();
            try (var row = insert.getGeneratedKeys()) { assertThat(row.next()).isTrue(); return new Fixture(row.getLong(1), number); }
        }
    }

    private String afterSale(Fixture order, String state) throws SQLException {
        try (var connection = admin()) {
            String id = insertAfterSale(connection, order, state.equals("PENDING_REVIEW") || state.equals("REJECTED") ? state : "APPROVED");
            if (List.of("PENDING", "PROCESSING", "FAILED", "SUCCEEDED").contains(state)) {
                try (var insert = connection.prepareStatement("""
                        INSERT INTO support_after_sale_execution(request_id,order_id,buyer_id,tenant_id,amount,status,
                          available_at,attempt_count,lease_until,claim_token,last_error_code,last_error,reference,completed_at)
                        VALUES(?,?,200,100,199.90,?,UTC_TIMESTAMP(3),?,?,?,?,?,?,?)
                        """)) {
                    insert.setString(1, id); insert.setLong(2, order.id()); insert.setString(3, state);
                    insert.setInt(4, state.equals("PENDING") ? 0 : 1);
                    insert.setObject(5, state.equals("PROCESSING") ? now().plusSeconds(30) : null);
                    insert.setString(6, state.equals("PROCESSING") ? UUID.randomUUID().toString() : null);
                    insert.setString(7, state.equals("FAILED") ? "TRANSIENT_FAILURE" : null);
                    insert.setString(8, state.equals("FAILED") ? "fixed fixture failure" : null);
                    insert.setString(9, state.equals("SUCCEEDED") ? "SIM-" + id : null);
                    insert.setObject(10, state.equals("SUCCEEDED") ? now() : null); insert.executeUpdate();
                }
            }
            return id;
        }
    }

    private String insertAfterSale(Connection connection, Fixture order, String state) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (var insert = connection.prepareStatement("""
                INSERT INTO support_after_sale(id,request_key,buyer_id,tenant_id,order_id,order_no,reason,status)
                VALUES(?,?,200,100,?,?,'fixed isolated reason',?)
                """)) {
            insert.setString(1, id); insert.setString(2, UUID.randomUUID().toString().replace("-", "").repeat(2));
            insert.setLong(3, order.id()); insert.setString(4, order.number()); insert.setString(5, state); insert.executeUpdate();
        }
        return id;
    }

    private String orderSnapshot(Fixture order) throws SQLException {
        try (var connection = admin(); var query = connection.prepareStatement("SELECT * FROM support_order WHERE id=?")) {
            query.setLong(1, order.id());
            try (var row = query.executeQuery()) {
                assertThat(row.next()).isTrue(); StringBuilder result = new StringBuilder();
                for (int column = 1; column <= row.getMetaData().getColumnCount(); column++) result.append(row.getString(column)).append('|');
                return result.toString();
            }
        }
    }

    private String auditSnapshot(Fixture order) throws SQLException {
        try (var connection = admin(); var query = connection.prepareStatement("""
                SELECT actor,deleted_at FROM support_order_buyer_deletion WHERE order_id=?
                """)) {
            query.setLong(1, order.id());
            try (var row = query.executeQuery()) { assertThat(row.next()).isTrue(); return row.getString(1) + "|" + row.getString(2); }
        }
    }

    private long count(String table, String column, long value) throws SQLException {
        try (var connection = admin(); var query = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?")) {
            query.setLong(1, value);
            try (var row = query.executeQuery()) { assertThat(row.next()).isTrue(); return row.getLong(1); }
        }
    }

    private LocalDateTime now() throws SQLException {
        try (var connection = admin(); var statement = connection.createStatement(); var row = statement.executeQuery("SELECT UTC_TIMESTAMP(3)")) {
            assertThat(row.next()).isTrue(); return row.getObject(1, LocalDateTime.class);
        }
    }

    private boolean awaitOrderLockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (var connection = admin(); var query = connection.prepareStatement("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits waits
                JOIN performance_schema.data_locks requested ON requested.ENGINE_LOCK_ID=waits.REQUESTING_ENGINE_LOCK_ID
                WHERE requested.OBJECT_SCHEMA='order_deletion_test' AND requested.OBJECT_NAME='support_order'
                  AND requested.LOCK_STATUS='WAITING'
                """)) {
            while (System.nanoTime() < deadline) {
                try (var row = query.executeQuery()) { assertThat(row.next()).isTrue(); if (row.getLong(1) > 0) return true; }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        return false;
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("Test barrier timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
    private record Fixture(long id, String number) { }
}
