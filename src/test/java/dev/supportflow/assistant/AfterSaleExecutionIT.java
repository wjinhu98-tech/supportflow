package dev.supportflow.assistant;

import dev.supportflow.SupportFlowApplication;
import dev.supportflow.assistant.execution.AfterSaleExecutionProcessor;
import dev.supportflow.assistant.execution.AfterSaleExecutionWorker;
import dev.supportflow.assistant.execution.ExecutionClaim;
import dev.supportflow.assistant.execution.ExecutionFailureCode;
import dev.supportflow.assistant.execution.SimulatedRefundGateway;
import dev.supportflow.testsupport.MerchantFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Durable approval/execution boundary against an isolated MySQL and a limited runtime user. */
@SpringBootTest(classes = SupportFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.config.location=classpath:/application-day4-test.yml",
                "spring.main.banner-mode=off",
                "supportflow.ai.deepseek.enabled=false",
                "supportflow.after-sale.execution.enabled=false"
        })
@ActiveProfiles("local")
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class AfterSaleExecutionIT {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);
    private static final String DATABASE_PASSWORD = UUID.randomUUID().toString();
    private static final String DATABASE = "supportflow_execution_test";
    private static final String RUNTIME = "execution_runtime";
    private static final String APPLY = "\u7533\u8bf7\u552e\u540e ";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName(DATABASE).withUsername("root").withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withUrlParam("sslMode", "DISABLED")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static boolean prepared;

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private AfterSaleExecutionProcessor processor;
    @Autowired private AfterSaleExecutionWorker worker;
    @MockitoSpyBean private SimulatedRefundGateway gateway;

    private MockMvc mvc;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        prepareDatabase();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME);
        registry.add("spring.datasource.password", () -> DATABASE_PASSWORD);
        account(registry, 0, "buyer", 200L, "ORDER_READ");
        account(registry, 1, "other", 201L, "ORDER_READ");
        account(registry, 2, "reviewer", 999L, "ORDER_READ,AFTER_SALE_REVIEW");
        account(registry, 3, "self-reviewer", 200L, "ORDER_READ,AFTER_SALE_REVIEW");
    }

    private static void account(DynamicPropertyRegistry registry, int index, String username,
                                long buyerId, String permissions) {
        String prefix = "supportflow.security.local.accounts[" + index + "].";
        registry.add(prefix + "username", () -> username);
        registry.add(prefix + "password-hash", () -> HASH);
        registry.add(prefix + "identity.buyer-id", () -> buyerId);
        registry.add(prefix + "permissions", () -> permissions);
        if (permissions.contains("AFTER_SALE_REVIEW")) {
            registry.add(prefix + "account-type", () -> "MERCHANT_STAFF");
        }
    }

    private static synchronized void prepareDatabase() throws SQLException {
        if (prepared) return;
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").validateOnMigrate(true).baselineOnMigrate(false)
                .cleanDisabled(true).createSchemas(false).load().migrate();
        try (Connection connection = admin();
             PreparedStatement create = connection.prepareStatement("CREATE USER 'execution_runtime'@'%' IDENTIFIED BY ?");
             Statement grant = connection.createStatement()) {
            create.setString(1, DATABASE_PASSWORD);
            create.execute();
            grant.execute("GRANT SELECT ON supportflow_execution_test.support_order TO 'execution_runtime'@'%'");
            grant.execute("GRANT SELECT ON supportflow_execution_test.support_shipment_event TO 'execution_runtime'@'%'");
            grant.execute("GRANT SELECT, INSERT, UPDATE ON supportflow_execution_test.support_after_sale TO 'execution_runtime'@'%'");
            grant.execute("GRANT SELECT, INSERT ON supportflow_execution_test.support_after_sale_audit TO 'execution_runtime'@'%'");
            grant.execute("GRANT SELECT, INSERT, UPDATE ON supportflow_execution_test.support_after_sale_execution TO 'execution_runtime'@'%'");
            grant.execute("GRANT SELECT, INSERT ON supportflow_execution_test.support_simulated_refund TO 'execution_runtime'@'%'");
            MerchantFixtures.grantRuntimeRead(connection, DATABASE, RUNTIME);
            MerchantFixtures.standardReviewers(connection);
        }
        prepared = true;
    }

    @BeforeEach
    void setUp() throws SQLException {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        reset(gatewaySpy());
        // Fixtures belong exclusively to this throwaway container. Preserve their rows while
        // keeping previous unfinished tasks out of claimNext's global due-work query.
        execute("""
                UPDATE support_after_sale_execution
                SET available_at=DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 365 DAY),
                    lease_until=CASE WHEN status='PROCESSING'
                        THEN DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 365 DAY) ELSE lease_until END
                WHERE status IN ('PENDING','FAILED','PROCESSING')
                """);
    }

    @Test
    void shouldNeverCreateExecutionForPendingOrRejectedRequest() throws Exception {
        Fixture pending = submit(order(100L, 200L, "199.90"));
        assertThat(taskCount(pending)).isZero();
        assertThat(worker.runOnce()).isFalse();
        assertThat(refundCount(pending)).isZero();
        decide("reviewer", pending, "REJECT", true).andExpect(status().isOk());
        assertThat(taskCount(pending)).isZero();
        assertThat(worker.runOnce()).isFalse();
        assertThat(refundCount(pending)).isZero();
    }

    @Test
    void shouldAtomicallyApproveAndCreateOneDurableTaskWithExactSnapshot() throws Exception {
        Fixture approved = approve(order(100L, 200L, "1234567.89"));
        assertThat(saleStatus(approved)).isEqualTo("APPROVED");
        Task task = task(approved);
        assertThat(task.status()).isEqualTo("PENDING");
        assertThat(task.attempts()).isZero();
        assertThat(task.maxAttempts()).isEqualTo(5);
        assertThat(task.amount()).isEqualByComparingTo("1234567.89");
        assertThat(task.currency()).isEqualTo("CNY");
        assertThat(taskCount(approved)).isEqualTo(1);
        assertThat(refundCount(approved)).isZero();
        decide("reviewer", approved, "APPROVE", true).andExpect(status().isConflict());
        assertThat(taskCount(approved)).isEqualTo(1);
        assertThat(auditCount(approved)).isEqualTo(3);
    }

    @Test
    void shouldRollbackApprovalWhenEnqueuingTaskFails() throws Exception {
        Fixture pending = submit(order(100L, 200L, "199.90"));
        setPermission("support_after_sale_execution", "INSERT", false);
        try {
            decide("reviewer", pending, "APPROVE", true).andExpect(status().isInternalServerError());
            assertThat(saleStatus(pending)).isEqualTo("PENDING_REVIEW");
            assertThat(taskCount(pending)).isZero();
            assertThat(auditCount(pending)).isEqualTo(1);
        } finally {
            setPermission("support_after_sale_execution", "INSERT", true);
        }
    }

    @Test
    void shouldRollbackApprovalAndTaskWhenReviewAuditFails() throws Exception {
        Fixture pending = submit(order(100L, 200L, "199.90"));
        setPermission("support_after_sale_audit", "INSERT", false);
        try {
            decide("reviewer", pending, "APPROVE", true).andExpect(status().isInternalServerError());
            assertThat(saleStatus(pending)).isEqualTo("PENDING_REVIEW");
            assertThat(taskCount(pending)).isZero();
            assertThat(auditCount(pending)).isEqualTo(1);
        } finally {
            setPermission("support_after_sale_audit", "INSERT", true);
        }
    }

    @Test
    void shouldAllowTwoConcurrentWorkersToCreateOnlyOneRefundAndCompletion() throws Exception {
        Fixture approved = approve(order(100L, 200L, "199.90"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<Boolean> first = workerRun(ready, go);
        CompletableFuture<Boolean> second = workerRun(ready, go);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        assertThat(task(approved).status()).isEqualTo("SUCCEEDED");
        assertThat(task(approved).attempts()).isEqualTo(1);
        assertThat(refundCount(approved)).isEqualTo(1);
        assertThat(auditCount(approved)).isEqualTo(5);
    }

    @Test
    void shouldReclaimExpiredLeaseFromDatabaseAndRejectStaleCompletion() throws Exception {
        Fixture approved = approve(order(100L, 200L, "199.90"));
        ExecutionClaim oldClaim = processor.claimNext().orElseThrow();
        assertThat(task(approved).status()).isEqualTo("PROCESSING");
        execute("UPDATE support_after_sale_execution SET lease_until=DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE request_id=?",
                approved.requestId());
        ExecutionClaim recovered = processor.claimNext().orElseThrow();
        assertThat(task(approved).attempts()).isEqualTo(2);
        processor.complete(oldClaim);
        assertThat(refundCount(approved)).isZero();
        assertThat(task(approved).status()).isEqualTo("PROCESSING");
        long auditCount = auditCount(approved);
        processor.recordFailure(oldClaim, ExecutionFailureCode.TRANSIENT_FAILURE);
        assertThat(task(approved).status()).isEqualTo("PROCESSING");
        assertThat(auditCount(approved)).isEqualTo(auditCount);
        processor.complete(recovered);
        assertThat(task(approved).status()).isEqualTo("SUCCEEDED");
        assertThat(refundCount(approved)).isEqualTo(1);
    }

    @Test
    void shouldResumePersistedWorkFromARecreatedWorkerAfterItsLeaseExpires() throws Exception {
        Fixture approved = approve(order(100L, 200L, "199.90"));
        assertThat(processor.claimNext()).isPresent();
        execute("UPDATE support_after_sale_execution SET lease_until=DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE request_id=?",
                approved.requestId());
        // A new worker has no memory of the lost claim. The database alone supplies its work.
        AfterSaleExecutionWorker restarted = new AfterSaleExecutionWorker(processor);
        assertThat(restarted.runOnce()).isTrue();
        assertThat(task(approved).status()).isEqualTo("SUCCEEDED");
        assertThat(task(approved).attempts()).isEqualTo(2);
        assertThat(refundCount(approved)).isEqualTo(1);
    }

    @Test
    void shouldPersistExactRefundAmountAndIgnoreRepeatedCompletion() throws Exception {
        Fixture approved = approve(order(100L, 200L, "1234567.89"));
        ExecutionClaim claim = processor.claimNext().orElseThrow();
        processor.complete(claim);
        long auditCount = auditCount(approved);
        processor.complete(claim);
        assertThat(task(approved).status()).isEqualTo("SUCCEEDED");
        assertThat(refundCount(approved)).isEqualTo(1);
        assertThat(auditCount(approved)).isEqualTo(auditCount);
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement(
                "SELECT amount, currency FROM support_simulated_refund WHERE request_id=?")) {
            query.setString(1, approved.requestId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBigDecimal("amount")).isEqualByComparingTo("1234567.89");
                assertThat(rows.getString("currency")).isEqualTo("CNY");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void shouldRollbackClaimAndAttemptCounterWhenStartAuditFails() throws Exception {
        Fixture approved = approve(order(100L, 200L, "199.90"));
        setPermission("support_after_sale_audit", "INSERT", false);
        try {
            assertThatThrownBy(processor::claimNext).isInstanceOf(RuntimeException.class);
            assertThat(task(approved).status()).isEqualTo("PENDING");
            assertThat(task(approved).attempts()).isZero();
            assertThat(refundCount(approved)).isZero();
        } finally {
            setPermission("support_after_sale_audit", "INSERT", true);
        }
    }

    @Test
    void shouldRollbackRefundAndSuccessWhenCompletionAuditFails() throws Exception {
        Fixture approved = approve(order(100L, 200L, "199.90"));
        ExecutionClaim claim = processor.claimNext().orElseThrow();
        setPermission("support_after_sale_audit", "INSERT", false);
        try {
            assertThatThrownBy(() -> processor.complete(claim)).isInstanceOf(RuntimeException.class);
            assertThat(task(approved).status()).isEqualTo("PROCESSING");
            assertThat(refundCount(approved)).isZero();
            assertThat(auditCount(approved)).isEqualTo(4);
        } finally {
            setPermission("support_after_sale_audit", "INSERT", true);
        }
        processor.complete(claim);
        assertThat(task(approved).status()).isEqualTo("SUCCEEDED");
        assertThat(refundCount(approved)).isEqualTo(1);
        assertThat(auditCount(approved)).isEqualTo(5);
    }

    @Test
    void shouldBackOffTransientFailureAndStopAfterFiveTotalAttempts() throws Exception {
        Fixture approved = approve(order(100L, 200L, "199.90"));
        transientFailure();
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(worker.runOnce()).isTrue();
            Task failed = task(approved);
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.attempts()).isEqualTo(attempt);
            assertThat(refundCount(approved)).isZero();
            assertThat(worker.runOnce()).as("No immediate retry before backoff expires").isFalse();
            if (attempt < 5) {
                assertThat(count("SELECT COUNT(*) FROM support_after_sale_execution WHERE request_id=? AND available_at>UTC_TIMESTAMP(3)",
                        approved.requestId())).isEqualTo(1);
                makeDue(approved);
            }
        }
        makeDue(approved);
        assertThat(worker.runOnce()).isFalse();
        retry("reviewer", approved, true).andExpect(status().isConflict());
        assertThat(task(approved).attempts()).isEqualTo(5);
        assertThat(refundCount(approved)).isZero();
    }

    @Test
    void shouldExhaustFifthCrashedLeaseWithoutARefundOrSixthClaim() throws Exception {
        Fixture approved = approve(order(100L, 200L, "199.90"));
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(processor.claimNext()).isPresent();
            assertThat(task(approved).attempts()).isEqualTo(attempt);
            execute("UPDATE support_after_sale_execution SET lease_until=DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE request_id=?",
                    approved.requestId());
        }
        assertThat(processor.claimNext()).isEmpty();
        assertThat(task(approved).status()).isEqualTo("FAILED");
        assertThat(task(approved).attempts()).isEqualTo(5);
        assertThat(task(approved).lastErrorCode()).isEqualTo("ATTEMPTS_EXHAUSTED");
        assertThat(refundCount(approved)).isZero();
        retry("reviewer", approved, true).andExpect(status().isConflict());
    }

    @Test
    void shouldRequireAuthenticationReviewPermissionCsrfAndNonSelfIdentityForRetry() throws Exception {
        Fixture approved = approve(order(100L, 200L, "199.90"));
        transientFailure();
        assertThat(worker.runOnce()).isTrue();
        mvc.perform(post("/api/v1/merchant/shops/100/after-sales/{id}/execution/retry", approved.requestId()).with(csrf()))
                .andExpect(status().isUnauthorized());
        retry("buyer", approved, true).andExpect(status().isForbidden());
        retry("reviewer", approved, false).andExpect(status().isForbidden());
        retry("self-reviewer", approved, true).andExpect(status().isForbidden());
        assertThat(task(approved).status()).isEqualTo("FAILED");
        assertThat(task(approved).attempts()).isEqualTo(1);
        retry("reviewer", approved, true).andExpect(status().isOk());
        assertThat(task(approved).status()).isEqualTo("PENDING");
        assertThat(task(approved).attempts()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM support_after_sale_audit WHERE request_id=? AND action='EXECUTION_RETRY' AND staff_id=999",
                approved.requestId())).isEqualTo(1);
        retry("reviewer", approved, true).andExpect(status().isConflict());
        reset(gatewaySpy());
        assertThat(worker.runOnce()).isTrue();
        assertThat(task(approved).status()).isEqualTo("SUCCEEDED");
        assertThat(task(approved).attempts()).isEqualTo(2);
        assertThat(refundCount(approved)).isEqualTo(1);
    }

    @Test
    void shouldKeepTotalAttemptBudgetAcrossRepeatedAdministratorRetries() throws Exception {
        Fixture approved = approve(order(100L, 200L, "199.90"));
        transientFailure();
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(worker.runOnce()).isTrue();
            assertThat(task(approved).status()).isEqualTo("FAILED");
            assertThat(task(approved).attempts()).isEqualTo(attempt);
            if (attempt < 5) {
                retry("reviewer", approved, true).andExpect(status().isOk());
                assertThat(task(approved).status()).isEqualTo("PENDING");
                assertThat(task(approved).attempts()).isEqualTo(attempt);
            }
        }
        retry("reviewer", approved, true).andExpect(status().isConflict());
        assertThat(refundCount(approved)).isZero();
        assertThat(task(approved).attempts()).isEqualTo(5);
    }

    @Test
    void shouldRollbackManualRetryWhenItsAuditFails() throws Exception {
        Fixture approved = approve(order(100L, 200L, "199.90"));
        transientFailure();
        assertThat(worker.runOnce()).isTrue();
        long auditCount = auditCount(approved);
        setPermission("support_after_sale_audit", "INSERT", false);
        try {
            retry("reviewer", approved, true).andExpect(status().isInternalServerError());
            assertThat(task(approved).status()).isEqualTo("FAILED");
            assertThat(task(approved).attempts()).isEqualTo(1);
            assertThat(auditCount(approved)).isEqualTo(auditCount);
        } finally {
            setPermission("support_after_sale_audit", "INSERT", true);
        }
    }

    @Test
    void shouldRejectExecutionAndRetryAfterOrderBecomesIneligible() throws Exception {
        Fixture approved = approve(order(100L, 200L, "199.90"));
        execute("UPDATE support_order SET status='CANCELLED' WHERE id=?", approved.orderId());
        assertThat(worker.runOnce()).isTrue();
        assertThat(task(approved).status()).isEqualTo("FAILED");
        assertThat(task(approved).lastErrorCode()).isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(refundCount(approved)).isZero();
        retry("reviewer", approved, true).andExpect(status().isConflict());
    }

    @Test
    void shouldRejectChangedOrderAmountRatherThanRefundAnUnapprovedSnapshot() throws Exception {
        Fixture approved = approve(order(100L, 200L, "199.90"));
        execute("UPDATE support_order SET total_amount=200.00 WHERE id=?", approved.orderId());
        assertThat(worker.runOnce()).isTrue();
        assertThat(task(approved).status()).isEqualTo("FAILED");
        assertThat(task(approved).lastErrorCode()).isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(refundCount(approved)).isZero();
    }

    @Test
    void shouldExposeExecutionOnlyToItsBuyerAndShopAndAuthorizedReviewHistory() throws Exception {
        Fixture mine = approve(order(100L, 200L, "199.90"));
        assertThat(worker.runOnce()).isTrue();
        JsonNode owned = tickets("buyer", 100L);
        JsonNode found = find(owned, mine.requestId());
        assertThat(found.isMissingNode()).isFalse();
        assertThat(found.path("execution").path("status").asString()).isEqualTo("SUCCEEDED");
        for (String internal : List.of("buyerId", "orderId", "claimToken", "leaseUntil", "requestKey")) {
            assertThat(found.toString()).doesNotContain('"' + internal + '"');
        }
        assertThat(find(tickets("other", 100L), mine.requestId()).isMissingNode()).isTrue();
        assertThat(find(tickets("buyer", 101L), mine.requestId()).isMissingNode()).isTrue();
        mvc.perform(get("/api/v1/merchant/shops/100/after-sales/history").with(httpBasic("buyer", PASSWORD)))
                .andExpect(status().isForbidden());
        JsonNode history = body(mvc.perform(get("/api/v1/merchant/shops/100/after-sales/history")
                .with(httpBasic("reviewer", PASSWORD))).andExpect(status().isOk()).andReturn());
        assertThat(find(history, mine.requestId()).path("execution").path("status").asString())
                .isEqualTo("SUCCEEDED");
    }

    private void transientFailure() {
        doThrow(new IllegalStateException("Injected transient simulator failure"))
                .when(gatewaySpy()).refund(any(ExecutionClaim.class));
    }

    private SimulatedRefundGateway gatewaySpy() {
        // Stub the actual Mockito spy, not Spring's MANDATORY transaction proxy.
        return AopTestUtils.getUltimateTargetObject(gateway);
    }

    private CompletableFuture<Boolean> workerRun(CountDownLatch ready, CountDownLatch go) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Worker start timed out");
                return worker.runOnce();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
    }

    private Fixture order(long shopId, long buyerId, String amount) throws SQLException {
        String orderNo = "SFEXEC" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin(); PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO support_order
                (tenant_id, customer_id, buyer_id, order_no, status, total_amount, currency)
                VALUES (?, ?, ?, ?, 'PAID', ?, 'CNY')
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, shopId);
            insert.setLong(2, buyerId);
            insert.setLong(3, buyerId);
            insert.setString(4, orderNo);
            insert.setBigDecimal(5, new BigDecimal(amount));
            assertThat(insert.executeUpdate()).isEqualTo(1);
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return new Fixture(keys.getLong(1), shopId, orderNo, null);
            }
        }
    }

    private Fixture submit(Fixture order) throws Exception {
        mvc.perform(post("/api/v1/shops/{shopId}/chat/messages", order.shopId())
                .with(httpBasic("buyer", PASSWORD)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("message", APPLY + order.orderNo() + " damaged item"))))
                .andExpect(status().isOk());
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement(
                "SELECT id FROM support_after_sale WHERE order_id=?")) {
            query.setLong(1, order.orderId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return new Fixture(order.orderId(), order.shopId(), order.orderNo(), rows.getString(1));
            }
        }
    }

    private Fixture approve(Fixture order) throws Exception {
        Fixture request = submit(order);
        decide("reviewer", request, "APPROVE", true).andExpect(status().isOk());
        return request;
    }

    private ResultActions decide(String username, Fixture request, String decision, boolean withCsrf) throws Exception {
        var builder = post("/api/v1/merchant/shops/{shopId}/after-sales/{id}/decision", request.shopId(), request.requestId())
                .with(httpBasic(username, PASSWORD)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("decision", decision, "note", "Checked in isolated test")));
        return mvc.perform(withCsrf ? builder.with(csrf()) : builder);
    }

    private ResultActions retry(String username, Fixture request, boolean withCsrf) throws Exception {
        var builder = post("/api/v1/merchant/shops/{shopId}/after-sales/{id}/execution/retry", request.shopId(), request.requestId())
                .with(httpBasic(username, PASSWORD));
        return mvc.perform(withCsrf ? builder.with(csrf()) : builder);
    }

    private JsonNode tickets(String username, long shopId) throws Exception {
        return body(mvc.perform(get("/api/v1/shops/{shopId}/after-sales", shopId)
                .with(httpBasic(username, PASSWORD))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode find(JsonNode tickets, String id) {
        for (JsonNode ticket : tickets) if (ticket.path("id").asString().equals(id)) return ticket;
        return json.createObjectNode().path("missing");
    }

    private Task task(Fixture fixture) throws SQLException {
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement("""
                SELECT status, attempt_count, max_attempts, amount, currency, last_error_code
                FROM support_after_sale_execution WHERE request_id=?
                """)) {
            query.setString(1, fixture.requestId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                Task task = new Task(rows.getString("status"), rows.getInt("attempt_count"),
                        rows.getInt("max_attempts"), rows.getBigDecimal("amount"),
                        rows.getString("currency"), rows.getString("last_error_code"));
                assertThat(rows.next()).isFalse();
                return task;
            }
        }
    }

    private String saleStatus(Fixture fixture) throws SQLException {
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement(
                "SELECT status FROM support_after_sale WHERE id=?")) {
            query.setString(1, fixture.requestId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private long taskCount(Fixture fixture) throws SQLException {
        return count("SELECT COUNT(*) FROM support_after_sale_execution WHERE request_id=?", fixture.requestId());
    }

    private long refundCount(Fixture fixture) throws SQLException {
        return count("SELECT COUNT(*) FROM support_simulated_refund WHERE request_id=?", fixture.requestId());
    }

    private long auditCount(Fixture fixture) throws SQLException {
        return count("SELECT COUNT(*) FROM support_after_sale_audit WHERE request_id=?", fixture.requestId());
    }

    private long count(String sql, Object parameter) throws SQLException {
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setObject(1, parameter);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private void makeDue(Fixture fixture) throws SQLException {
        execute("UPDATE support_after_sale_execution SET available_at=DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE request_id=?",
                fixture.requestId());
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = admin(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            statement.executeUpdate();
        }
    }

    private void setPermission(String table, String privilege, boolean enabled) throws SQLException {
        if (!List.of("support_after_sale_audit", "support_after_sale_execution").contains(table)
                || !privilege.equals("INSERT")) throw new IllegalArgumentException("Unexpected test grant target");
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute((enabled ? "GRANT " : "REVOKE ") + privilege + " ON " + DATABASE + "." + table
                    + (enabled ? " TO " : " FROM ") + "'execution_runtime'@'%'");
        }
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private record Fixture(long orderId, long shopId, String orderNo, String requestId) { }
    private record Task(String status, int attempts, int maxAttempts, BigDecimal amount, String currency,
                        String lastErrorCode) { }
}
