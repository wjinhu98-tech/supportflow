package dev.supportflow.assistant;

import dev.supportflow.SupportFlowApplication;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.testsupport.MerchantFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actual MySQL, migrations, Java tools, security and HTTP contract in isolation.
 * No business database, developer secrets, model calls, deletes or shared fixtures.
 */
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
class AssistantWorkflowIT {

    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);
    private static final String DATABASE_PASSWORD = UUID.randomUUID().toString();

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName("supportflow_assistant_test")
            .withUsername("root")
            .withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withUrlParam("sslMode", "DISABLED")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private static boolean prepared;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    private MockMvc mvc;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        prepareDatabase();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "assistant_runtime");
        registry.add("spring.datasource.password", () -> DATABASE_PASSWORD);
        registry.add("supportflow.ai.deepseek.enabled", () -> false);
        account(registry, 0, "buyer", 200L, "ORDER_READ");
        account(registry, 1, "other", 201L, "ORDER_READ");
        account(registry, 2, "reviewer", 999L, "ORDER_READ,AFTER_SALE_REVIEW");
        account(registry, 3, "self-reviewer", 200L, "ORDER_READ,AFTER_SALE_REVIEW");
        account(registry, 4, "reviewer-two", 998L, "ORDER_READ,AFTER_SALE_REVIEW");
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
                .locations("classpath:db/migration").validateOnMigrate(true)
                .baselineOnMigrate(false).cleanDisabled(true).createSchemas(false).load().migrate();
        try (Connection connection = admin();
             PreparedStatement create = connection.prepareStatement(
                     "CREATE USER 'assistant_runtime'@'%' IDENTIFIED BY ?");
             Statement grant = connection.createStatement()) {
            create.setString(1, DATABASE_PASSWORD);
            create.execute();
            grant.execute("GRANT SELECT ON supportflow_assistant_test.support_order TO 'assistant_runtime'@'%'");
            grant.execute("GRANT SELECT ON supportflow_assistant_test.support_shipment_event TO 'assistant_runtime'@'%'");
            grant.execute("GRANT SELECT, INSERT, UPDATE ON supportflow_assistant_test.support_after_sale TO 'assistant_runtime'@'%'");
            grant.execute("GRANT SELECT, INSERT ON supportflow_assistant_test.support_after_sale_audit TO 'assistant_runtime'@'%'");
            grant.execute("GRANT SELECT, INSERT, UPDATE ON supportflow_assistant_test.support_after_sale_execution TO 'assistant_runtime'@'%'");
            grant.execute("GRANT SELECT, INSERT ON supportflow_assistant_test.support_simulated_refund TO 'assistant_runtime'@'%'");
            MerchantFixtures.grantRuntimeRead(connection, "supportflow_assistant_test", "assistant_runtime");
            MerchantFixtures.standardReviewers(connection);
        }
        prepared = true;
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void shouldAnswerAfterSaleProcessFromVersionedKnowledgeRatherThanWrite() throws Exception {
        JsonNode response = body(chat("buyer", 100L, "售后审批流程").andExpect(status().isOk()).andReturn());
        assertThat(response.get("type").asString()).isEqualTo("TEXT");
        assertThat(response.get("reply").asString()).contains("来源", "@", "演示环境");
    }

    @Test
    void shouldNotInventTrackingFromShippedOrderWithoutRecordedEvents() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.SHIPPED);
        JsonNode response = body(chat("buyer", 100L, "查物流 " + order.number())
                .andExpect(status().isOk()).andReturn());
        assertThat(response.get("reply").asString()).contains("暂无录入", "未连接真实快递查询服务")
                .doesNotContain("已签收", "运输中");
        assertThat(countCases(order)).isZero();
    }

    @Test
    void shouldReadActualRecordedLogisticsWithUtcAndSourceDisclosure() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.SHIPPED);
        shipment(order, "2026-10-20 02:03:04.123", "TEST-EVENT-PACKED");
        shipment(order, "2026-10-20 05:06:07.456", "TEST-EVENT-HANDOVER");
        String reply = body(chat("buyer", 100L, "查物流 " + order.number())
                .andExpect(status().isOk()).andReturn()).get("reply").asString();
        assertThat(reply).contains("本项目物流模块", "并非实时快递联网查询", "UTC")
                .containsSubsequence("2026-10-20T02:03:04.123Z", "TEST-EVENT-PACKED",
                        "2026-10-20T05:06:07.456Z", "TEST-EVENT-HANDOVER");
    }

    @Test
    void shouldHideOtherBuyerAndOtherShopLogisticsWithoutLeakingEvents() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.SHIPPED);
        shipment(order, "2026-10-20 02:03:04.123", "PRIVATE-EVENT-ONLY");
        for (ResultActions denied : List.of(chat("other", 100L, "查物流 " + order.number()),
                chat("buyer", 101L, "查物流 " + order.number()))) {
            String response = denied.andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("PRIVATE-EVENT-ONLY", "orderId", "buyerId");
        }
    }

    @Test
    void shouldPersistPendingRequestAndOneAuditAndReturnSameCaseForRepeat() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.PAID);
        String first = apply(order, "商品破损");
        Map<String, String> ticket = storedTicket(order);
        assertThat(first).contains(ticket.get("id"), "审核")
                .doesNotContain("Java", "SQL", "AI", "PENDING_REVIEW");
        assertThat(ticket.get("status")).isEqualTo("PENDING_REVIEW");
        assertThat(ticket.get("reason")).isEqualTo("商品破损");
        assertThat(countCases(order)).isEqualTo(1);
        assertThat(countAudit(ticket.get("id"))).isEqualTo(1);
        String repeated = apply(order, "再次提交的原因");
        assertThat(repeated).contains(ticket.get("id"));
        assertThat(storedTicket(order).get("reason")).isEqualTo("商品破损");
        assertThat(countCases(order)).isEqualTo(1);
        assertThat(countAudit(ticket.get("id"))).isEqualTo(1);
    }

    @Test
    void shouldAskForMissingReasonWithoutWritingAnyCase() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.PAID);
        String reply = body(chat("buyer", 100L, "申请售后 " + order.number())
                .andExpect(status().isOk()).andReturn()).get("reply").asString();
        assertThat(reply).contains("原因").doesNotContain("Java", "SQL");
        assertThat(countCases(order)).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CREATED", "CANCELLED"})
    void shouldRejectIneligibleOrderStatusBeforePersisting(OrderStatus initialStatus) throws Exception {
        Fixture order = order(100L, 200L, initialStatus);
        chat("buyer", 100L, "申请售后 " + order.number() + " 商品破损")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
        assertThat(countCases(order)).isZero();
    }

    @Test
    void shouldDenyOrdinaryBuyerAccessToReviewQueueAndDecisions() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.PAID);
        apply(order, "商品破损");
        String id = storedTicket(order).get("id");
        mvc.perform(get("/api/v1/merchant/shops/100/after-sales").with(httpBasic("buyer", PASSWORD)))
                .andExpect(status().isForbidden());
        decide("buyer", id, "APPROVE", true).andExpect(status().isForbidden());
        assertThat(storedTicket(order).get("status")).isEqualTo("PENDING_REVIEW");
        assertThat(countAudit(id)).isEqualTo(1);
    }

    @Test
    void shouldKeepCsrfProtectionForHumanReview() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.PAID);
        apply(order, "商品破损");
        String id = storedTicket(order).get("id");
        decide("reviewer", id, "APPROVE", false).andExpect(status().isForbidden());
        assertThat(storedTicket(order).get("status")).isEqualTo("PENDING_REVIEW");
        assertThat(countAudit(id)).isEqualTo(1);
    }

    @Test
    void shouldDenySelfReviewEvenWithReviewPermission() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.PAID);
        apply(order, "商品破损");
        String id = storedTicket(order).get("id");
        decide("self-reviewer", id, "APPROVE", true).andExpect(status().isForbidden());
        assertThat(storedTicket(order).get("status")).isEqualTo("PENDING_REVIEW");
        assertThat(countAudit(id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "REJECT"})
    void shouldReviewExactlyOnceAndExposeOnlyPublicTicketFields(String decision) throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.PAID);
        apply(order, "商品破损");
        String id = storedTicket(order).get("id");
        JsonNode pending = body(mvc.perform(get("/api/v1/merchant/shops/100/after-sales")
                .with(httpBasic("reviewer", PASSWORD))).andExpect(status().isOk()).andReturn());
        assertThat(findTicket(pending, id).isMissingNode()).isFalse();
        String finalStatus = decision.equals("APPROVE") ? "APPROVED" : "REJECTED";
        JsonNode reviewed = body(decide("reviewer", id, decision, true)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(finalStatus)).andReturn());
        assertPublicTicket(reviewed);
        assertThat(reviewed.get("reviewer").asString()).isEqualTo("reviewer");
        assertThat(storedTicket(order).get("status")).isEqualTo(finalStatus);
        assertThat(countAudit(id)).isEqualTo(decision.equals("APPROVE") ? 3 : 2);
        decide("reviewer", id, decision, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_CONFLICT"));
        assertThat(countAudit(id)).isEqualTo(decision.equals("APPROVE") ? 3 : 2);
    }

    @Test
    void shouldListOnlyAuthenticatedBuyerCasesInCurrentShop() throws Exception {
        Fixture mine = order(100L, 200L, OrderStatus.PAID);
        Fixture otherBuyer = order(100L, 201L, OrderStatus.PAID);
        Fixture otherShop = order(101L, 200L, OrderStatus.PAID);
        apply(mine, "商品破损");
        chat("other", 100L, "申请售后 " + otherBuyer.number() + " 商品破损").andExpect(status().isOk());
        chat("buyer", 101L, "申请售后 " + otherShop.number() + " 商品破损").andExpect(status().isOk());
        JsonNode response = body(mvc.perform(get("/api/v1/shops/100/after-sales")
                .with(httpBasic("buyer", PASSWORD))).andExpect(status().isOk()).andReturn());
        JsonNode ticket = findTicket(response, storedTicket(mine).get("id"));
        assertThat(ticket.isMissingNode()).isFalse();
        assertPublicTicket(ticket);
        for (JsonNode item : response) {
            assertThat(item.get("shopId").asLong()).isEqualTo(100L);
            assertThat(item.get("orderNo").asString()).isNotEqualTo(otherBuyer.number())
                    .isNotEqualTo(otherShop.number());
            assertPublicTicket(item);
        }
    }

    @Test
    void shouldDeduplicateConcurrentSubmissionAndAuditAtomically() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.PAID);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<String>> requests = List.of(
                concurrentApply(order, ready, go), concurrentApply(order, ready, go),
                concurrentApply(order, ready, go), concurrentApply(order, ready, go));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        String first = requests.get(0).get(30, TimeUnit.SECONDS);
        String second = requests.get(1).get(30, TimeUnit.SECONDS);
        String third = requests.get(2).get(30, TimeUnit.SECONDS);
        String fourth = requests.get(3).get(30, TimeUnit.SECONDS);
        String id = storedTicket(order).get("id");
        assertThat(first).contains(id);
        assertThat(second).contains(id);
        assertThat(third).contains(id);
        assertThat(fourth).contains(id);
        assertThat(countCases(order)).isEqualTo(1);
        assertThat(countAudit(id)).isEqualTo(1);
    }

    private CompletableFuture<String> concurrentApply(Fixture order, CountDownLatch ready, CountDownLatch go) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Submission start timed out");
                return apply(order, "商品破损");
            } catch (Exception failure) {
                throw new IllegalStateException("Isolated concurrent submission failed", failure);
            }
        });
    }

    @Test
    void shouldAllowOnlyOneOfTwoConcurrentHumanDecisionsAndOneReviewAudit() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.PAID);
        apply(order, "商品破损");
        String id = storedTicket(order).get("id");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<MvcResult> approval = concurrentDecision("reviewer", id, "APPROVE", ready, go);
        CompletableFuture<MvcResult> rejection = concurrentDecision("reviewer-two", id, "REJECT", ready, go);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<MvcResult> results = List.of(approval.get(30, TimeUnit.SECONDS), rejection.get(30, TimeUnit.SECONDS));
        assertThat(results.stream().map(result -> result.getResponse().getStatus()).toList())
                .containsExactlyInAnyOrder(200, 409);
        MvcResult accepted = results.stream().filter(result -> result.getResponse().getStatus() == 200)
                .findFirst().orElseThrow();
        MvcResult conflicted = results.stream().filter(result -> result.getResponse().getStatus() == 409)
                .findFirst().orElseThrow();
        JsonNode decision = body(accepted);
        assertThat(storedTicket(order).get("status")).isEqualTo(decision.get("status").asString());
        assertThat(decision.get("reviewer").asString()).isIn("reviewer", "reviewer-two");
        assertThat(body(conflicted).get("code").asString()).isEqualTo("REVIEW_CONFLICT");
        assertThat(countAudit(id)).isEqualTo(decision.get("status").asString().equals("APPROVED") ? 3 : 2);
        assertThat(count("SELECT COUNT(*) FROM support_after_sale_audit WHERE request_id=? AND action='SUBMITTED'", id))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM support_after_sale_audit WHERE request_id=? AND action IN ('APPROVED','REJECTED')", id))
                .isEqualTo(1);
    }

    @Test
    void shouldRollbackNewApplicationWhenAuditInsertFails() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.PAID);
        setAuditInsertPermission(false);
        try {
            chat("buyer", 100L, "申请售后 " + order.number() + " 商品破损")
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
            assertThat(countCases(order)).isZero();
        } finally {
            setAuditInsertPermission(true);
        }
        apply(order, "商品破损");
        assertThat(countCases(order)).isEqualTo(1);
        assertThat(countAudit(storedTicket(order).get("id"))).isEqualTo(1);
    }

    @Test
    void shouldRollbackHumanDecisionWhenAuditInsertFails() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.PAID);
        apply(order, "商品破损");
        String id = storedTicket(order).get("id");
        setAuditInsertPermission(false);
        try {
            decide("reviewer", id, "APPROVE", true).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
            assertThat(storedTicket(order).get("status")).isEqualTo("PENDING_REVIEW");
            assertThat(countAudit(id)).isEqualTo(1);
        } finally {
            setAuditInsertPermission(true);
        }
        decide("reviewer", id, "APPROVE", true).andExpect(status().isOk());
        assertThat(storedTicket(order).get("status")).isEqualTo("APPROVED");
        assertThat(countAudit(id)).isEqualTo(3);
    }

    @Test
    void shouldRejectApprovalAfterWaitingForConcurrentOrderCancellation() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.PAID);
        apply(order, "商品破损");
        String id = storedTicket(order).get("id");
        try (Connection cancellation = admin();
             PreparedStatement update = cancellation.prepareStatement(
                     "UPDATE support_order SET status='CANCELLED' WHERE id=?")) {
            cancellation.setAutoCommit(false);
            update.setLong(1, order.id());
            assertThat(update.executeUpdate()).isEqualTo(1);
            CompletableFuture<MvcResult> review = concurrentDecision("reviewer", id, "APPROVE",
                    new CountDownLatch(0), new CountDownLatch(0));
            try {
                // The ordinary snapshot can still see PAID; the current shared read must wait for this X lock.
                assertThat(awaitOrderReadLockWait()).as("Approval must wait for the current order row lock").isTrue();
                assertThat(review).isNotDone();
                cancellation.commit();
                MvcResult result = review.get(30, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(409);
                assertThat(body(result).get("code").asString()).isEqualTo("REVIEW_CONFLICT");
                assertThat(storedTicket(order).get("status")).isEqualTo("PENDING_REVIEW");
                assertThat(countAudit(id)).isEqualTo(1);
            } finally {
                cancellation.rollback();
                if (!review.isDone()) review.get(30, TimeUnit.SECONDS);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"申请退款", "将该订单退款", "我要退款"})
    void shouldOpenRefundReasonFormWithoutCreatingAnApplication(String message) throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.PAID);
        JsonNode response = body(mvc.perform(post("/api/v1/shops/100/chat/messages")
                        .with(httpBasic("buyer", PASSWORD)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("message", message, "orderNo", order.number()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("AFTER_SALE_FORM"))
                .andExpect(jsonPath("$.orders[0].orderNo").value(order.number())).andReturn());
        assertThat(response.get("reply").asString()).contains("原因").doesNotContain("Java", "SQL", "AI", "Tool");
        assertThat(countCases(order)).isZero();
    }

    @Test
    void shouldRequireOrderSelectionWhenOpeningRefundWithoutAnOrder() throws Exception {
        chat("buyer", 100L, "申请退款").andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("AFTER_SALE_FORM"))
                .andExpect(jsonPath("$.orders").isEmpty());
    }

    @Test
    void shouldReportTrustedReceiptAndFifteenDayDeadlineWithoutWriting() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.COMPLETED);
        java.time.Instant receipt = java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.DAYS)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        recordedReceipt(order, receipt);
        JsonNode result = body(eligibility("buyer", order).andExpect(status().isOk()).andReturn());
        assertThat(result.get("status").asString()).isEqualTo("ELIGIBLE");
        assertThat(result.get("canSubmit").asBoolean()).isTrue();
        assertThat(result.get("windowDays").asInt()).isEqualTo(15);
        assertThat(java.time.Instant.parse(result.get("receivedAt").asString())).isEqualTo(receipt);
        assertThat(java.time.Instant.parse(result.get("deadlineAt").asString())).isEqualTo(receipt.plus(15, java.time.temporal.ChronoUnit.DAYS));
        assertThat(countCases(order)).isZero();
    }

    @Test
    void shouldSubmitMissingReceiptForManualReviewWithAnImmutableSnapshot() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.COMPLETED);
        eligibility("buyer", order).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUIRES_REVIEW"))
                .andExpect(jsonPath("$.canSubmit").value(true))
                .andExpect(jsonPath("$.receivedAt").isEmpty()).andExpect(jsonPath("$.deadlineAt").isEmpty());
        JsonNode created = body(refundForm("buyer", order, Map.of("reason", "  实物破损，请核实  "), true)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.eligibility.status").value("REQUIRES_REVIEW")).andReturn());
        assertThat(created.get("reason").asString()).isEqualTo("实物破损，请核实");
        assertThat(created.get("eligibility").get("checkedAt").isString()).isTrue();
        assertPublicTicket(created);
        String id = created.get("id").asString();
        assertThat(countCases(order)).isEqualTo(1);
        assertThat(countAudit(id)).isEqualTo(1);
        JsonNode repeated = body(refundForm("buyer", order, Map.of("reason", "补充不同原因"), true)
                .andExpect(status().isOk()).andReturn());
        assertThat(repeated.get("id").asString()).isEqualTo(id);
        assertThat(repeated.get("reason").asString()).isEqualTo("实物破损，请核实");
        assertThat(repeated.get("eligibility")).isEqualTo(created.get("eligibility"));
        assertThat(countAudit(id)).isEqualTo(1);
    }

    @Test
    void shouldRejectExpiredApplicationsEvenWithForgedClientDatesOrTheLegacyChatCommand() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.COMPLETED);
        recordedReceipt(order, java.time.Instant.now().minus(16, java.time.temporal.ChronoUnit.DAYS));
        eligibility("buyer", order).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.canSubmit").value(false));
        refundForm("buyer", order, Map.of("reason", "商品破损", "receivedAt", "2999-01-01T00:00:00Z",
                        "deadlineAt", "2999-02-01T00:00:00Z", "canSubmit", true), true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
        chat("buyer", 100L, "申请售后 " + order.number() + " 商品破损")
                .andExpect(status().isConflict());
        assertThat(countCases(order)).isZero();
    }

    @Test
    void shouldNotTreatAnAbnormalFutureReceiptAsVerifiedEligibility() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.COMPLETED);
        recordedReceipt(order, java.time.Instant.now().plus(1, java.time.temporal.ChronoUnit.DAYS));
        eligibility("buyer", order).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUIRES_REVIEW"))
                .andExpect(jsonPath("$.canSubmit").value(true));
        assertThat(countCases(order)).isZero();
    }

    @Test
    void shouldValidateRefundReasonsAndKeepAuthenticationOwnershipAndCsrfBoundaries() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.PAID);
        for (String reason : List.of("", "   ", "原".repeat(501))) {
            refundForm("buyer", order, Map.of("reason", reason), true).andExpect(status().isBadRequest());
        }
        refundForm("buyer", order, Map.of("reason", "商品破损"), false).andExpect(status().isForbidden());
        refundForm("other", order, Map.of("reason", "商品破损"), true).andExpect(status().isNotFound());
        eligibility("other", order).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/shops/101/orders/{orderNo}/after-sale-eligibility", order.number())
                .with(httpBasic("buyer", PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/shops/100/orders/{orderNo}/after-sale-eligibility", order.number()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/shops/100/orders/{orderNo}/after-sales", order.number())
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"商品破损\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(countCases(order)).isZero();
    }

    @Test
    void shouldHonorTimelySubmissionWhenTheReviewerActsAfterItsSavedDeadline() throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.COMPLETED);
        recordedReceipt(order, java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS));
        JsonNode original = body(refundForm("buyer", order, Map.of("reason", "商品破损"), true)
                .andExpect(status().isOk()).andReturn());
        String id = original.get("id").asString();
        // Move the isolated fixture's ENTIRE timeline back consistently: application day10,
        // deadline day5, review today. This represents a legitimately timely historical case.
        try (Connection connection = admin(); Statement sql = connection.createStatement()) {
            sql.executeUpdate("UPDATE support_order SET received_at=UTC_TIMESTAMP(3)-INTERVAL 20 DAY WHERE id=" + order.id());
            try (PreparedStatement shift = connection.prepareStatement("""
                    UPDATE support_after_sale SET
                      eligibility_received_at=UTC_TIMESTAMP(3)-INTERVAL 20 DAY,
                      eligibility_deadline_at=UTC_TIMESTAMP(3)-INTERVAL 5 DAY,
                      eligibility_checked_at=UTC_TIMESTAMP(3)-INTERVAL 10 DAY,
                      created_at=UTC_TIMESTAMP(3)-INTERVAL 10 DAY
                    WHERE id=?
                    """)) {
                shift.setString(1, id);
                assertThat(shift.executeUpdate()).isEqualTo(1);
            }
        }
        eligibility("buyer", order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
        JsonNode repeated = body(refundForm("buyer", order, Map.of("reason", "网络重试"), true)
                .andExpect(status().isOk()).andReturn());
        assertThat(repeated.get("id").asString()).isEqualTo(id);
        assertThat(repeated.get("eligibility").get("status").asString()).isEqualTo("ELIGIBLE");
        decide("reviewer", id, "APPROVE", true).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(countCases(order)).isEqualTo(1);
    }

    @Test
    void shouldReturnConcurrentlyCommittedApplicationWhenItsSnapshotMissesItAndCurrentReceiptIsExpired()
            throws Exception {
        Fixture order = order(100L, 200L, OrderStatus.COMPLETED);
        recordedReceipt(order, java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS));
        String originalId = UUID.randomUUID().toString();
        String originalReason = "Previously submitted reason";
        try (Connection concurrent = admin();
             PreparedStatement correctReceipt = concurrent.prepareStatement(
                     "UPDATE support_order SET received_at=UTC_TIMESTAMP(3)-INTERVAL 16 DAY WHERE id=?")) {
            concurrent.setAutoCommit(false);
            correctReceipt.setLong(1, order.id());
            assertThat(correctReceipt.executeUpdate()).isEqualTo(1);
            CompletableFuture<MvcResult> retry = CompletableFuture.supplyAsync(() -> {
                try {
                    return refundForm("buyer", order, Map.of("reason", "Retried submission reason"), true)
                            .andReturn();
                } catch (Exception failure) {
                    throw new IllegalStateException("Isolated concurrent refund retry failed", failure);
                }
            });
            try {
                // The request has already read an RR snapshot with no case; its current order read is blocked.
                assertThat(awaitOrderReadLockWait()).as("Retry must wait for the current order row lock").isTrue();
                assertThat(retry).isNotDone();
                // Publish a legitimately timely historical case after that snapshot, alongside the corrected
                // receipt. Only a current read can now find this case before rejecting the expired order.
                try (PreparedStatement insert = concurrent.prepareStatement("""
                        INSERT INTO support_after_sale
                          (id, request_key, buyer_id, tenant_id, order_id, order_no, reason, status,
                           created_at, eligibility_status, eligibility_can_submit, eligibility_message,
                           eligibility_received_at, eligibility_deadline_at, eligibility_checked_at,
                           eligibility_window_days)
                        VALUES (?, SHA2(?, 256), 200, ?, ?, ?, ?, 'PENDING_REVIEW',
                                UTC_TIMESTAMP(3)-INTERVAL 2 DAY, 'ELIGIBLE', TRUE, 'Eligible at submission',
                                UTC_TIMESTAMP(3)-INTERVAL 16 DAY, UTC_TIMESTAMP(3)-INTERVAL 1 DAY,
                                UTC_TIMESTAMP(3)-INTERVAL 2 DAY, 15)
                        """);
                     PreparedStatement audit = concurrent.prepareStatement("""
                        INSERT INTO support_after_sale_audit (request_id, actor, action, notes, created_at)
                        VALUES (?, 'buyer', 'SUBMITTED', 'Original submission', UTC_TIMESTAMP(3)-INTERVAL 2 DAY)
                        """)) {
                    insert.setString(1, originalId);
                    insert.setString(2, "200:" + order.shopId() + ":" + order.id());
                    insert.setLong(3, order.shopId());
                    insert.setLong(4, order.id());
                    insert.setString(5, order.number());
                    insert.setString(6, originalReason);
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                    audit.setString(1, originalId);
                    assertThat(audit.executeUpdate()).isEqualTo(1);
                }
                concurrent.commit();
                MvcResult response = retry.get(30, TimeUnit.SECONDS);
                assertThat(response.getResponse().getStatus()).isEqualTo(200);
                JsonNode returned = body(response);
                assertThat(returned.get("id").asString()).isEqualTo(originalId);
                assertThat(returned.get("reason").asString()).isEqualTo(originalReason);
                assertThat(returned.get("eligibility").get("status").asString()).isEqualTo("ELIGIBLE");
                eligibility("buyer", order).andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("EXPIRED"));
                assertThat(countCases(order)).isEqualTo(1);
                assertThat(countAudit(originalId)).isEqualTo(1);
            } finally {
                concurrent.rollback();
                if (!retry.isDone()) retry.get(30, TimeUnit.SECONDS);
            }
        }
    }

    private ResultActions eligibility(String username, Fixture order) throws Exception {
        return mvc.perform(get("/api/v1/shops/{shopId}/orders/{orderNo}/after-sale-eligibility", order.shopId(), order.number())
                .with(httpBasic(username, PASSWORD)));
    }

    private ResultActions refundForm(String username, Fixture order, Map<String, ?> values, boolean withCsrf) throws Exception {
        var request = post("/api/v1/shops/{shopId}/orders/{orderNo}/after-sales", order.shopId(), order.number())
                .with(httpBasic(username, PASSWORD)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(values));
        return mvc.perform(withCsrf ? request.with(csrf()) : request);
    }

    private void recordedReceipt(Fixture order, java.time.Instant receivedAt) throws SQLException {
        try (Connection connection = admin(); PreparedStatement update = connection.prepareStatement(
                "UPDATE support_order SET received_at=? WHERE id=?")) {
            update.setObject(1, java.time.LocalDateTime.ofInstant(receivedAt, java.time.ZoneOffset.UTC));
            update.setLong(2, order.id());
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    private CompletableFuture<MvcResult> concurrentDecision(String username, String id, String decision,
                                                            CountDownLatch ready, CountDownLatch go) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Review start timed out");
                return decide(username, id, decision, true).andReturn();
            } catch (Exception failure) {
                throw new IllegalStateException("Isolated concurrent review failed", failure);
            }
        });
    }

    private void setAuditInsertPermission(boolean enabled) throws SQLException {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute(enabled
                    ? "GRANT INSERT ON supportflow_assistant_test.support_after_sale_audit TO 'assistant_runtime'@'%'"
                    : "REVOKE INSERT ON supportflow_assistant_test.support_after_sale_audit FROM 'assistant_runtime'@'%'");
        }
    }

    private boolean awaitOrderReadLockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits waits
                JOIN performance_schema.data_locks requested
                  ON requested.ENGINE_LOCK_ID=waits.REQUESTING_ENGINE_LOCK_ID
                WHERE requested.OBJECT_SCHEMA='supportflow_assistant_test'
                  AND requested.OBJECT_NAME='support_order' AND requested.LOCK_STATUS='WAITING'
                """)) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    if (rows.getLong(1) > 0) return true;
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        return false;
    }

    private ResultActions chat(String username, long shopId, String message) throws Exception {
        return mvc.perform(post("/api/v1/shops/{shopId}/chat/messages", shopId)
                .with(httpBasic(username, PASSWORD)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("message", message))));
    }

    private String apply(Fixture order, String reason) throws Exception {
        return body(chat("buyer", order.shopId(), "申请售后 " + order.number() + " " + reason)
                .andExpect(status().isOk()).andReturn()).get("reply").asString();
    }

    private ResultActions decide(String username, String id, String decision, boolean withCsrf) throws Exception {
        var request = post("/api/v1/merchant/shops/100/after-sales/{id}/decision", id)
                .with(httpBasic(username, PASSWORD)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("decision", decision, "note", "人工核对完成")));
        return mvc.perform(withCsrf ? request.with(csrf()) : request);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode findTicket(JsonNode tickets, String id) {
        for (JsonNode ticket : tickets) if (ticket.get("id").asString().equals(id)) return ticket;
        return json.createObjectNode().path("missing");
    }

    private void assertPublicTicket(JsonNode ticket) {
        for (String privateField : List.of("buyerId", "orderId", "requestKey", "version", "tenantId")) {
            assertThat(ticket.has(privateField)).as("Private field %s must not be serialized", privateField).isFalse();
        }
    }

    private Fixture order(long shopId, long buyerId, OrderStatus state) throws SQLException {
        String number = "SFTEST" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO support_order
                     (tenant_id, customer_id, buyer_id, order_no, status, total_amount, currency)
                     VALUES (?, ?, ?, ?, ?, ?, 'CNY')
                     """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, shopId);
            insert.setLong(2, buyerId);
            insert.setLong(3, buyerId);
            insert.setString(4, number);
            insert.setString(5, state.name());
            insert.setBigDecimal(6, new BigDecimal("199.90"));
            assertThat(insert.executeUpdate()).isEqualTo(1);
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return new Fixture(keys.getLong(1), shopId, number);
            }
        }
    }

    private void shipment(Fixture order, String occurredAt, String description) throws SQLException {
        try (Connection connection = admin();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO support_shipment_event (order_id, occurred_at, description) VALUES (?, ?, ?)
                     """)) {
            insert.setLong(1, order.id());
            insert.setString(2, occurredAt);
            insert.setString(3, description);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
    }

    private Map<String, String> storedTicket(Fixture order) throws SQLException {
        try (Connection connection = admin();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT id, status, reason FROM support_after_sale WHERE order_id=?")) {
            query.setLong(1, order.id());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                Map<String, String> result = Map.of("id", rows.getString("id"),
                        "status", rows.getString("status"), "reason", rows.getString("reason"));
                assertThat(rows.next()).isFalse();
                return result;
            }
        }
    }

    private long countCases(Fixture order) throws SQLException {
        return count("SELECT COUNT(*) FROM support_after_sale WHERE order_id=?", order.id());
    }

    private long countAudit(String requestId) throws SQLException {
        return count("SELECT COUNT(*) FROM support_after_sale_audit WHERE request_id=?", requestId);
    }

    private long count(String sql, Object value) throws SQLException {
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setObject(1, value);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private record Fixture(long id, long shopId, String number) {
    }
}
