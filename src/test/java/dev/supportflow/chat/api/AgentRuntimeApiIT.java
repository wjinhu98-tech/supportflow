package dev.supportflow.chat.api;

import dev.supportflow.SupportFlowApplication;
import dev.supportflow.assistant.planning.ToolName;
import dev.supportflow.assistant.planning.ToolPlanner;
import dev.supportflow.assistant.tool.LogisticsTool;
import dev.supportflow.chat.api.response.ChatResponse;
import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.chat.conversation.ConversationException;
import dev.supportflow.chat.conversation.ConversationMessageRequest;
import dev.supportflow.chat.conversation.ConversationStore;
import dev.supportflow.testsupport.KnowledgeFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real security, MySQL checkpoints and explicit recovery; no daily data or external model calls. */
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
class AgentRuntimeApiIT {
    private static final String DATABASE = "supportflow_agent_api_test";
    private static final String RUNTIME = "agent_api_runtime";
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);
    private static final String DATABASE_PASSWORD = UUID.randomUUID().toString();
    private static final AtomicLong SHOPS = new AtomicLong(40_000);

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName(DATABASE)
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
    @MockitoSpyBean private ToolPlanner planner;
    @MockitoSpyBean private LogisticsTool logistics;
    @MockitoSpyBean private ConversationStore conversations;
    private MockMvc mvc;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        prepareDatabase();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME);
        registry.add("spring.datasource.password", () -> DATABASE_PASSWORD);
        account(registry, 0, "buyer", 200, "ORDER_READ", "BUYER");
        account(registry, 1, "other", 201, "ORDER_READ", "BUYER");
        account(registry, 2, "no-order-permission", 200, "", "BUYER");
        account(registry, 3, "merchant", 900, "ORDER_READ,AFTER_SALE_REVIEW", "MERCHANT_STAFF");
    }

    private static void account(DynamicPropertyRegistry registry, int index, String username,
                                long buyerId, String permissions, String type) {
        String prefix = "supportflow.security.local.accounts[" + index + "].";
        registry.add(prefix + "username", () -> username);
        registry.add(prefix + "password-hash", () -> HASH);
        registry.add(prefix + "identity.buyer-id", () -> buyerId);
        registry.add(prefix + "permissions", () -> permissions);
        registry.add(prefix + "account-type", () -> type);
    }

    private static synchronized void prepareDatabase() throws SQLException {
        if (prepared) return;
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").validateOnMigrate(true)
                .baselineOnMigrate(false).cleanDisabled(true).createSchemas(false).load().migrate();
        try (Connection connection = admin();
             PreparedStatement create = connection.prepareStatement(
                     "CREATE USER 'agent_api_runtime'@'%' IDENTIFIED BY ?");
             Statement grants = connection.createStatement()) {
            create.setString(1, DATABASE_PASSWORD);
            create.execute();
            KnowledgeFixtures.grantRead(connection, DATABASE, RUNTIME);
            for (String table : List.of("support_order", "support_shipment_event")) {
                grants.execute("GRANT SELECT ON " + DATABASE + "." + table + " TO '" + RUNTIME + "'@'%'");
            }
            for (String table : List.of("support_chat_conversation", "support_chat_turn",
                    "support_agent_run", "support_agent_step")) {
                grants.execute("GRANT SELECT, INSERT, UPDATE ON " + DATABASE + "." + table
                        + " TO '" + RUNTIME + "'@'%'");
            }
            grants.execute("GRANT SELECT, INSERT ON " + DATABASE + ".support_chat_message TO '" + RUNTIME + "'@'%'");
        }
        prepared = true;
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void shouldPersistThreeCheckpointsAndKeepTheExistingConversationResponseAndReplayContract() throws Exception {
        long shop = SHOPS.incrementAndGet();
        Fixture order = order(shop, 200);
        String conversation = create(shop);
        String message = UUID.randomUUID().toString();
        JsonNode response = body(send("buyer", shop, conversation, message, "查物流", order.number())
                .andExpect(status().isOk()).andReturn());
        assertCompletedTurn(response, order.number());
        JsonNode run = view("buyer", shop, conversation, message);
        assertThat(UUID.fromString(run.path("runId").asString())).isNotNull();
        assertThat(run.path("clientMessageId").asString()).isEqualTo(message);
        assertThat(run.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.path("phase").asString()).isEqualTo("COMPLETE");
        assertThat(run.path("attempts").asInt()).isEqualTo(3);
        assertThat(run.path("maxSteps").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(Instant.parse(run.path("deadlineAt").asString())).isAfter(Instant.now().minusSeconds(120));
        assertThat(run.path("resumable").asBoolean()).isFalse();
        assertThat(phases(run.path("steps"))).containsExactly("PLAN", "ORDER_LOOKUP", "LOGISTICS_LOOKUP");
        for (JsonNode step : run.path("steps")) {
            assertThat(step.path("status").asString()).isEqualTo("SUCCEEDED");
            assertThat(UUID.fromString(step.path("operationId").asString())).isNotNull();
            assertThat(Instant.parse(step.path("completedAt").asString())).isNotNull();
        }
        assertPrivateFieldsAbsent(run);
        JsonNode replay = body(send("buyer", shop, conversation, message, "查物流", order.number())
                .andExpect(status().isOk()).andReturn());
        assertThat(replay).isEqualTo(response);
        assertThat(view("buyer", shop, conversation, message)).isEqualTo(run);
        assertThat(messageCount(conversation)).isEqualTo(2);
        verify(planner, times(1)).plan(eq(ToolName.LOGISTICS_LOOKUP), anyString(), eq(order.number()));
        verify(logistics, times(1)).lookup(argThat(value -> value.orderNo().equals(order.number())));
    }

    @Test
    void shouldScopeRunProgressAndRecoveryToTheAuthenticatedBuyerAndOriginalConversation() throws Exception {
        long shop = SHOPS.incrementAndGet();
        Fixture order = order(shop, 200);
        String conversation = create(shop);
        String message = UUID.randomUUID().toString();
        send("buyer", shop, conversation, message, "查物流", order.number()).andExpect(status().isOk());
        String url = runUrl(shop, conversation, message);
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(post(url + "/resume").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(url + "/resume").with(httpBasic("buyer", PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(post(url + "/resume").with(httpBasic("buyer", PASSWORD)).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        mvc.perform(get(url).with(httpBasic("merchant", PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(post(url + "/resume").with(httpBasic("merchant", PASSWORD)).with(csrf()))
                .andExpect(status().isForbidden());
        // Matching buyer identity does not replace the current order-read permission.
        mvc.perform(get(url).with(httpBasic("no-order-permission", PASSWORD)))
                .andExpect(status().isForbidden());
        mvc.perform(post(url + "/resume").with(httpBasic("no-order-permission", PASSWORD)).with(csrf()))
                .andExpect(status().isForbidden());
        for (String inaccessible : List.of(
                runUrl(shop + 100_000, conversation, message),
                runUrl(shop, UUID.randomUUID().toString(), message),
                runUrl(shop, conversation, UUID.randomUUID().toString()))) {
            mvc.perform(get(inaccessible).with(httpBasic("buyer", PASSWORD))).andExpect(status().isNotFound());
            mvc.perform(post(inaccessible + "/resume").with(httpBasic("buyer", PASSWORD)).with(csrf()))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get(url).with(httpBasic("other", PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(post(url + "/resume").with(httpBasic("other", PASSWORD)).with(csrf()))
                .andExpect(status().isNotFound());
        for (String invalid : List.of("not-a-uuid", "1-1-1-1-1")) {
            mvc.perform(get(runUrl(shop, conversation, invalid)).with(httpBasic("buyer", PASSWORD)))
                    .andExpect(status().isBadRequest());
            mvc.perform(post(runUrl(shop, conversation, invalid) + "/resume")
                            .with(httpBasic("buyer", PASSWORD)).with(csrf()))
                    .andExpect(status().isBadRequest());
        }
        assertThat(messageCount(conversation)).isEqualTo(2);
        verify(logistics, times(1)).lookup(argThat(value -> value.orderNo().equals(order.number())));
    }

    @Test
    void shouldResumeOnlyTheFailedReadStepAndPersistTheTurnExactlyOnce() throws Exception {
        long shop = SHOPS.incrementAndGet();
        Fixture order = order(shop, 200);
        String conversation = create(shop);
        String message = UUID.randomUUID().toString();
        failNextLogisticsCall(order);
        MvcResult failed = send("buyer", shop, conversation, message, "查物流", order.number())
                .andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(failed.getResponse().getContentAsString()).doesNotContain("INJECTED_PRIVATE_FAILURE");
        JsonNode paused = view("buyer", shop, conversation, message);
        assertThat(paused.path("status").asString()).isEqualTo("PAUSED");
        assertThat(paused.path("phase").asString()).isEqualTo("LOGISTICS_LOOKUP");
        assertThat(paused.path("resumable").asBoolean()).isTrue();
        assertThat(paused.path("attempts").asInt()).isEqualTo(3);
        assertThat(messageCount(conversation)).isZero();
        send("buyer", shop, conversation, message, "查物流", order.number()).andExpect(status().isConflict());

        JsonNode completed = body(resume("buyer", shop, conversation, message)
                .andExpect(status().isOk()).andReturn());
        assertCompletedTurn(completed, order.number());
        JsonNode recovered = view("buyer", shop, conversation, message);
        assertThat(recovered.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(recovered.path("attempts").asInt()).isEqualTo(4);
        assertThat(recovered.path("deadlineAt")).isEqualTo(paused.path("deadlineAt"));
        assertThat(phases(recovered.path("steps")))
                .containsExactly("PLAN", "ORDER_LOOKUP", "LOGISTICS_LOOKUP", "LOGISTICS_LOOKUP");
        JsonNode replay = body(resume("buyer", shop, conversation, message)
                .andExpect(status().isOk()).andReturn());
        assertThat(replay).isEqualTo(completed);
        assertThat(messageCount(conversation)).isEqualTo(2);
        verify(planner, times(1)).plan(eq(ToolName.LOGISTICS_LOOKUP), anyString(), eq(order.number()));
        verify(logistics, times(2)).lookup(argThat(value -> value.orderNo().equals(order.number())));
        assertPrivateFieldsAbsent(recovered);
    }

    @Test
    void shouldFinalizeASavedRunWithoutRepeatingToolsWhenTheConversationCommitFailed() throws Exception {
        long shop = SHOPS.incrementAndGet();
        Fixture order = order(shop, 200);
        String conversation = create(shop);
        String message = UUID.randomUUID().toString();
        UUID conversationId = UUID.fromString(conversation);
        UUID clientMessageId = UUID.fromString(message);
        doThrow(new DataAccessResourceFailureException("INJECTED_TURN_STORAGE_FAILURE"))
                .doCallRealMethod().when(conversations).succeed(eq(200L), eq(shop), eq(conversationId),
                        argThat(request -> request != null && request.clientMessageId().equals(clientMessageId)),
                        any(ChatResponse.class), any(UUID.class));

        MvcResult failed = send("buyer", shop, conversation, message, "查物流", order.number())
                .andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(failed.getResponse().getContentAsString()).doesNotContain("INJECTED_TURN_STORAGE_FAILURE");
        JsonNode saved = view("buyer", shop, conversation, message);
        assertThat(saved.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(saved.path("phase").asString()).isEqualTo("COMPLETE");
        assertThat(saved.path("attempts").asInt()).isEqualTo(3);
        assertThat(saved.path("resumable").asBoolean()).isTrue();
        assertThat(messageCount(conversation)).isZero();
        send("buyer", shop, conversation, message, "查物流", order.number()).andExpect(status().isConflict());

        JsonNode completed = body(resume("buyer", shop, conversation, message)
                .andExpect(status().isOk()).andReturn());
        assertCompletedTurn(completed, order.number());
        JsonNode finalized = view("buyer", shop, conversation, message);
        assertThat(finalized.path("attempts").asInt()).isEqualTo(3);
        assertThat(finalized.path("steps")).isEqualTo(saved.path("steps"));
        assertThat(finalized.path("resumable").asBoolean()).isFalse();
        assertThat(body(resume("buyer", shop, conversation, message).andExpect(status().isOk()).andReturn()))
                .isEqualTo(completed);
        assertThat(messageCount(conversation)).isEqualTo(2);
        verify(planner, times(1)).plan(eq(ToolName.LOGISTICS_LOOKUP), anyString(), eq(order.number()));
        verify(logistics, times(1)).lookup(argThat(value -> value.orderNo().equals(order.number())));
    }

    @Test
    void shouldRejectRecoveryAfterANewerTurnHasCompletedInsteadOfReplacingItsTopic() throws Exception {
        long shop = SHOPS.incrementAndGet();
        Fixture order = order(shop, 200);
        String conversation = create(shop);
        String message = UUID.randomUUID().toString();
        failNextLogisticsCall(order);
        send("buyer", shop, conversation, message, "查物流", order.number())
                .andExpect(status().isServiceUnavailable());
        send("buyer", shop, conversation, UUID.randomUUID().toString(), "你好", null).andExpect(status().isOk());
        resume("buyer", shop, conversation, message).andExpect(status().isConflict());
        assertThat(messageCount(conversation)).isEqualTo(2);
        verify(logistics, times(1)).lookup(argThat(value -> value.orderNo().equals(order.number())));
        verify(planner, times(1)).plan(eq(ToolName.LOGISTICS_LOOKUP), anyString(), eq(order.number()));
    }

    @Test
    void shouldRecheckCurrentOrderOwnershipBeforeResumingSavedReadSteps() throws Exception {
        long shop = SHOPS.incrementAndGet();
        Fixture order = order(shop, 200);
        String conversation = create(shop);
        String message = UUID.randomUUID().toString();
        failNextLogisticsCall(order);
        send("buyer", shop, conversation, message, "查物流", order.number())
                .andExpect(status().isServiceUnavailable());
        try (Connection connection = admin(); PreparedStatement transfer = connection.prepareStatement(
                "UPDATE support_order SET buyer_id=201 WHERE id=?")) {
            transfer.setLong(1, order.id());
            assertThat(transfer.executeUpdate()).isEqualTo(1);
        }
        resume("buyer", shop, conversation, message).andExpect(status().isNotFound());
        assertThat(messageCount(conversation)).isZero();
        verify(logistics, times(1)).lookup(argThat(value -> value.orderNo().equals(order.number())));
    }

    @Test
    void shouldKeepOrdinaryChatAndRefundFormsOutsideTheReadOnlyRecoveryProtocol() throws Exception {
        long shop = SHOPS.incrementAndGet();
        String conversation = create(shop);
        for (String text : List.of("你好", "申请退款")) {
            String message = UUID.randomUUID().toString();
            send("buyer", shop, conversation, message, text, null).andExpect(status().isOk());
            mvc.perform(get(runUrl(shop, conversation, message)).with(httpBasic("buyer", PASSWORD)))
                    .andExpect(status().isNotFound());
            resume("buyer", shop, conversation, message).andExpect(status().isNotFound());
        }
        assertThat(messageCount(conversation)).isEqualTo(4);
    }

    @Test
    void staleTurnTokensCannotFailOrCompleteTheNewRecoveryGeneration() throws Exception {
        long shop = SHOPS.incrementAndGet();
        String conversation = create(shop);
        UUID conversationId = UUID.fromString(conversation);
        ConversationMessageRequest request = new ConversationMessageRequest(UUID.randomUUID(), "fencing-only", null);
        var original = conversations.reserve(200, shop, conversationId, request);
        assertThat(original.processingToken()).isNotNull();
        conversations.fail(200, shop, conversationId, request.clientMessageId(), original.processingToken());
        var recovery = conversations.reserveResume(200, shop, conversationId, request);
        assertThat(recovery.processingToken()).isNotNull().isNotEqualTo(original.processingToken());

        conversations.fail(200, shop, conversationId, request.clientMessageId(), original.processingToken());
        // Compatibility methods with no token must also fail closed for a newly fenced turn.
        conversations.fail(200, shop, conversationId, request.clientMessageId());
        ChatResponse response = ChatResponse.from(ChatReply.text("只由当前恢复执行者保存的回复"));
        assertThatThrownBy(() -> conversations.succeed(200, shop, conversationId, request, response,
                original.processingToken()))
                .isInstanceOf(ConversationException.class)
                .extracting(failure -> ((ConversationException) failure).status()).isEqualTo(HttpStatus.CONFLICT);
        assertThatThrownBy(() -> conversations.succeed(200, shop, conversationId, request, response))
                .isInstanceOf(ConversationException.class);
        assertThat(messageCount(conversation)).isZero();
        assertThat(conversations.completed(200, shop, conversationId, request.clientMessageId())).isFalse();

        var completed = conversations.succeed(200, shop, conversationId, request,
                response, recovery.processingToken());
        assertThat(completed.messages()).hasSize(2);
        assertThat(completed.messages().getLast().text())
                .isEqualTo("只由当前恢复执行者保存的回复");
        assertThat(conversations.completed(200, shop, conversationId, request.clientMessageId())).isTrue();
        assertThat(messageCount(conversation)).isEqualTo(2);
    }

    private void failNextLogisticsCall(Fixture order) {
        doThrow(new DataAccessResourceFailureException("INJECTED_PRIVATE_FAILURE"))
                .doCallRealMethod().when(logistics)
                .lookup(argThat(value -> value != null && value.orderNo().equals(order.number())));
    }

    private void assertCompletedTurn(JsonNode response, String order) {
        assertThat(response.path("messages").size()).isEqualTo(2);
        assertThat(response.path("messages").get(0).path("role").asString()).isEqualTo("USER");
        assertThat(response.path("messages").get(1).path("role").asString()).isEqualTo("ASSISTANT");
        assertThat(response.path("messages").get(1).path("response").path("reply").asString())
                .contains(order, "已录入的测试物流");
    }

    private JsonNode view(String account, long shop, String conversation, String message) throws Exception {
        return body(mvc.perform(get(runUrl(shop, conversation, message)).with(httpBasic(account, PASSWORD)))
                .andExpect(status().isOk()).andReturn());
    }

    private String create(long shop) throws Exception {
        return body(mvc.perform(post(conversationsUrl(shop)).with(httpBasic("buyer", PASSWORD)).with(csrf()))
                .andExpect(status().isCreated()).andReturn()).path("id").asString();
    }

    private ResultActions send(String account, long shop, String conversation, String message,
                               String text, String orderNo) throws Exception {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("clientMessageId", message);
        request.put("message", text);
        if (orderNo != null) request.put("orderNo", orderNo);
        return mvc.perform(post(conversationsUrl(shop) + "/" + conversation + "/messages")
                .with(httpBasic(account, PASSWORD)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)));
    }

    private ResultActions resume(String account, long shop, String conversation, String message) throws Exception {
        return mvc.perform(post(runUrl(shop, conversation, message) + "/resume")
                .with(httpBasic(account, PASSWORD)).with(csrf()));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private void assertPrivateFieldsAbsent(JsonNode value) {
        for (String field : List.of("buyerId", "customerId", "tenantId", "orderId", "conversationId", "turnId",
                "request", "requestHash", "request_hash", "buyer_id", "tenant_id", "generation", "leaseUntil",
                "processingToken", "processing_token")) {
            assertThat(value.toString()).as("Internal field %s must not appear in run progress", field)
                    .doesNotContain('"' + field + '"');
        }
        assertThat(value.toString()).doesNotContain("INJECTED_PRIVATE_FAILURE");
    }

    private List<String> phases(JsonNode steps) {
        List<String> phases = new ArrayList<>();
        for (JsonNode step : steps) phases.add(step.path("phase").asString());
        return phases;
    }

    private Fixture order(long shop, long buyer) throws SQLException {
        String number = "SFAGENT" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin(); PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO support_order
                  (tenant_id, customer_id, buyer_id, order_no, status, total_amount, currency)
                VALUES (?, ?, ?, ?, 'SHIPPED', ?, 'CNY')
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, shop);
            insert.setLong(2, buyer);
            insert.setLong(3, buyer);
            insert.setString(4, number);
            insert.setBigDecimal(5, new BigDecimal("199.90"));
            assertThat(insert.executeUpdate()).isEqualTo(1);
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                long id = keys.getLong(1);
                try (PreparedStatement event = connection.prepareStatement("""
                        INSERT INTO support_shipment_event(order_id, occurred_at, description)
                        VALUES (?, UTC_TIMESTAMP(3), '已录入的测试物流')
                        """)) {
                    event.setLong(1, id);
                    assertThat(event.executeUpdate()).isEqualTo(1);
                }
                return new Fixture(id, number);
            }
        }
    }

    private long messageCount(String conversation) throws SQLException {
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM support_chat_message message
                JOIN support_chat_conversation conversation ON conversation.id=message.conversation_id
                WHERE conversation.public_id=?
                """)) {
            query.setString(1, conversation);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static String conversationsUrl(long shop) {
        return "/api/v1/shops/" + shop + "/chat/conversations";
    }

    private static String runUrl(long shop, String conversation, String message) {
        return conversationsUrl(shop) + "/" + conversation + "/runs/" + message;
    }

    private record Fixture(long id, String number) { }
}
