package dev.supportflow.chat.support;

import dev.supportflow.SupportFlowApplication;
import dev.supportflow.chat.api.response.ChatResponse;
import dev.supportflow.chat.application.model.ChatReplyType;
import dev.supportflow.chat.conversation.*;
import dev.supportflow.common.security.model.*;
import dev.supportflow.merchant.MerchantAccessService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static dev.supportflow.chat.support.HumanSupportModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Isolated real MySQL, authenticated HTTP, race and durable-history tests; no paid model calls. */
@SpringBootTest(classes = SupportFlowApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"spring.config.location=classpath:/application-day4-test.yml", "spring.main.banner-mode=off", "supportflow.ai.deepseek.enabled=false"})
@ActiveProfiles("local")
@Testcontainers
class HumanSupportApiIT {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);
    private static final AtomicLong SHOPS = new AtomicLong(80000);
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName("human_support_test").withUsername("root").withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%").withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true").withUrlParam("sslMode", "DISABLED")
            .withUrlParam("allowPublicKeyRetrieval", "true");
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired JdbcConversationStore conversations;
    @Autowired HumanSupportService humans;
    @Autowired HumanSupportAiGate gate;
    @Autowired MerchantAccessService merchants;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean ConversationContextEngine engine;
    private MockMvc mvc;
    private long shop;
    private UUID conversation;
    private String buyerBase;
    private String merchantBase;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        account(registry, 0, "buyer", 200, "BUYER", "ORDER_READ");
        account(registry, 1, "other", 201, "BUYER", "ORDER_READ");
        account(registry, 2, "support", 999, "MERCHANT_STAFF", "CHAT_SUPPORT");
        account(registry, 3, "support-two", 998, "MERCHANT_STAFF", "CHAT_SUPPORT");
        account(registry, 4, "reviewer", 997, "MERCHANT_STAFF", "ORDER_READ,AFTER_SALE_REVIEW");
    }
    private static void account(DynamicPropertyRegistry registry, int i, String username, long id, String type, String permissions) {
        String prefix = "supportflow.security.local.accounts[" + i + "].";
        registry.add(prefix + "username", () -> username); registry.add(prefix + "password-hash", () -> HASH);
        registry.add(prefix + "identity.buyer-id", () -> id); registry.add(prefix + "account-type", () -> type);
        registry.add(prefix + "permissions", () -> permissions);
    }
    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        shop = SHOPS.incrementAndGet();
        jdbc.sql("INSERT INTO support_merchant(id,name) VALUES(:id,'support test')").param("id", shop).update();
        jdbc.sql("INSERT INTO support_merchant_shop(shop_id,merchant_id,name) VALUES(:id,:id,'support shop')").param("id", shop).update();
        jdbc.sql("INSERT IGNORE INTO support_merchant_staff(id,merchant_id,username,linked_buyer_id) VALUES(999,1,'support',999),(998,1,'support-two',998),(997,1,'reviewer',997)").update();
        // Each test has its own merchant while users are bound to the same deliberately isolated merchant.
        jdbc.sql("INSERT IGNORE INTO support_merchant(id,name) VALUES(1,'test staff merchant')").update();
        jdbc.sql("UPDATE support_merchant_shop SET merchant_id=1 WHERE shop_id=:shop").param("shop", shop).update();
        for (long staff : List.of(999L, 998L, 997L)) jdbc.sql("INSERT INTO support_merchant_staff_shop_grant(staff_id,shop_id,enabled) VALUES(:staff,:shop,TRUE)")
                .param("staff", staff).param("shop", shop).update();
        conversation = conversations.openWindow(200, shop).id();
        buyerBase = "/api/v1/shops/" + shop + "/chat/conversations/" + conversation + "/support";
        merchantBase = "/api/v1/merchant/shops/" + shop + "/support-sessions";
    }

    @Test void completeHandoffIsDurableIdempotentAndReturnsToAi() throws Exception {
        UUID request = UUID.randomUUID();
        JsonNode started = postAs(buyerBase, "buyer", Map.of("requestId", request));
        String session = started.path("session").path("id").asText();
        assertThat(started.path("session").path("status").asText()).isEqualTo("WAITING");
        assertThat(postAs(buyerBase, "buyer", Map.of("requestId", request)).path("session").path("id").asText()).isEqualTo(session);
        UUID message = UUID.randomUUID();
        JsonNode buyerReply = postAs(buyerBase + "/" + session + "/messages", "buyer", Map.of("clientMessageId", message, "text", "请核实退款，不要自动执行"));
        assertThat(postAs(buyerBase + "/" + session + "/messages", "buyer", Map.of("clientMessageId", message, "text", "请核实退款，不要自动执行"))).isEqualTo(buyerReply);
        mvc.perform(get(merchantBase + "/" + session).with(httpBasic("support", PASSWORD))).andExpect(status().isOk()).andExpect(jsonPath("$.messages").isEmpty());
        postAs(merchantBase + "/" + session + "/claim", "support", null);
        postAs(merchantBase + "/" + session + "/claim", "support", null);
        postAs(merchantBase + "/" + session + "/messages", "support", Map.of("clientMessageId", UUID.randomUUID(), "text", "已收到，我会核实"));
        var restarted = new HumanSupportService(jdbc, merchants, manager);
        assertThat(restarted.buyerSnapshot(buyer(), shop, conversation).messages()).hasSize(2);
        postAs(merchantBase + "/" + session + "/close", "support", null);
        postAs(merchantBase + "/" + session + "/close", "support", null);
        assertThat(postAs(buyerBase, "buyer", Map.of("requestId", request)).path("session").path("status").asText()).isEqualTo("CLOSED");
        var reservation = conversations.reserve(200, shop, conversation, new ConversationMessageRequest(UUID.randomUUID(), "重新查询", null));
        assertThat(reservation.context().kind()).isEqualTo(ConversationContextKind.NONE);
        verifyNoInteractions(engine);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM support_after_sale WHERE tenant_id=:shop").param("shop", shop).query(Long.class).single()).isZero();
    }

    @Test void enforcesCsrfBuyerShopRoleAssignmentAndTranscriptBoundaries() throws Exception {
        mvc.perform(post(buyerBase).with(httpBasic("buyer", PASSWORD)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("requestId", UUID.randomUUID())))).andExpect(status().isForbidden());
        mvc.perform(get(buyerBase)).andExpect(status().isUnauthorized());
        var session = start();
        mvc.perform(get(buyerBase).with(httpBasic("other", PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(get(buyerBase.replace("/shops/" + shop, "/shops/" + (shop + 999))).with(httpBasic("buyer", PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(get(merchantBase).with(httpBasic("reviewer", PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(get(merchantBase).with(httpBasic("buyer", PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(get(merchantBase.replace("/shops/" + shop, "/shops/" + (shop + 999))).with(httpBasic("support", PASSWORD))).andExpect(status().isForbidden());
        postAs(merchantBase + "/" + session + "/claim", "support", null);
        mvc.perform(get(merchantBase + "/" + session).with(httpBasic("support-two", PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(post(merchantBase + "/" + session + "/messages").with(httpBasic("support-two", PASSWORD)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID(), "text", "wrong staff")))).andExpect(status().isConflict());
        var snapshot = mvc.perform(get(buyerBase).with(httpBasic("buyer", PASSWORD))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(snapshot).doesNotContain("buyerId", "staffId", "username", "password", "prompt", "conversationId");
        jdbc.sql("UPDATE support_merchant_staff_shop_grant SET enabled=FALSE WHERE staff_id=999 AND shop_id=:shop").param("shop", shop).update();
        mvc.perform(post(merchantBase + "/" + session + "/close").with(httpBasic("support", PASSWORD)).with(csrf())).andExpect(status().isForbidden());
    }

    @Test void humanModeBlocksEveryAiPipelineAndDeletionAcrossLegacyConversations() throws Exception {
        start();
        mvc.perform(get("/api/v1/shops/" + shop + "/orders").with(httpBasic("buyer", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        var otherConversation = conversations.create(200, shop).id();
        mvc.perform(post("/api/v1/shops/" + shop + "/chat/messages").with(httpBasic("buyer", PASSWORD)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"帮我查一下\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/shops/" + shop + "/chat/conversations/" + otherConversation + "/messages").with(httpBasic("buyer", PASSWORD)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID(), "message", "帮我查一下")))).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/shops/" + shop + "/chat/window/" + conversation).with(httpBasic("buyer", PASSWORD)).with(csrf())).andExpect(status().isConflict());
        assertThatThrownBy(() -> conversations.reserveResume(200, shop, conversation, new ConversationMessageRequest(UUID.randomUUID(), "resume", null), true)).isInstanceOf(ConversationException.class);
        verifyNoInteractions(engine);
    }

    @Test void paginationKeepsAllBuyerSessionsAndOnlyAssignedSessionForMerchant() throws Exception {
        UUID first = start();
        humans.claim(staff(999), shop, first);
        for (int i = 0; i < 35; i++) humans.buyerSend(buyer(), shop, conversation, first, new MessageRequest(UUID.randomUUID(), "消息" + i));
        var newest = humans.buyerMessages(buyer(), shop, conversation, null);
        assertThat(newest.messages()).hasSize(30);
        assertThat(newest.messages().getFirst().text()).isEqualTo("消息5");
        var older = humans.buyerMessages(buyer(), shop, conversation, newest.nextBeforeId());
        assertThat(older.messages()).hasSize(5);
        assertThat(older.nextBeforeId()).isNull();
        humans.buyerClose(buyer(), shop, conversation, first);
        UUID second = humans.start(buyer(), shop, conversation, new StartRequest(UUID.randomUUID())).session().id();
        humans.buyerSend(buyer(), shop, conversation, second, new MessageRequest(UUID.randomUUID(), "新人工会话"));
        humans.claim(staff(998), shop, second);
        assertThat(humans.merchantSnapshot(staff(998), shop, second, null).messages()).extracting(MessageView::text).containsExactly("新人工会话");
        assertThat(humans.merchantSnapshot(staff(999), shop, first, newest.nextBeforeId()).messages()).hasSize(5);
        humans.buyerClose(buyer(), shop, conversation, first); // Delayed close must not end the new session.
        assertThat(humans.buyerSnapshot(buyer(), shop, conversation).session().status()).isEqualTo(Status.ACTIVE);
        UUID foreign = conversations.openWindow(201, shop).id();
        UUID foreignSession = humans.start(other(), shop, foreign, new StartRequest(UUID.randomUUID())).session().id();
        UUID foreignMessage = humans.buyerSend(other(), shop, foreign, foreignSession, new MessageRequest(UUID.randomUUID(), "foreign")).id();
        assertThatThrownBy(() -> humans.buyerMessages(buyer(), shop, conversation, foreignMessage)).isInstanceOf(HumanSupportException.class);
    }

    @Test void deletedConversationNeverRevealsHumanHistory() throws Exception {
        UUID session = start();
        humans.buyerSend(buyer(), shop, conversation, session, new MessageRequest(UUID.randomUUID(), "保存历史"));
        humans.claim(staff(999), shop, session);
        humans.buyerClose(buyer(), shop, conversation, session);
        conversations.deleteWindow(200, shop, conversation);
        assertThatThrownBy(() -> humans.buyerSnapshot(buyer(), shop, conversation)).isInstanceOf(HumanSupportException.class);
        assertThatThrownBy(() -> humans.merchantSnapshot(staff(999), shop, session, null)).isInstanceOf(HumanSupportException.class);
        assertThat(humans.queue(staff(999), shop, Status.CLOSED, null).items()).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM support_human_message WHERE session_id=(SELECT id FROM support_human_session WHERE public_id=:id)").param("id", session.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test void concurrentClaimAndMessageRetriesHaveExactlyOneOwnerAndOneMessage() throws Exception {
        UUID session = start();
        try (var workers = Executors.newFixedThreadPool(8)) {
            var a = CompletableFuture.supplyAsync(() -> tryClaim(999, session), workers);
            var b = CompletableFuture.supplyAsync(() -> tryClaim(998, session), workers);
            assertThat(List.of(a.join(), b.join())).containsExactlyInAnyOrder(true, false);
            UUID key = UUID.randomUUID();
            var requests = java.util.stream.IntStream.range(0, 8).mapToObj(i -> CompletableFuture.supplyAsync(
                    () -> humans.buyerSend(buyer(), shop, conversation, session, new MessageRequest(key, "只发送一次")).id(), workers)).toList();
            assertThat(requests.stream().map(CompletableFuture::join).distinct().toList()).hasSize(1);
            assertThatThrownBy(() -> humans.buyerSend(buyer(), shop, conversation, session, new MessageRequest(key, "不同内容"))).isInstanceOf(HumanSupportException.class);
            assertThat(humans.buyerSnapshot(buyer(), shop, conversation).messages()).hasSize(1);
        }
    }

    @Test void simultaneousAiAndHumanAdmissionAcrossDifferentConversationsCannotBothProceed() {
        UUID old = conversations.create(200, shop).id();
        UUID client = UUID.randomUUID();
        try (var workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(1);
            var ai = CompletableFuture.supplyAsync(() -> { await(ready); try { conversations.reserve(200, shop, old, new ConversationMessageRequest(client, "AI query", null)); return true; } catch (ConversationException rejected) { return false; } }, workers);
            var human = CompletableFuture.supplyAsync(() -> { await(ready); try { humans.start(buyer(), shop, conversation, new StartRequest(UUID.randomUUID())); return true; } catch (HumanSupportException rejected) { return false; } }, workers);
            ready.countDown();
            assertThat(List.of(ai.join(), human.join())).containsExactlyInAnyOrder(true, false);
        }
    }

    @Test void legacyLeaseBlocksHandoffAndStaleWorkerCannotClearNewLease() {
        CountDownLatch oldStarted = new CountDownLatch(1), releaseOld = new CountDownLatch(1);
        CountDownLatch newStarted = new CountDownLatch(1), releaseNew = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var old = CompletableFuture.supplyAsync(() -> gate.call(buyer(), shop, () -> { oldStarted.countDown(); await(releaseOld); return "old"; }), workers);
            await(oldStarted);
            assertThatThrownBy(() -> humans.start(buyer(), shop, conversation, new StartRequest(UUID.randomUUID()))).isInstanceOf(HumanSupportException.class);
            jdbc.sql("UPDATE support_chat_scope SET legacy_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE buyer_id=200 AND tenant_id=:shop").param("shop", shop).update();
            var newer = CompletableFuture.supplyAsync(() -> gate.call(buyer(), shop, () -> { newStarted.countDown(); await(releaseNew); return "new"; }), workers);
            await(newStarted);
            String token = jdbc.sql("SELECT legacy_processing_token FROM support_chat_scope WHERE buyer_id=200 AND tenant_id=:shop").param("shop", shop).query(String.class).single();
            releaseOld.countDown(); assertThatThrownBy(old::join).hasCauseInstanceOf(ConversationException.class);
            assertThat(jdbc.sql("SELECT legacy_processing_token FROM support_chat_scope WHERE buyer_id=200 AND tenant_id=:shop").param("shop", shop).query(String.class).single()).isEqualTo(token);
            releaseNew.countDown(); assertThat(newer.join()).isEqualTo("new");
            assertThat(humans.start(buyer(), shop, conversation, new StartRequest(UUID.randomUUID())).session().status()).isEqualTo(Status.WAITING);
        } finally { releaseOld.countDown(); releaseNew.countDown(); }
    }

    @Test void expiredAiTurnCanHandoffButItsLateReplyCannotAppend() {
        var request = new ConversationMessageRequest(UUID.randomUUID(), "old pending AI", null);
        var turn = conversations.reserve(200, shop, conversation, request);
        jdbc.sql("UPDATE support_chat_turn SET expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE client_message_id=:client")
                .param("client", request.clientMessageId().toString()).update();
        assertThat(humans.start(buyer(), shop, conversation, new StartRequest(UUID.randomUUID())).session().status()).isEqualTo(Status.WAITING);
        assertThatThrownBy(() -> conversations.succeed(200, shop, conversation, request,
                new ChatResponse("late result", ChatReplyType.TEXT, List.of(), null), turn.processingToken())).isInstanceOf(ConversationException.class);
        assertThat(conversations.messages(200, shop, conversation, null).messages()).isEmpty();
    }

    @Test void expiredLegacyResultCannotBeDeliveredAfterHumanHandoff() {
        assertThatThrownBy(() -> gate.call(buyer(), shop, () -> {
            jdbc.sql("UPDATE support_chat_scope SET legacy_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE buyer_id=200 AND tenant_id=:shop")
                    .param("shop", shop).update();
            humans.start(buyer(), shop, conversation, new StartRequest(UUID.randomUUID()));
            return "late legacy reply";
        })).isInstanceOf(ConversationException.class);
        assertThat(humans.buyerSnapshot(buyer(), shop, conversation).session().status()).isEqualTo(Status.WAITING);
    }

    @Test void handoffClearsOldStructuredContextAndPreservesImmutableAiSnapshots() {
        var request = new ConversationMessageRequest(UUID.randomUUID(), "查询之前的订单", null);
        var turn = conversations.reserve(200, shop, conversation, request);
        conversations.succeed(200, shop, conversation, request,
                new ChatResponse("旧订单快照", ChatReplyType.TEXT, List.of(), null)
                        .withContext(ConversationContextRef.order("OLD-ORDER", Instant.now().plusSeconds(300))), turn.processingToken());
        assertThat(conversations.window(200, shop, null).context().kind()).isEqualTo(ConversationContextKind.ORDER);
        UUID session = humans.start(buyer(), shop, conversation, new StartRequest(UUID.randomUUID())).session().id();
        assertThat(conversations.window(200, shop, null).context().kind()).isEqualTo(ConversationContextKind.NONE);
        humans.buyerClose(buyer(), shop, conversation, session);
        var next = new ConversationMessageRequest(UUID.randomUUID(), "新AI查询", null);
        var reservation = conversations.reserve(200, shop, conversation, next);
        assertThat(reservation.context().kind()).isEqualTo(ConversationContextKind.NONE);
        conversations.succeed(200, shop, conversation, next,
                new ChatResponse("新订单快照", ChatReplyType.TEXT, List.of(), null)
                        .withContext(ConversationContextRef.order("NEW-ORDER", Instant.now().plusSeconds(300))), reservation.processingToken());
        assertThat(conversations.window(200, shop, null).context().orderNo()).isEqualTo("NEW-ORDER");
        assertThat(conversations.messages(200, shop, conversation, null).messages()).hasSize(4);
        assertThat(conversations.messages(200, shop, conversation, null).messages().get(1).response().context().orderNo()).isEqualTo("OLD-ORDER");
    }

    @Test void startAndClosedMessageRetriesCannotCreateAnUnintendedNewCase() {
        UUID key = UUID.randomUUID();
        UUID first = humans.start(buyer(), shop, conversation, new StartRequest(key)).session().id();
        UUID otherConversation = conversations.create(200, shop).id();
        assertThatThrownBy(() -> humans.start(buyer(), shop, otherConversation, new StartRequest(UUID.randomUUID()))).isInstanceOf(HumanSupportException.class);
        assertThatThrownBy(() -> humans.start(buyer(), shop, conversation, new StartRequest(UUID.randomUUID()))).isInstanceOf(HumanSupportException.class);
        var message = new MessageRequest(UUID.randomUUID(), "安全重试");
        MessageView saved = humans.buyerSend(buyer(), shop, conversation, first, message);
        humans.buyerClose(buyer(), shop, conversation, first);
        assertThat(humans.buyerSend(buyer(), shop, conversation, first, message)).isEqualTo(saved);
        assertThatThrownBy(() -> humans.buyerSend(buyer(), shop, conversation, first, new MessageRequest(UUID.randomUUID(), "closed write"))).isInstanceOf(HumanSupportException.class);
        UUID second = humans.start(buyer(), shop, conversation, new StartRequest(UUID.randomUUID())).session().id();
        assertThat(humans.start(buyer(), shop, conversation, new StartRequest(key)).session().id()).isEqualTo(first);
        humans.buyerClose(buyer(), shop, conversation, first);
        assertThat(humans.buyerSnapshot(buyer(), shop, conversation).session().id()).isEqualTo(second);
        assertThat(humans.buyerSnapshot(buyer(), shop, conversation).session().status()).isEqualTo(Status.WAITING);
    }

    private UUID start() throws Exception { return UUID.fromString(postAs(buyerBase, "buyer", Map.of("requestId", UUID.randomUUID())).path("session").path("id").asText()); }
    private JsonNode postAs(String path, String user, Object body) throws Exception {
        var request = post(path).with(httpBasic(user, PASSWORD)).with(csrf());
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private boolean tryClaim(long id, UUID session) { try { humans.claim(staff(id), shop, session); return true; } catch (HumanSupportException denied) { return false; } }
    private static CustomerPrincipal buyer() { return new CustomerPrincipal("buyer", HASH, new CustomerIdentity(200), List.of(SecurityPermission.ORDER_READ)); }
    private static CustomerPrincipal other() { return new CustomerPrincipal("other", HASH, new CustomerIdentity(201), List.of(SecurityPermission.ORDER_READ)); }
    private static CustomerPrincipal staff(long id) { return new CustomerPrincipal(id == 999 ? "support" : "support-two", HASH, new CustomerIdentity(id), List.of(SecurityPermission.CHAT_SUPPORT), AccountType.MERCHANT_STAFF); }
    private static void await(CountDownLatch latch) { try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Latch timed out"); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); } }
}
