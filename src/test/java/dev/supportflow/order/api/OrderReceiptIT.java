package dev.supportflow.order.api;

import dev.supportflow.SupportFlowApplication;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.testsupport.OrderVisibilityFixtures;
import dev.supportflow.testsupport.HumanSupportFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Receipt confirmation against isolated MySQL with column-scoped runtime privileges. */
@SpringBootTest(classes = SupportFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.config.location=classpath:/application-day4-test.yml",
                "spring.main.banner-mode=off",
                "supportflow.ai.deepseek.enabled=false"
        })
@ActiveProfiles("local")
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class OrderReceiptIT {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);
    private static final String DATABASE_PASSWORD = UUID.randomUUID().toString();

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName("supportflow_receipt_test")
            .withUsername("root")
            .withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withUrlParam("sslMode", "DISABLED")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static boolean prepared;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    private MockMvc mvc;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        prepareDatabase();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "receipt_runtime");
        registry.add("spring.datasource.password", () -> DATABASE_PASSWORD);
        account(registry, 0, "buyer", 200, "ORDER_READ,ORDER_CONFIRM_RECEIPT");
        account(registry, 1, "other", 201, "ORDER_READ,ORDER_CONFIRM_RECEIPT");
        account(registry, 2, "read-only", 200, "ORDER_READ");
        account(registry, 3, "confirm-only", 200, "ORDER_CONFIRM_RECEIPT");
        account(registry, 4, "no-permissions", 200, "");
    }

    private static void account(DynamicPropertyRegistry registry, int index, String username,
                                long buyerId, String permissions) {
        String prefix = "supportflow.security.local.accounts[" + index + "].";
        registry.add(prefix + "username", () -> username);
        registry.add(prefix + "password-hash", () -> HASH);
        registry.add(prefix + "identity.buyer-id", () -> buyerId);
        registry.add(prefix + "permissions", () -> permissions);
    }

    private static synchronized void prepareDatabase() throws SQLException {
        if (prepared) return;
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").validateOnMigrate(true)
                .baselineOnMigrate(false).cleanDisabled(true).createSchemas(false).load().migrate();
        try (Connection connection = admin();
             PreparedStatement create = connection.prepareStatement(
                     "CREATE USER 'receipt_runtime'@'%' IDENTIFIED BY ?");
             Statement grant = connection.createStatement()) {
            create.setString(1, DATABASE_PASSWORD);
            create.execute();
            OrderVisibilityFixtures.grantRead(connection, "supportflow_receipt_test", "receipt_runtime");
            HumanSupportFixtures.grantAiAdmission(connection, "supportflow_receipt_test", "receipt_runtime");
            grant.execute("GRANT SELECT, UPDATE (status, received_at) ON supportflow_receipt_test.support_order TO 'receipt_runtime'@'%'");
            grant.execute("GRANT INSERT ON supportflow_receipt_test.support_order_receipt_audit TO 'receipt_runtime'@'%'");
            grant.execute("GRANT SELECT, INSERT, UPDATE ON supportflow_receipt_test.support_after_sale TO 'receipt_runtime'@'%'");
            grant.execute("GRANT SELECT, INSERT ON supportflow_receipt_test.support_after_sale_audit TO 'receipt_runtime'@'%'");
            grant.execute("GRANT SELECT, INSERT, UPDATE ON supportflow_receipt_test.support_after_sale_execution TO 'receipt_runtime'@'%'");
            grant.execute("GRANT SELECT, INSERT ON supportflow_receipt_test.support_simulated_refund TO 'receipt_runtime'@'%'");
        }
        prepared = true;
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void shouldConfirmWithDatabaseTimeIgnoreClientFactsAndReturnOnlyPublicOrderFields() throws Exception {
        Fixture order = order(OrderStatus.SHIPPED, null);
        Instant before = databaseNow();
        JsonNode response = body(mvc.perform(post(receiptUrl(order)).with(httpBasic("buyer", PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "receivedAt", "2000-01-01T00:00:00Z", "buyerId", 201,
                                "shopId", 999, "status", "CANCELLED", "totalAmount", "0.01"))))
                .andExpect(status().isOk()).andReturn());
        Instant after = databaseNow();
        JsonNode publicOrder = response.get("order");
        Instant receivedAt = Instant.parse(publicOrder.get("receivedAt").asString());
        assertThat(receivedAt).isBetween(before, after);
        assertThat(receivedAt.getNano() % 1_000_000).isZero();
        assertThat(response.get("alreadyConfirmed").asBoolean()).isFalse();
        assertThat(publicOrder.get("shopId").asLong()).isEqualTo(100);
        assertThat(publicOrder.get("orderNo").asString()).isEqualTo(order.number());
        assertThat(publicOrder.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(new BigDecimal(publicOrder.get("totalAmount").asString())).isEqualByComparingTo("199.90");
        assertPublicOrder(publicOrder);
        Stored stored = stored(order);
        assertThat(stored.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(stored.receivedAt()).isEqualTo(receivedAt);
        assertThat(stored.buyerId()).isEqualTo(200);
        assertThat(stored.amount()).isEqualByComparingTo("199.90");
        assertThat(countReceiptAudit(order)).isEqualTo(1);
        assertAuditMatches(order, receivedAt);
        assertThat(countCases(order)).isZero();
        assertEligible(response.get("eligibility"), receivedAt);
    }

    @Test
    void shouldReturnOriginalReceiptForRepeatedConfirmationAndWriteOneAudit() throws Exception {
        Fixture order = order(OrderStatus.SHIPPED, null);
        JsonNode first = body(confirm("buyer", order, true).andExpect(status().isOk()).andReturn());
        JsonNode repeated = body(confirm("buyer", order, true).andExpect(status().isOk()).andReturn());
        assertThat(first.get("alreadyConfirmed").asBoolean()).isFalse();
        assertThat(repeated.get("alreadyConfirmed").asBoolean()).isTrue();
        assertThat(repeated.get("order")).isEqualTo(first.get("order"));
        assertThat(repeated.get("eligibility").get("deadlineAt"))
                .isEqualTo(first.get("eligibility").get("deadlineAt"));
        assertThat(countReceiptAudit(order)).isEqualTo(1);
    }

    @Test
    void shouldPreserveExistingHistoricalReceiptWithoutResettingExpiredAfterSaleWindow() throws Exception {
        Instant receivedAt = databaseNow().minus(Duration.ofDays(20));
        Fixture order = order(OrderStatus.COMPLETED, receivedAt);
        JsonNode response = body(confirm("buyer", order, true).andExpect(status().isOk()).andReturn());
        assertThat(response.get("alreadyConfirmed").asBoolean()).isTrue();
        assertThat(Instant.parse(response.get("order").get("receivedAt").asString())).isEqualTo(receivedAt);
        assertThat(response.get("eligibility").get("status").asString()).isEqualTo("EXPIRED");
        assertThat(response.get("eligibility").get("canSubmit").asBoolean()).isFalse();
        assertThat(stored(order).receivedAt()).isEqualTo(receivedAt);
        assertThat(countReceiptAudit(order)).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CREATED", "PAID", "CANCELLED", "COMPLETED"})
    void shouldRejectNonShippedOrdersWithoutInventingAReceiptDate(OrderStatus state) throws Exception {
        Fixture order = order(state, null);
        confirm("buyer", order, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_RECEIPT_CONFLICT"));
        assertThat(stored(order).status()).isEqualTo(state);
        assertThat(stored(order).receivedAt()).isNull();
        assertThat(countReceiptAudit(order)).isZero();
    }

    @Test
    void shouldRejectInconsistentShippedOrderWithExistingReceiptInsteadOfOverwritingIt() throws Exception {
        Instant receivedAt = databaseNow().minus(Duration.ofDays(1));
        Fixture order = order(OrderStatus.SHIPPED, receivedAt);
        confirm("buyer", order, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_RECEIPT_CONFLICT"));
        assertThat(stored(order).status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(stored(order).receivedAt()).isEqualTo(receivedAt);
        assertThat(countReceiptAudit(order)).isZero();
    }

    @Test
    void shouldRejectFutureReceiptWithoutReplacingAnAbnormalHistoricalFact() throws Exception {
        Instant receivedAt = databaseNow().plus(Duration.ofDays(1));
        Fixture order = order(OrderStatus.COMPLETED, receivedAt);
        confirm("buyer", order, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_RECEIPT_CONFLICT"));
        assertThat(stored(order).status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(stored(order).receivedAt()).isEqualTo(receivedAt);
        assertThat(countReceiptAudit(order)).isZero();
    }

    @Test
    void shouldHideOtherBuyersOtherShopsAndUnknownOrders() throws Exception {
        Fixture order = order(OrderStatus.SHIPPED, null);
        for (ResultActions denied : List.of(confirm("other", order, true),
                confirm("buyer", new Fixture(order.id(), 101, order.number()), true),
                confirm("buyer", new Fixture(order.id(), 100, "MISSING-" + UUID.randomUUID()), true))) {
            denied.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        }
        assertThat(stored(order).status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(stored(order).receivedAt()).isNull();
        assertThat(countReceiptAudit(order)).isZero();
    }

    @Test
    void shouldRequireBothReadAndConfirmPermissions() throws Exception {
        Fixture order = order(OrderStatus.SHIPPED, null);
        for (String username : List.of("read-only", "confirm-only", "no-permissions")) {
            confirm(username, order, true).andExpect(status().isForbidden());
        }
        assertThat(stored(order).status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(stored(order).receivedAt()).isNull();
        assertThat(countReceiptAudit(order)).isZero();
    }

    @Test
    void shouldRequireAuthenticationAndCsrfAndExposeNoGetMutation() throws Exception {
        Fixture order = order(OrderStatus.SHIPPED, null);
        mvc.perform(post(receiptUrl(order)).with(csrf())).andExpect(status().isUnauthorized());
        confirm("buyer", order, false).andExpect(status().isForbidden());
        mvc.perform(post(receiptUrl(order)).with(httpBasic("buyer", PASSWORD)).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        mvc.perform(get(receiptUrl(order)).with(httpBasic("buyer", PASSWORD))).andExpect(status().isForbidden());
        assertThat(stored(order).status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(stored(order).receivedAt()).isNull();
        assertThat(countReceiptAudit(order)).isZero();
    }

    @Test
    void shouldSerializeFourConcurrentConfirmationsToOneReceiptAndOneAudit() throws Exception {
        Fixture order = order(OrderStatus.SHIPPED, null);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch go = new CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            List<CompletableFuture<MvcResult>> calls = List.of(
                    concurrentConfirm(order, ready, go, workers), concurrentConfirm(order, ready, go, workers),
                    concurrentConfirm(order, ready, go, workers), concurrentConfirm(order, ready, go, workers));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                go.countDown();
            }
            List<JsonNode> responses = new ArrayList<>();
            for (CompletableFuture<MvcResult> call : calls) {
                MvcResult result = call.get(30, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                responses.add(body(result));
            }
            assertThat(responses.stream().filter(value -> !value.get("alreadyConfirmed").asBoolean()).count()).isEqualTo(1);
            assertThat(responses.stream().map(value -> value.get("order").get("receivedAt").asString()).distinct().count())
                    .isEqualTo(1);
            assertThat(stored(order).status()).isEqualTo(OrderStatus.COMPLETED);
            assertThat(countReceiptAudit(order)).isEqualTo(1);
            assertAuditMatches(order, stored(order).receivedAt());
        }
    }

    @Test
    void shouldRollbackOrderWhenReceiptAuditCannotBeWrittenThenAllowRetry() throws Exception {
        Fixture order = order(OrderStatus.SHIPPED, null);
        setReceiptAuditInsert(false);
        try {
            confirm("buyer", order, true).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
            assertThat(stored(order).status()).isEqualTo(OrderStatus.SHIPPED);
            assertThat(stored(order).receivedAt()).isNull();
            assertThat(countReceiptAudit(order)).isZero();
        } finally {
            setReceiptAuditInsert(true);
        }
        confirm("buyer", order, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyConfirmed").value(false));
        assertThat(stored(order).status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(countReceiptAudit(order)).isEqualTo(1);
    }

    @Test
    void shouldReadCommittedCancellationAfterWaitingForOrderLock() throws Exception {
        Fixture order = order(OrderStatus.SHIPPED, null);
        try (Connection cancellation = admin(); PreparedStatement update = cancellation.prepareStatement(
                "UPDATE support_order SET status='CANCELLED' WHERE id=?")) {
            cancellation.setAutoCommit(false);
            update.setLong(1, order.id());
            assertThat(update.executeUpdate()).isEqualTo(1);
            CompletableFuture<MvcResult> confirmation = concurrentConfirm(order, new CountDownLatch(0), new CountDownLatch(0));
            try {
                assertThat(awaitOrderLockWait()).as("confirmation must wait for the competing order update").isTrue();
                assertThat(confirmation.isDone()).isFalse();
                cancellation.commit();
                MvcResult result = confirmation.get(30, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(409);
                assertThat(body(result).get("code").asString()).isEqualTo("ORDER_RECEIPT_CONFLICT");
                assertThat(stored(order).status()).isEqualTo(OrderStatus.CANCELLED);
                assertThat(stored(order).receivedAt()).isNull();
                assertThat(countReceiptAudit(order)).isZero();
            } finally {
                cancellation.rollback();
                if (!confirmation.isDone()) confirmation.get(30, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void shouldKeepRuntimeUnableToChangeMoneyBuyerOrDeleteOrdersAndAudit() throws Exception {
        Fixture order = order(OrderStatus.SHIPPED, null);
        confirm("buyer", order, true).andExpect(status().isOk());
        try (Connection runtime = DriverManager.getConnection(MYSQL.getJdbcUrl(), "receipt_runtime", DATABASE_PASSWORD)) {
            for (String sql : List.of("UPDATE support_order SET total_amount=0 WHERE id=?",
                    "UPDATE support_order SET buyer_id=201 WHERE id=?",
                    "UPDATE support_order SET tenant_id=101 WHERE id=?",
                    "DELETE FROM support_order WHERE id=?",
                    "UPDATE support_order_receipt_audit SET actor='forged' WHERE order_id=?",
                    "DELETE FROM support_order_receipt_audit WHERE order_id=?")) {
                try (PreparedStatement denied = runtime.prepareStatement(sql)) {
                    denied.setLong(1, order.id());
                    assertThatThrownBy(denied::executeUpdate).as(sql).isInstanceOf(SQLException.class)
                            .satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isIn(1142, 1143));
                }
            }
        }
        assertThat(stored(order).amount()).isEqualByComparingTo("199.90");
        assertThat(stored(order).buyerId()).isEqualTo(200);
        assertThat(countReceiptAudit(order)).isEqualTo(1);
    }

    @Test
    void shouldUseConfirmedReceiptForAfterSaleDeadlineAndPreserveTheSubmittedSnapshot() throws Exception {
        Fixture order = order(OrderStatus.SHIPPED, null);
        JsonNode confirmation = body(confirm("buyer", order, true).andExpect(status().isOk()).andReturn());
        Instant receivedAt = Instant.parse(confirmation.get("order").get("receivedAt").asString());
        JsonNode eligibility = body(mvc.perform(get("/api/v1/shops/{shopId}/orders/{orderNo}/after-sale-eligibility",
                        order.shopId(), order.number()).with(httpBasic("buyer", PASSWORD)))
                .andExpect(status().isOk()).andReturn());
        assertEligible(eligibility, receivedAt);
        JsonNode ticket = refund(order, "商品存在质量问题");
        assertThat(ticket.get("status").asString()).isEqualTo("PENDING_REVIEW");
        assertEligible(ticket.get("eligibility"), receivedAt);
        confirm("buyer", order, true).andExpect(status().isOk()).andExpect(jsonPath("$.alreadyConfirmed").value(true));
        JsonNode retry = refund(order, "再次点击提交");
        assertThat(retry.get("id")).isEqualTo(ticket.get("id"));
        assertThat(retry.get("reason")).isEqualTo(ticket.get("reason"));
        assertThat(retry.get("eligibility")).isEqualTo(ticket.get("eligibility"));
        assertThat(countCases(order)).isEqualTo(1);
        assertThat(countReceiptAudit(order)).isEqualTo(1);
    }

    private ResultActions confirm(String username, Fixture order, boolean withCsrf) throws Exception {
        var request = post(receiptUrl(order)).with(httpBasic(username, PASSWORD));
        return mvc.perform(withCsrf ? request.with(csrf()) : request);
    }

    private String receiptUrl(Fixture order) {
        return "/api/v1/shops/" + order.shopId() + "/orders/" + order.number() + "/receipt-confirmation";
    }

    private JsonNode refund(Fixture order, String reason) throws Exception {
        return body(mvc.perform(post("/api/v1/shops/{shopId}/orders/{orderNo}/after-sales", order.shopId(), order.number())
                        .with(httpBasic("buyer", PASSWORD)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", reason))))
                .andExpect(status().isOk()).andReturn());
    }

    private CompletableFuture<MvcResult> concurrentConfirm(Fixture order, CountDownLatch ready, CountDownLatch go) {
        return concurrentConfirm(order, ready, go, java.util.concurrent.ForkJoinPool.commonPool());
    }

    private CompletableFuture<MvcResult> concurrentConfirm(Fixture order, CountDownLatch ready, CountDownLatch go,
                                                          java.util.concurrent.Executor workers) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Confirmation start timed out");
                return confirm("buyer", order, true).andReturn();
            } catch (Exception failure) {
                throw new IllegalStateException("Isolated confirmation failed", failure);
            }
        }, workers);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void assertPublicOrder(JsonNode order) {
        for (String privateField : List.of("id", "orderId", "buyerId", "tenantId", "customerId", "version")) {
            assertThat(order.has(privateField)).as(privateField).isFalse();
        }
    }

    private void assertEligible(JsonNode eligibility, Instant receivedAt) {
        assertThat(eligibility.get("status").asString()).isEqualTo("ELIGIBLE");
        assertThat(eligibility.get("canSubmit").asBoolean()).isTrue();
        assertThat(eligibility.get("windowDays").asInt()).isEqualTo(15);
        assertThat(Instant.parse(eligibility.get("receivedAt").asString())).isEqualTo(receivedAt);
        assertThat(Instant.parse(eligibility.get("deadlineAt").asString())).isEqualTo(receivedAt.plus(Duration.ofDays(15)));
    }

    private Fixture order(OrderStatus state, Instant receivedAt) throws SQLException {
        String number = "SFRECEIPT" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin(); PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO support_order
                (tenant_id, customer_id, buyer_id, order_no, status, total_amount, currency, received_at)
                VALUES (100, 200, 200, ?, ?, 199.90, 'CNY', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, number);
            insert.setString(2, state.name());
            insert.setObject(3, receivedAt == null ? null : LocalDateTime.ofInstant(receivedAt, ZoneOffset.UTC));
            assertThat(insert.executeUpdate()).isEqualTo(1);
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return new Fixture(keys.getLong(1), 100, number);
            }
        }
    }

    private Stored stored(Fixture order) throws SQLException {
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement(
                "SELECT status, received_at, buyer_id, total_amount FROM support_order WHERE id=?")) {
            query.setLong(1, order.id());
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                LocalDateTime received = row.getObject("received_at", LocalDateTime.class);
                return new Stored(OrderStatus.valueOf(row.getString("status")),
                        received == null ? null : received.toInstant(ZoneOffset.UTC),
                        row.getLong("buyer_id"), row.getBigDecimal("total_amount"));
            }
        }
    }

    private void assertAuditMatches(Fixture order, Instant receivedAt) throws SQLException {
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement("""
                SELECT tenant_id, buyer_id, order_no, actor, received_at, recorded_at
                FROM support_order_receipt_audit WHERE order_id=?
                """)) {
            query.setLong(1, order.id());
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong("tenant_id")).isEqualTo(order.shopId());
                assertThat(row.getLong("buyer_id")).isEqualTo(200);
                assertThat(row.getString("order_no")).isEqualTo(order.number());
                assertThat(row.getString("actor")).isEqualTo("buyer");
                assertThat(row.getObject("received_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)).isEqualTo(receivedAt);
                assertThat(row.getObject("recorded_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)).isAfterOrEqualTo(receivedAt);
                assertThat(row.next()).isFalse();
            }
        }
    }

    private long countReceiptAudit(Fixture order) throws SQLException {
        return count("SELECT COUNT(*) FROM support_order_receipt_audit WHERE order_id=?", order.id());
    }

    private long countCases(Fixture order) throws SQLException {
        return count("SELECT COUNT(*) FROM support_after_sale WHERE order_id=?", order.id());
    }

    private long count(String sql, long id) throws SQLException {
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setLong(1, id);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private Instant databaseNow() throws SQLException {
        try (Connection connection = admin(); Statement query = connection.createStatement();
             ResultSet row = query.executeQuery("SELECT UTC_TIMESTAMP(3)")) {
            assertThat(row.next()).isTrue();
            return row.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC);
        }
    }

    private void setReceiptAuditInsert(boolean enabled) throws SQLException {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute(enabled
                    ? "GRANT INSERT ON supportflow_receipt_test.support_order_receipt_audit TO 'receipt_runtime'@'%'"
                    : "REVOKE INSERT ON supportflow_receipt_test.support_order_receipt_audit FROM 'receipt_runtime'@'%'");
        }
    }

    private boolean awaitOrderLockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits waits
                JOIN performance_schema.data_locks requested
                  ON requested.ENGINE_LOCK_ID=waits.REQUESTING_ENGINE_LOCK_ID
                WHERE requested.OBJECT_SCHEMA='supportflow_receipt_test'
                  AND requested.OBJECT_NAME='support_order' AND requested.LOCK_STATUS='WAITING'
                """)) {
            while (System.nanoTime() < deadline) {
                try (ResultSet row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    if (row.getLong(1) > 0) return true;
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        return false;
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private record Fixture(long id, long shopId, String number) { }
    private record Stored(OrderStatus status, Instant receivedAt, long buyerId, BigDecimal amount) { }
}
