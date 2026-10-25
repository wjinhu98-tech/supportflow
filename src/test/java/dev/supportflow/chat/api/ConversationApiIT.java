package dev.supportflow.chat.api;

import dev.supportflow.SupportFlowApplication;
import dev.supportflow.assistant.orchestration.ConversationOrchestrator;
import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.testsupport.KnowledgeFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Conversation ownership, durable snapshots and replay protection against an isolated MySQL. */
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
class ConversationApiIT {
    private static final String DATABASE = "supportflow_conversation_test";
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);
    private static final String DATABASE_PASSWORD = UUID.randomUUID().toString();
    private static final AtomicLong SHOP = new AtomicLong(10_000);

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
    @MockitoSpyBean private ConversationOrchestrator orchestrator;
    private MockMvc mvc;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        prepareDatabase();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "conversation_runtime");
        registry.add("spring.datasource.password", () -> DATABASE_PASSWORD);
        account(registry, 0, "buyer", 200, "ORDER_READ");
        account(registry, 1, "other", 201, "ORDER_READ");
        account(registry, 2, "no-order-permission", 202, "");
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
                     "CREATE USER 'conversation_runtime'@'%' IDENTIFIED BY ?");
             Statement grant = connection.createStatement()) {
            create.setString(1, DATABASE_PASSWORD);
            create.execute();
            KnowledgeFixtures.grantRead(connection, DATABASE, "conversation_runtime");
            for (String table : List.of("support_order", "support_shipment_event")) {
                grant.execute("GRANT SELECT ON " + DATABASE + "." + table + " TO 'conversation_runtime'@'%'");
            }
            for (String table : List.of("support_after_sale", "support_after_sale_execution",
                    "support_chat_conversation", "support_chat_turn")) {
                grant.execute("GRANT SELECT, INSERT, UPDATE ON " + DATABASE + "." + table
                        + " TO 'conversation_runtime'@'%'");
            }
            for (String table : List.of("support_after_sale_audit", "support_simulated_refund", "support_chat_message")) {
                grant.execute("GRANT SELECT, INSERT ON " + DATABASE + "." + table
                        + " TO 'conversation_runtime'@'%'");
            }
        }
        prepared = true;
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void shouldCreateConversationAndRecoverTheSameOrderedUserAndAssistantMessages() throws Exception {
        long shop = SHOP.incrementAndGet();
        JsonNode conversation = create("buyer", shop);
        String id = conversation.path("id").asString();
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(conversation.path("shopId").asLong()).isEqualTo(shop);
        assertThat(Instant.parse(conversation.path("createdAt").asString())).isNotNull();
        assertThat(Instant.parse(conversation.path("updatedAt").asString())).isNotNull();
        assertPrivateFieldsAbsent(conversation);

        JsonNode empty = history("buyer", shop, id, null);
        assertThat(empty.path("messages").size()).isZero();
        assertThat(empty.path("nextBeforeId").isNull()).isTrue();
        JsonNode sent = body(send("buyer", shop, id, UUID.randomUUID().toString(), "你好", null)
                .andExpect(status().isOk()).andReturn());
        JsonNode messages = sent.path("messages");
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(0).path("role").asString()).isEqualTo("USER");
        assertThat(messages.get(0).path("text").asString()).isEqualTo("你好");
        assertThat(messages.get(0).path("response").isNull()).isTrue();
        assertThat(messages.get(1).path("role").asString()).isEqualTo("ASSISTANT");
        assertThat(messages.get(1).path("text").asString()).contains("你好");
        assertThat(messages.get(1).path("response").path("reply"))
                .isEqualTo(messages.get(1).path("text"));
        assertThat(messages.get(1).path("response").path("type").asString()).isEqualTo("TEXT");
        assertThat(messages.get(1).path("response").path("orders").size()).isZero();
        for (JsonNode message : messages) {
            assertThat(UUID.fromString(message.path("id").asString())).isNotNull();
            assertThat(Instant.parse(message.path("createdAt").asString())).isNotNull();
            assertPrivateFieldsAbsent(message);
        }
        JsonNode recovered = history("buyer", shop, id, null);
        assertThat(recovered.path("messages")).isEqualTo(messages);
        assertThat(recovered.path("conversation").path("id").asString()).isEqualTo(id);
        assertThat(recovered.path("nextBeforeId").isNull()).isTrue();
        assertThat(messageCount(id)).isEqualTo(2);
    }

    @Test
    void shouldHideConversationsAndBothReadAndWritePathsFromOtherBuyersAndShops() throws Exception {
        long shop = SHOP.incrementAndGet();
        String id = create("buyer", shop).path("id").asString();
        send("buyer", shop, id, UUID.randomUUID().toString(), "PRIVATE-HISTORY", null)
                .andExpect(status().isOk());
        JsonNode otherBuyer = conversations("other", shop, null);
        JsonNode otherShop = conversations("buyer", shop + 100_000, null);
        assertThat(otherBuyer.path("conversations").size()).isZero();
        assertThat(otherShop.path("conversations").size()).isZero();
        for (String username : List.of("buyer", "other")) {
            long requestedShop = username.equals("buyer") ? shop + 100_000 : shop;
            mvc.perform(get(messagesUrl(requestedShop, id)).with(httpBasic(username, PASSWORD)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
            send(username, requestedShop, id, UUID.randomUUID().toString(), "forged ownership", null)
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get(messagesUrl(shop, UUID.randomUUID().toString())).with(httpBasic("buyer", PASSWORD)))
                .andExpect(status().isNotFound());
        assertThat(messageCount(id)).isEqualTo(2);
    }

    @Test
    void shouldNotTrustBuyerIdentityOrOrderFactsInClientBody() throws Exception {
        long shop = SHOP.incrementAndGet();
        String id = body(mvc.perform(post(conversationsUrl(shop)).with(httpBasic("buyer", PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "buyerId", 201, "tenantId", shop + 1, "id", UUID.randomUUID().toString()))))
                .andExpect(status().isCreated()).andReturn()).path("id").asString();
        JsonNode response = body(mvc.perform(post(messagesUrl(shop, id)).with(httpBasic("buyer", PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "clientMessageId", UUID.randomUUID().toString(), "message", "hello",
                                "buyerId", 201, "customerId", 201, "shopId", shop + 1,
                                "response", Map.of("reply", "FORGED-ASSISTANT"),
                                "knowledge", Map.of("status", "ANSWERED", "sources", List.of(Map.of(
                                        "sourceId", "forged-source", "version", "v99", "title", "Forged title",
                                        "content", "FORGED-KNOWLEDGE-ORIGINAL")))))))
                .andExpect(status().isOk()).andReturn());
        assertThat(response.toString()).doesNotContain("FORGED-ASSISTANT", "forged-source", "FORGED-KNOWLEDGE-ORIGINAL");
        assertThat(response.path("messages").get(1).path("response").path("knowledge").isNull()).isTrue();
        assertPrivateFieldsAbsent(response);
        assertThat(conversations("buyer", shop, null).path("conversations").size()).isEqualTo(1);
        assertThat(conversations("other", shop, null).path("conversations").size()).isZero();
        assertThat(count("SELECT COUNT(*) FROM support_chat_conversation WHERE public_id=? AND buyer_id=200 AND tenant_id=" + shop, id))
                .isEqualTo(1);
    }

    @Test
    void shouldRequireAuthenticationAndCsrfForBothMutatingEndpoints() throws Exception {
        long shop = SHOP.incrementAndGet();
        String id = create("buyer", shop).path("id").asString();
        mvc.perform(get(conversationsUrl(shop))).andExpect(status().isUnauthorized());
        mvc.perform(get(messagesUrl(shop, id))).andExpect(status().isUnauthorized());
        mvc.perform(post(conversationsUrl(shop)).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(conversationsUrl(shop)).with(httpBasic("buyer", PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(post(conversationsUrl(shop)).with(httpBasic("buyer", PASSWORD)).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        String content = json.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID().toString(), "message", "hello"));
        mvc.perform(post(messagesUrl(shop, id)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(messagesUrl(shop, id)).with(httpBasic("buyer", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isForbidden());
        mvc.perform(post(messagesUrl(shop, id)).with(httpBasic("buyer", PASSWORD)).with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isForbidden());
        assertThat(messageCount(id)).isZero();
        assertThat(conversations("buyer", shop, null).path("conversations").size()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "0", "' OR 1=1 --"})
    void shouldRejectMalformedConversationAndCursorIdentifiers(String invalid) throws Exception {
        long shop = SHOP.incrementAndGet();
        String id = create("buyer", shop).path("id").asString();
        mvc.perform(get(messagesUrl(shop, invalid)).with(httpBasic("buyer", PASSWORD)))
                .andExpect(status().isBadRequest());
        mvc.perform(get(conversationsUrl(shop)).param("beforeId", invalid).with(httpBasic("buyer", PASSWORD)))
                .andExpect(status().isBadRequest());
        mvc.perform(get(messagesUrl(shop, id)).param("beforeId", invalid).with(httpBasic("buyer", PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectMissingBlankOversizedAndMalformedMessageInputsBeforeClaimingATurn() throws Exception {
        long shop = SHOP.incrementAndGet();
        String id = create("buyer", shop).path("id").asString();
        List<Map<String, ?>> invalid = List.of(
                Map.of("message", "hello"),
                Map.of("clientMessageId", "not-a-uuid", "message", "hello"),
                Map.of("clientMessageId", "1-1-1-1-1", "message", "hello"),
                Map.of("clientMessageId", UUID.randomUUID().toString()),
                Map.of("clientMessageId", UUID.randomUUID().toString(), "message", "  \t\n"),
                Map.of("clientMessageId", UUID.randomUUID().toString(), "message", "x".repeat(1001)),
                Map.of("clientMessageId", UUID.randomUUID().toString(), "message", "hello", "orderNo", "x".repeat(65)),
                Map.of("clientMessageId", UUID.randomUUID().toString(), "message", "hello", "orderNo", " "),
                Map.of("clientMessageId", UUID.randomUUID().toString(), "message", "hello", "orderNo", "SF\n123"));
        for (Map<String, ?> values : invalid) {
            mvc.perform(post(messagesUrl(shop, id)).with(httpBasic("buyer", PASSWORD)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(values)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(messagesUrl(shop, id)).with(httpBasic("buyer", PASSWORD)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        assertThat(messageCount(id)).isZero();
        assertThat(turnCount(id)).isZero();
    }

    @Test
    void shouldEnforcePositiveShopAndRejectUnknownCursorInsteadOfWideningTheQuery() throws Exception {
        long shop = SHOP.incrementAndGet();
        String id = create("buyer", shop).path("id").asString();
        for (long invalid : List.of(0L, -1L)) {
            mvc.perform(get(conversationsUrl(invalid)).with(httpBasic("buyer", PASSWORD))).andExpect(status().isBadRequest());
            mvc.perform(post(conversationsUrl(invalid)).with(httpBasic("buyer", PASSWORD)).with(csrf()))
                    .andExpect(status().isBadRequest());
            send("buyer", invalid, id, UUID.randomUUID().toString(), "hello", null).andExpect(status().isBadRequest());
        }
        mvc.perform(get(conversationsUrl(shop)).param("beforeId", UUID.randomUUID().toString())
                        .with(httpBasic("buyer", PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(get(messagesUrl(shop, id)).param("beforeId", UUID.randomUUID().toString())
                        .with(httpBasic("buyer", PASSWORD))).andExpect(status().isNotFound());
    }

    @Test
    void shouldPageConversationsNewestFirstWithoutDuplicatesOrLeakingForeignCursors() throws Exception {
        long shop = SHOP.incrementAndGet();
        List<String> created = new ArrayList<>();
        for (int index = 0; index < 21; index++) created.add(create("buyer", shop).path("id").asString());
        String foreign = create("other", shop).path("id").asString();
        String otherShop = create("buyer", shop + 100_000).path("id").asString();
        JsonNode first = conversations("buyer", shop, null);
        assertThat(first.path("conversations").size()).isEqualTo(20);
        assertThat(ids(first.path("conversations"))).containsExactlyElementsOf(created.reversed().subList(0, 20));
        String cursor = first.path("nextBeforeId").asString();
        assertThat(cursor).isEqualTo(created.get(1));
        // A newer conversation inserted between requests must not shift an older keyset page.
        create("buyer", shop);
        JsonNode second = conversations("buyer", shop, cursor);
        assertThat(ids(second.path("conversations"))).containsExactly(created.getFirst());
        assertThat(second.path("nextBeforeId").isNull()).isTrue();
        for (String invalidCursor : List.of(foreign, otherShop)) {
            mvc.perform(get(conversationsUrl(shop)).param("beforeId", invalidCursor).with(httpBasic("buyer", PASSWORD)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void shouldPageMessagesNewestFortyInChronologicalOrderAndRejectOtherConversationCursor() throws Exception {
        long shop = SHOP.incrementAndGet();
        String id = create("buyer", shop).path("id").asString();
        List<String> sent = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            JsonNode response = body(send("buyer", shop, id, UUID.randomUUID().toString(), "hello-" + index, null)
                    .andExpect(status().isOk()).andReturn());
            sent.addAll(ids(response.path("messages")));
        }
        JsonNode first = history("buyer", shop, id, null);
        assertThat(first.path("messages").size()).isEqualTo(40);
        assertThat(ids(first.path("messages"))).containsExactlyElementsOf(sent.subList(2, 42));
        String cursor = first.path("nextBeforeId").asString();
        assertThat(cursor).isEqualTo(sent.get(2));
        JsonNode second = history("buyer", shop, id, cursor);
        assertThat(ids(second.path("messages"))).containsExactlyElementsOf(sent.subList(0, 2));
        assertThat(second.path("nextBeforeId").isNull()).isTrue();
        List<String> combined = new ArrayList<>(ids(second.path("messages")));
        combined.addAll(ids(first.path("messages")));
        assertThat(new HashSet<>(combined)).hasSize(42);
        String other = create("buyer", shop).path("id").asString();
        mvc.perform(get(messagesUrl(shop, other)).param("beforeId", sent.getFirst()).with(httpBasic("buyer", PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnTheExactPersistedPairForSameClientKeyAndRejectChangedMessageOrContext() throws Exception {
        long shop = SHOP.incrementAndGet();
        String id = create("buyer", shop).path("id").asString();
        String key = UUID.randomUUID().toString();
        String text = "IDEMPOTENT-HELLO";
        JsonNode first = body(send("buyer", shop, id, key, text, null).andExpect(status().isOk()).andReturn());
        JsonNode repeated = body(send("buyer", shop, id, key, text, null).andExpect(status().isOk()).andReturn());
        assertThat(repeated.path("messages")).isEqualTo(first.path("messages"));
        send("buyer", shop, id, key, "CHANGED-HELLO", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_CONFLICT"));
        send("buyer", shop, id, key, text, "SF-DIFFERENT").andExpect(status().isConflict());
        assertThat(messageCount(id)).isEqualTo(2);
        assertThat(turnCount(id)).isEqualTo(1);
        verify(orchestrator, times(1)).reply(any(), eq(shop), eq(text), isNull());
    }

    @Test
    void shouldKeepClientMessageKeysScopedToTheirConversation() throws Exception {
        long shop = SHOP.incrementAndGet();
        String firstId = create("buyer", shop).path("id").asString();
        String secondId = create("buyer", shop).path("id").asString();
        String key = UUID.randomUUID().toString();
        JsonNode first = body(send("buyer", shop, firstId, key, "one", null).andExpect(status().isOk()).andReturn());
        JsonNode second = body(send("buyer", shop, secondId, key, "two", null).andExpect(status().isOk()).andReturn());
        Set<String> overlap = new HashSet<>(ids(first.path("messages")));
        overlap.retainAll(ids(second.path("messages")));
        assertThat(overlap).isEmpty();
        assertThat(messageCount(firstId)).isEqualTo(2);
        assertThat(messageCount(secondId)).isEqualTo(2);
    }

    @Test
    void shouldRetainBusinessToolAuthorizationInsteadOfGrantingOrderAccessThroughHistory() throws Exception {
        long shop = SHOP.incrementAndGet();
        String id = create("no-order-permission", shop).path("id").asString();
        send("no-order-permission", shop, id, UUID.randomUUID().toString(), "查订单", null)
                .andExpect(status().isForbidden());
        assertThat(messageCount(id)).isZero();
        assertThat(history("no-order-permission", shop, id, null).path("messages").size()).isZero();
    }

    @Test
    void shouldNotExecuteTheSameConcurrentClientRequestMoreThanOnce() throws Exception {
        long shop = SHOP.incrementAndGet();
        String id = create("buyer", shop).path("id").asString();
        String key = UUID.randomUUID().toString();
        String text = "CONCURRENT-HELLO";
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<MvcResult>> requests = new ArrayList<>();
        for (int index = 0; index < 3; index++) requests.add(CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent request start timeout");
                return send("buyer", shop, id, key, text, null).andReturn();
            } catch (Exception failure) {
                throw new IllegalStateException("Isolated concurrent chat request failed", failure);
            }
        }));
        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            go.countDown();
        }
        List<MvcResult> results = new ArrayList<>();
        for (CompletableFuture<MvcResult> request : requests) results.add(request.get(30, TimeUnit.SECONDS));
        assertThat(results.stream().map(result -> result.getResponse().getStatus()).toList())
                .allMatch(code -> code == 200 || code == 409).contains(200);
        JsonNode successfulPair = body(results.stream().filter(result -> result.getResponse().getStatus() == 200)
                .findFirst().orElseThrow()).path("messages");
        for (MvcResult result : results) {
            if (result.getResponse().getStatus() == 200) assertThat(body(result).path("messages")).isEqualTo(successfulPair);
        }
        assertThat(messageCount(id)).isEqualTo(2);
        assertThat(turnCount(id)).isEqualTo(1);
        verify(orchestrator, times(1)).reply(any(), eq(shop), eq(text), isNull());
    }

    @Test
    void shouldPersistOnlyPublicOrderSnapshotAndNotRewriteItWhenBusinessStateChanges() throws Exception {
        long shop = SHOP.incrementAndGet();
        Fixture order = order(shop, 200, "PAID");
        String id = create("buyer", shop).path("id").asString();
        JsonNode sent = body(send("buyer", shop, id, UUID.randomUUID().toString(), "查询此订单", order.number())
                .andExpect(status().isOk()).andReturn());
        JsonNode snapshot = sent.path("messages").get(1).path("response");
        assertThat(snapshot.path("type").asString()).isEqualTo("ORDER_DETAILS");
        assertThat(snapshot.path("orders").size()).isEqualTo(1);
        assertThat(snapshot.path("orders").get(0).path("orderNo").asString()).isEqualTo(order.number());
        assertThat(snapshot.path("orders").get(0).path("status").asString()).isEqualTo("PAID");
        assertPrivateFieldsAbsent(snapshot);
        try (Connection connection = admin(); PreparedStatement update = connection.prepareStatement(
                "UPDATE support_order SET status='SHIPPED' WHERE id=?")) {
            update.setLong(1, order.id());
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
        JsonNode recovered = history("buyer", shop, id, null).path("messages").get(1).path("response");
        assertThat(recovered).isEqualTo(snapshot);
        JsonNode currentOrder = body(mvc.perform(get("/api/v1/shops/{shopId}/orders/{orderNo}", shop, order.number())
                        .with(httpBasic("buyer", PASSWORD))).andExpect(status().isOk()).andReturn());
        assertThat(currentOrder.path("status").asString()).isEqualTo("SHIPPED");
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement("""
                SELECT response_json FROM support_chat_message message
                JOIN support_chat_conversation conversation ON conversation.id=message.conversation_id
                WHERE conversation.public_id=? AND message.role='ASSISTANT'
                """)) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                JsonNode stored = json.readTree(row.getString(1));
                assertThat(stored).isEqualTo(snapshot);
                assertPrivateFieldsAbsent(stored);
                assertThat(row.next()).isFalse();
            }
        }
    }

    @Test
    void shouldPersistKnowledgeSourcesAcrossDraftChangesRepublishOfflineAndIdempotentReplay() throws Exception {
        long shop = SHOP.incrementAndGet();
        String token = "faq" + UUID.randomUUID().toString().replace("-", "");
        String original = "ORIGINAL-PUBLISHED-GIFT-GUIDE " + token;
        String revised = "REVISED-PUBLISHED-GIFT-GUIDE " + token;
        String article = knowledge(shop, token, original);
        String conversation = create("buyer", shop).path("id").asString();
        String key = UUID.randomUUID().toString();
        String message = "知识库 " + token;
        JsonNode first = body(send("buyer", shop, conversation, key, message, null)
                .andExpect(status().isOk()).andReturn());
        JsonNode firstSnapshot = first.path("messages").get(1).path("response");
        assertKnowledgeSource(firstSnapshot, article, "v1", token, original);
        assertThat(storedResponse(first.path("messages").get(1).path("id").asString())).isEqualTo(firstSnapshot);

        execute("UPDATE support_knowledge_article SET content=?,version=2 WHERE id=?", revised, article);
        JsonNode draftResult = body(send("buyer", shop, conversation, UUID.randomUUID().toString(), message, null)
                .andExpect(status().isOk()).andReturn());
        assertKnowledgeSource(draftResult.path("messages").get(1).path("response"), article, "v1", token, original);

        republishKnowledge(article);
        JsonNode republished = body(send("buyer", shop, conversation, UUID.randomUUID().toString(), message, null)
                .andExpect(status().isOk()).andReturn());
        JsonNode secondSnapshot = republished.path("messages").get(1).path("response");
        assertKnowledgeSource(secondSnapshot, article, "v2", token, revised);
        JsonNode beforeOffline = history("buyer", shop, conversation, null).path("messages");
        assertThat(beforeOffline.get(1).path("response")).isEqualTo(firstSnapshot);
        assertThat(beforeOffline.get(3).path("response")).isEqualTo(firstSnapshot);
        assertThat(beforeOffline.get(5).path("response")).isEqualTo(secondSnapshot);

        execute("""
                UPDATE support_knowledge_article SET status='OFFLINE',version=4,
                    published_revision=NULL,publication_slot=NULL WHERE id=?
                """, article);
        assertThat(history("buyer", shop, conversation, null).path("messages")).isEqualTo(beforeOffline);
        JsonNode offline = body(send("buyer", shop, conversation, UUID.randomUUID().toString(), message, null)
                .andExpect(status().isOk()).andReturn()).path("messages").get(1).path("response");
        assertThat(offline.path("knowledge").path("status").asString()).isEqualTo("NO_MATCH");
        assertThat(offline.path("knowledge").path("sources").size()).isZero();
        assertThat(offline.toString()).doesNotContain(original, revised, "kb-" + article);
        JsonNode replayed = body(send("buyer", shop, conversation, key, message, null)
                .andExpect(status().isOk()).andReturn());
        assertThat(replayed.path("messages")).isEqualTo(first.path("messages"));
        assertThat(messageCount(conversation)).isEqualTo(8);
        verify(orchestrator, times(4)).reply(any(), eq(shop), eq(message), isNull());
    }

    @Test
    void shouldRestoreLegacyResponseJsonWithoutFabricatingKnowledgeOrRerunningRetrieval() throws Exception {
        long shop = SHOP.incrementAndGet();
        String conversation = create("buyer", shop).path("id").asString();
        String key = UUID.randomUUID().toString();
        String text = "legacy greeting";
        JsonNode sent = body(send("buyer", shop, conversation, key, text, null)
                .andExpect(status().isOk()).andReturn());
        JsonNode expected = sent.path("messages").get(1).path("response");
        String messageId = sent.path("messages").get(1).path("id").asString();
        execute("UPDATE support_chat_message SET response_json=JSON_REMOVE(response_json,'$.knowledge') WHERE public_id=?",
                messageId);
        assertThat(storedResponse(messageId).has("knowledge")).isFalse();

        JsonNode recovered = history("buyer", shop, conversation, null).path("messages").get(1).path("response");
        assertThat(recovered).isEqualTo(expected);
        assertThat(recovered.path("knowledge").isNull()).isTrue();
        assertPrivateFieldsAbsent(recovered);
        JsonNode replayed = body(send("buyer", shop, conversation, key, text, null)
                .andExpect(status().isOk()).andReturn());
        assertThat(replayed.path("messages").get(1).path("response")).isEqualTo(expected);
        assertThat(storedResponse(messageId).has("knowledge")).isFalse();
        assertThat(messageCount(conversation)).isEqualTo(2);
        verify(orchestrator, times(1)).reply(any(), eq(shop), eq(text), isNull());
    }

    @Test
    void shouldKeepSavedKnowledgeCitationsPrivateToConversationBuyerAndShop() throws Exception {
        long shop = SHOP.incrementAndGet();
        long foreignShop = SHOP.incrementAndGet();
        String token = "faq" + UUID.randomUUID().toString().replace("-", "");
        String ownText = "CURRENT-SHOP-PUBLIC-KNOWLEDGE " + token;
        String foreignText = "FOREIGN-SHOP-PUBLIC-KNOWLEDGE " + token;
        String ownArticle = knowledge(shop, token, ownText);
        String foreignArticle = knowledge(foreignShop, token, foreignText);
        String conversation = create("buyer", shop).path("id").asString();
        String key = UUID.randomUUID().toString();
        String text = "知识库 " + token;
        JsonNode own = body(send("buyer", shop, conversation, key, text, null)
                .andExpect(status().isOk()).andReturn());
        assertKnowledgeSource(own.path("messages").get(1).path("response"), ownArticle, "v1", token, ownText);
        assertThat(own.toString()).doesNotContain(foreignText, "kb-" + foreignArticle);

        for (String user : List.of("buyer", "other")) {
            long requestedShop = user.equals("buyer") ? foreignShop : shop;
            MvcResult deniedHistory = mvc.perform(get(messagesUrl(requestedShop, conversation)).with(httpBasic(user, PASSWORD)))
                    .andExpect(status().isNotFound()).andReturn();
            MvcResult deniedReplay = send(user, requestedShop, conversation, key, text, null)
                    .andExpect(status().isNotFound()).andReturn();
            assertThat(deniedHistory.getResponse().getContentAsString()).doesNotContain(ownText, ownArticle);
            assertThat(deniedReplay.getResponse().getContentAsString()).doesNotContain(ownText, ownArticle);
        }

        String foreignConversation = create("buyer", foreignShop).path("id").asString();
        JsonNode foreign = body(send("buyer", foreignShop, foreignConversation, UUID.randomUUID().toString(), text, null)
                .andExpect(status().isOk()).andReturn());
        assertKnowledgeSource(foreign.path("messages").get(1).path("response"), foreignArticle, "v1", token, foreignText);
        assertThat(foreign.toString()).doesNotContain(ownText, "kb-" + ownArticle);

        // Shop FAQ is intentionally public to authenticated buyers; another buyer may retrieve it
        // in a new conversation, but must never read or replay the original buyer's saved messages.
        String otherConversation = create("other", shop).path("id").asString();
        JsonNode other = body(send("other", shop, otherConversation, UUID.randomUUID().toString(), text, null)
                .andExpect(status().isOk()).andReturn());
        assertKnowledgeSource(other.path("messages").get(1).path("response"), ownArticle, "v1", token, ownText);
        assertThat(ids(other.path("messages"))).doesNotContainAnyElementsOf(ids(own.path("messages")));
        assertThat(messageCount(conversation)).isEqualTo(2);
    }

    @Test
    void shouldNeverReplayARefundApplicationWhileLoadingHistoryOrRetryingTheSameTurn() throws Exception {
        long shop = SHOP.incrementAndGet();
        Fixture order = order(shop, 200, "PAID");
        String id = create("buyer", shop).path("id").asString();
        String key = UUID.randomUUID().toString();
        String text = "申请售后 " + order.number() + " damaged item";
        JsonNode sent = body(send("buyer", shop, id, key, text, null).andExpect(status().isOk()).andReturn());
        assertThat(count("SELECT COUNT(*) FROM support_after_sale WHERE order_id=?", order.id())).isEqualTo(1);
        long auditBefore = count("SELECT COUNT(*) FROM support_after_sale_audit audit JOIN support_after_sale sale ON sale.id=audit.request_id WHERE sale.order_id=?", order.id());
        for (int index = 0; index < 3; index++) {
            assertThat(history("buyer", shop, id, null).path("messages")).isEqualTo(sent.path("messages"));
            conversations("buyer", shop, null);
        }
        JsonNode repeated = body(send("buyer", shop, id, key, text, null).andExpect(status().isOk()).andReturn());
        assertThat(repeated.path("messages")).isEqualTo(sent.path("messages"));
        assertThat(count("SELECT COUNT(*) FROM support_after_sale WHERE order_id=?", order.id())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM support_after_sale_audit audit JOIN support_after_sale sale ON sale.id=audit.request_id WHERE sale.order_id=?", order.id()))
                .isEqualTo(auditBefore);
        verify(orchestrator, times(1)).reply(any(), eq(shop), eq(text), isNull());
    }

    @Test
    void shouldKeepFailedModelTurnOutOfNormalHistoryAndNeverRetryItUnderTheSameKey() throws Exception {
        long shop = SHOP.incrementAndGet();
        String id = create("buyer", shop).path("id").asString();
        String key = UUID.randomUUID().toString();
        String text = "MODEL_FAILURE";
        doThrow(new ModelUnavailableException()).when(orchestrator).reply(any(), eq(shop), eq(text), isNull());
        send("buyer", shop, id, key, text, null).andExpect(status().isServiceUnavailable());
        assertThat(messageCount(id)).isZero();
        assertThat(history("buyer", shop, id, null).path("messages").size()).isZero();
        assertThat(count("SELECT COUNT(*) FROM support_chat_turn turn JOIN support_chat_conversation conversation ON conversation.id=turn.conversation_id WHERE conversation.public_id=? AND turn.status='FAILED'", id))
                .isEqualTo(1);
        send("buyer", shop, id, key, text, null).andExpect(status().isConflict());
        verify(orchestrator, times(1)).reply(any(), eq(shop), eq(text), isNull());
        send("buyer", shop, id, UUID.randomUUID().toString(), "new request", null).andExpect(status().isOk());
        assertThat(messageCount(id)).isEqualTo(2);
    }

    @Test
    void shouldRollbackBothMessagesIfSecondInsertFailsAndNeverReplayTheAlreadyRunBusinessAction() throws Exception {
        long shop = SHOP.incrementAndGet();
        Fixture order = order(shop, 200, "PAID");
        String id = create("buyer", shop).path("id").asString();
        String key = UUID.randomUUID().toString();
        String text = "申请售后 " + order.number() + " damaged item";
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER conversation_test_fail_assistant BEFORE INSERT ON support_chat_message
                    FOR EACH ROW BEGIN
                        IF NEW.role='ASSISTANT' THEN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Isolated assistant snapshot insert failure';
                        END IF;
                    END
                    """);
        }
        try {
            MvcResult failed = send("buyer", shop, id, key, text, null).andReturn();
            assertThat(failed.getResponse().getStatus()).isIn(500, 503);
            assertThat(messageCount(id)).isZero();
            assertThat(count("SELECT COUNT(*) FROM support_after_sale WHERE order_id=?", order.id())).isEqualTo(1);
            assertThat(history("buyer", shop, id, null).path("messages").size()).isZero();
        } finally {
            try (Connection connection = admin(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER conversation_test_fail_assistant");
            }
        }
        send("buyer", shop, id, key, text, null).andExpect(status().isConflict());
        assertThat(messageCount(id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM support_after_sale WHERE order_id=?", order.id())).isEqualTo(1);
        verify(orchestrator, times(1)).reply(any(), eq(shop), eq(text), isNull());
    }

    @Test
    void shouldKeepRuntimeUnableToEditOrDeleteSavedMessages() throws Exception {
        long shop = SHOP.incrementAndGet();
        String id = create("buyer", shop).path("id").asString();
        send("buyer", shop, id, UUID.randomUUID().toString(), "immutable history", null).andExpect(status().isOk());
        try (Connection runtime = DriverManager.getConnection(MYSQL.getJdbcUrl(), "conversation_runtime", DATABASE_PASSWORD)) {
            for (String sql : List.of("UPDATE support_chat_message SET text='forged' WHERE public_id=?",
                    "DELETE FROM support_chat_message WHERE public_id=?")) {
                try (PreparedStatement denied = runtime.prepareStatement(sql)) {
                    denied.setString(1, history("buyer", shop, id, null).path("messages").get(0).path("id").asString());
                    assertThatThrownBy(denied::executeUpdate).isInstanceOf(SQLException.class)
                            .satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isIn(1142, 1143));
                }
            }
        }
        assertThat(messageCount(id)).isEqualTo(2);
    }

    private JsonNode create(String username, long shop) throws Exception {
        return body(mvc.perform(post(conversationsUrl(shop)).with(httpBasic(username, PASSWORD)).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode conversations(String username, long shop, String cursor) throws Exception {
        var request = get(conversationsUrl(shop)).with(httpBasic(username, PASSWORD));
        return body(mvc.perform(cursor == null ? request : request.param("beforeId", cursor))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode history(String username, long shop, String id, String cursor) throws Exception {
        var request = get(messagesUrl(shop, id)).with(httpBasic(username, PASSWORD));
        return body(mvc.perform(cursor == null ? request : request.param("beforeId", cursor))
                .andExpect(status().isOk()).andReturn());
    }

    private ResultActions send(String username, long shop, String conversation, String clientId,
                               String message, String orderNo) throws Exception {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("clientMessageId", clientId);
        values.put("message", message);
        if (orderNo != null) values.put("orderNo", orderNo);
        return mvc.perform(post(messagesUrl(shop, conversation)).with(httpBasic(username, PASSWORD)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(values)));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void assertKnowledgeSource(JsonNode response, String article, String version, String title, String content) {
        JsonNode evidence = response.path("knowledge");
        assertThat(evidence.path("status").asString()).isEqualTo("ANSWERED");
        assertThat(evidence.path("sources").size()).isEqualTo(1);
        JsonNode source = evidence.path("sources").get(0);
        assertThat(source.path("sourceId").asString()).isEqualTo("kb-" + article);
        assertThat(source.path("version").asString()).isEqualTo(version);
        assertThat(source.path("title").asString()).isEqualTo(title);
        assertThat(source.path("content").asString()).isEqualTo(content);
        assertPrivateFieldsAbsent(response);
    }

    private JsonNode storedResponse(String message) throws SQLException {
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement(
                "SELECT response_json FROM support_chat_message WHERE public_id=?")) {
            query.setString(1, message);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                JsonNode response = json.readTree(row.getString(1));
                assertThat(row.next()).isFalse();
                return response;
            }
        }
    }

    private String knowledge(long shop, String keyword, String content) throws SQLException {
        String article = UUID.randomUUID().toString();
        try (Connection connection = admin(); PreparedStatement draft = connection.prepareStatement("""
                INSERT INTO support_knowledge_article
                  (id,tenant_id,title,keywords_json,content,status,version,last_revision,published_revision,publication_slot)
                VALUES (?,?,?,JSON_ARRAY(?),?,'PUBLISHED',1,1,1,1)
                """); PreparedStatement revision = connection.prepareStatement("""
                INSERT INTO support_knowledge_revision
                  (article_id,revision,tenant_id,title,keywords_json,content,actor,staff_id)
                VALUES (?,1,?,?,JSON_ARRAY(?),?,'isolated-fixture',1000)
                """)) {
            connection.setAutoCommit(false);
            draft.setString(1, article);
            draft.setLong(2, shop);
            draft.setString(3, keyword);
            draft.setString(4, keyword);
            draft.setString(5, content);
            assertThat(draft.executeUpdate()).isEqualTo(1);
            revision.setString(1, article);
            revision.setLong(2, shop);
            revision.setString(3, keyword);
            revision.setString(4, keyword);
            revision.setString(5, content);
            assertThat(revision.executeUpdate()).isEqualTo(1);
            connection.commit();
        }
        return article;
    }

    private void republishKnowledge(String article) throws SQLException {
        try (Connection connection = admin(); PreparedStatement revision = connection.prepareStatement("""
                INSERT INTO support_knowledge_revision
                  (article_id,revision,tenant_id,title,keywords_json,content,actor,staff_id)
                SELECT id,2,tenant_id,title,keywords_json,content,'isolated-fixture',1000
                FROM support_knowledge_article WHERE id=?
                """); PreparedStatement publish = connection.prepareStatement("""
                UPDATE support_knowledge_article SET version=3,last_revision=2,published_revision=2 WHERE id=?
                """)) {
            connection.setAutoCommit(false);
            revision.setString(1, article);
            assertThat(revision.executeUpdate()).isEqualTo(1);
            publish.setString(1, article);
            assertThat(publish.executeUpdate()).isEqualTo(1);
            connection.commit();
        }
    }

    private void execute(String sql, Object... arguments) throws SQLException {
        try (Connection connection = admin(); PreparedStatement mutation = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) mutation.setObject(index + 1, arguments[index]);
            assertThat(mutation.executeUpdate()).isEqualTo(1);
        }
    }

    private List<String> ids(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : nodes) values.add(node.path("id").asString());
        return values;
    }

    private void assertPrivateFieldsAbsent(JsonNode value) {
        for (String privateField : List.of("buyerId", "customerId", "tenantId", "orderId", "conversationId",
                "turnId", "requestHash", "request_hash", "buyer_id", "customer_id", "tenant_id")) {
            assertThat(value.toString()).as("Private field %s must not appear in public snapshots", privateField)
                    .doesNotContain('"' + privateField + '"');
        }
    }

    private long messageCount(String conversation) throws SQLException {
        return count("SELECT COUNT(*) FROM support_chat_message message JOIN support_chat_conversation conversation ON conversation.id=message.conversation_id WHERE conversation.public_id=?", conversation);
    }

    private long turnCount(String conversation) throws SQLException {
        return count("SELECT COUNT(*) FROM support_chat_turn turn JOIN support_chat_conversation conversation ON conversation.id=turn.conversation_id WHERE conversation.public_id=?", conversation);
    }

    private long count(String sql, Object parameter) throws SQLException {
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setObject(1, parameter);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private Fixture order(long shop, long buyer, String state) throws SQLException {
        String number = "SFCONVERSATION" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin(); PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO support_order
                (tenant_id, customer_id, buyer_id, order_no, status, total_amount, currency)
                VALUES (?, ?, ?, ?, ?, ?, 'CNY')
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, shop);
            insert.setLong(2, buyer);
            insert.setLong(3, buyer);
            insert.setString(4, number);
            insert.setString(5, state);
            insert.setBigDecimal(6, new BigDecimal("199.90"));
            assertThat(insert.executeUpdate()).isEqualTo(1);
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return new Fixture(keys.getLong(1), number);
            }
        }
    }

    private String conversationsUrl(long shop) {
        return "/api/v1/shops/" + shop + "/chat/conversations";
    }

    private String messagesUrl(long shop, String conversation) {
        return conversationsUrl(shop) + "/" + conversation + "/messages";
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private record Fixture(long id, String number) { }
}
