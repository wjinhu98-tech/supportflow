package dev.supportflow.chat.api;

import dev.supportflow.SupportFlowApplication;
import dev.supportflow.assistant.tool.LogisticsTool;
import dev.supportflow.testsupport.KnowledgeFixtures;
import dev.supportflow.testsupport.OrderVisibilityFixtures;
import dev.supportflow.testsupport.HumanSupportFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Product words resolve only current-shop owned order snapshots; actual authentication, SQL and checkpoints. */
@SpringBootTest(classes = SupportFlowApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"spring.config.location=classpath:/application-day4-test.yml", "spring.main.banner-mode=off",
                "supportflow.ai.deepseek.enabled=false"})
@ActiveProfiles("local")
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class ProductOrderChatIT {
    private static final String DATABASE = "supportflow_product_chat_test";
    private static final String RUNTIME = "product_chat_runtime";
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);
    private static final String DATABASE_PASSWORD = UUID.randomUUID().toString();
    private static final AtomicLong SHOPS = new AtomicLong(90_000);
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName(DATABASE).withUsername("root").withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%").withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true").withUrlParam("sslMode", "DISABLED")
            .withUrlParam("allowPublicKeyRetrieval", "true");
    private static boolean prepared;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private LogisticsTool logistics;
    private MockMvc mvc;
    private JdbcClient inspection;
    private long shop;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        prepareDatabase();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME);
        registry.add("spring.datasource.password", () -> DATABASE_PASSWORD);
        account(registry, 0, "buyer", 200, "ORDER_READ", "BUYER");
        account(registry, 1, "other", 201, "ORDER_READ", "BUYER");
        account(registry, 2, "no-permission", 200, "", "BUYER");
        account(registry, 3, "merchant", 900, "ORDER_READ,AFTER_SALE_REVIEW", "MERCHANT_STAFF");
    }

    private static void account(DynamicPropertyRegistry registry, int index, String name, long buyer,
                                String permissions, String type) {
        String prefix = "supportflow.security.local.accounts[" + index + "].";
        registry.add(prefix + "username", () -> name);
        registry.add(prefix + "password-hash", () -> HASH);
        registry.add(prefix + "identity.buyer-id", () -> buyer);
        registry.add(prefix + "permissions", () -> permissions);
        registry.add(prefix + "account-type", () -> type);
    }

    private static synchronized void prepareDatabase() throws SQLException {
        if (prepared) return;
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").validateOnMigrate(true).baselineOnMigrate(false)
                .cleanDisabled(true).createSchemas(false).load().migrate();
        try (Connection connection = admin(); PreparedStatement create = connection.prepareStatement(
                "CREATE USER 'product_chat_runtime'@'%' IDENTIFIED BY ?"); Statement grants = connection.createStatement()) {
            create.setString(1, DATABASE_PASSWORD);
            create.execute();
            KnowledgeFixtures.grantRead(connection, DATABASE, RUNTIME);
            OrderVisibilityFixtures.grantRead(connection, DATABASE, RUNTIME);
            HumanSupportFixtures.grantAiAdmission(connection, DATABASE, RUNTIME);
            for (String table : List.of("support_order", "support_shipment_event", "support_merchant", "support_merchant_shop"))
                grants.execute("GRANT SELECT ON " + DATABASE + "." + table + " TO '" + RUNTIME + "'@'%'");
            for (String table : List.of("support_after_sale", "support_after_sale_execution", "support_chat_conversation",
                    "support_chat_turn", "support_agent_run", "support_agent_step"))
                grants.execute("GRANT SELECT, INSERT, UPDATE ON " + DATABASE + "." + table + " TO '" + RUNTIME + "'@'%'");
            for (String table : List.of("support_after_sale_audit", "support_simulated_refund", "support_chat_message"))
                grants.execute("GRANT SELECT, INSERT ON " + DATABASE + "." + table + " TO '" + RUNTIME + "'@'%'");
        }
        prepared = true;
    }

    @BeforeEach
    void setUp() {
        shop = SHOPS.addAndGet(10);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        inspection = JdbcClient.create(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    @Test
    void aUniqueProductReturnsOwnedOrderStatusAndBoundedPurchaseTimeProductPreview() throws Exception {
        var order = order(shop, 200, "SHIPPED", "199.90", List.of(
                item("蓝牙耳机", "黑色", 1), item("耳机收纳盒", "小号", 1),
                item("清洁布", null, 2), item("充电线", "1米", 1)));
        var response = assistant(send("buyer", shop, create("buyer", shop), id(), "我买的那副耳机发货了吗", null)
                .andExpect(status().isOk()).andReturn());
        assertThat(response.path("type").asString()).isEqualTo("ORDER_DETAILS");
        assertThat(response.path("reply").asString()).contains("整单状态", "已发货", order.number());
        assertThat(response.path("orders").size()).isEqualTo(1);
        JsonNode card = response.path("orders").get(0);
        assertThat(card.path("orderNo").asString()).isEqualTo(order.number());
        assertThat(card.path("items").size()).isEqualTo(3);
        assertThat(card.path("itemKinds").asInt()).isEqualTo(4);
        assertThat(card.path("items").get(0).path("productName").asString()).isEqualTo("蓝牙耳机");
        assertThat(card.path("items").get(0).path("specification").asString()).isEqualTo("黑色");
        assertThat(card.path("items").get(2).path("quantity").asInt()).isEqualTo(2);
        assertOrderContext(response, order.number());
        assertPrivateFieldsAbsent(response);
        JsonNode stateless = body(mvc.perform(post("/api/v1/shops/" + shop + "/chat/messages")
                .with(httpBasic("buyer", PASSWORD)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("message", "我买的那副耳机发货了吗"))))
                .andExpect(status().isOk()).andReturn());
        assertThat(stateless.path("type").asString()).isEqualTo("ORDER_DETAILS");
        assertThat(stateless.path("orders")).isEqualTo(response.path("orders"));
        assertThat(stateless.path("reply").asString()).contains("整单状态", "已发货");
        assertThat(inspection.sql("SELECT COUNT(*) FROM support_chat_message m JOIN support_chat_conversation c ON c.id=m.conversation_id WHERE c.tenant_id=:shop")
                .param("shop", shop).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void aProductLogisticsQuestionRunsTheActualFiveStepAgentAndBindsTheOriginalMessage() throws Exception {
        var order = order(shop, 200, "SHIPPED", "99.90", List.of(item("蓝牙耳机", "黑色", 1)));
        String conversation = create("buyer", shop), messageId = id(), message = "我买的耳机物流到哪了";
        JsonNode completed = body(send("buyer", shop, conversation, messageId, message, null)
                .andExpect(status().isOk()).andReturn());
        JsonNode response = assistant(completed);
        assertThat(response.path("reply").asString()).contains(order.number(), "已录入的商品测试物流");
        assertOrderContext(response, order.number());
        JsonNode run = view(shop, conversation, messageId);
        assertThat(run.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.path("attempts").asInt()).isEqualTo(5);
        assertThat(phases(run)).containsExactly("PLAN", "ORDER_LOOKUP", "PLAN", "LOGISTICS_LOOKUP", "PLAN");
        assertThat(run.path("budget").path("reportedTokens").asLong()).isZero();
        assertThat(storedRun(messageId).path("request").path("message").asString()).isEqualTo(message);
        assertThat(storedRun(messageId).path("selectedOrderNo").asString()).isEqualTo(order.number());
        assertThat(body(send("buyer", shop, conversation, messageId, message, null)
                .andExpect(status().isOk()).andReturn())).isEqualTo(completed);
        assertThat(messageCount(conversation)).isEqualTo(2);
    }

    @Test
    void repeatedPurchasesProduceChoiceCardsAndClearAnyOldOrderPointer() throws Exception {
        var first = order(shop, 200, "SHIPPED", "99.90", List.of(item("蓝牙耳机", "黑色", 1)));
        var second = order(shop, 200, "PAID", "59.90", List.of(item("蓝牙耳机", "白色", 1)));
        var response = assistant(send("buyer", shop, create("buyer", shop), id(), "耳机发货了吗", first.number())
                .andExpect(status().isOk()).andReturn());
        assertThat(response.path("type").asString()).isEqualTo("ORDER_SELECTION");
        assertThat(response.path("selectionIntent").asString()).isEqualTo("ORDER_STATUS");
        assertThat(response.path("orders").size()).isEqualTo(2);
        assertThat(numbers(response)).containsExactlyInAnyOrder(first.number(), second.number());
        assertThat(response.path("nextAfterId").isNull()).isTrue();
        assertEmptyContext(response);
        assertThat(runCount()).isZero();
    }

    @Test
    void logisticsChoicePersistsItsReadOnlyContinuationAndSelectingACardQueriesTheChosenOrder() throws Exception {
        var first = order(shop, 200, "SHIPPED", "99.90", List.of(item("蓝牙耳机", "黑色", 1)));
        var second = order(shop, 200, "SHIPPED", "109.90", List.of(item("蓝牙耳机", "白色", 1)));
        String conversation = create("buyer", shop);
        var response = assistant(send("buyer", shop, conversation, id(), "耳机物流到哪了", null)
                .andExpect(status().isOk()).andReturn());
        assertThat(response.path("selectionIntent").asString()).isEqualTo("LOGISTICS");
        assertThat(numbers(response)).containsExactlyInAnyOrder(first.number(), second.number());
        assertThat(history(shop, conversation).path("messages").get(1).path("response").path("selectionIntent").asString())
                .isEqualTo("LOGISTICS");
        assertThat(runCount()).isZero();
        var chosen = assistant(send("buyer", shop, conversation, id(), "查物流", second.number())
                .andExpect(status().isOk()).andReturn());
        assertThat(chosen.path("reply").asString()).contains(second.number(), "已录入的商品测试物流")
                .doesNotContain(first.number());
        assertOrderContext(chosen, second.number());
    }

    @Test
    void aNewProductNameOverridesBothAnOldUiSelectionAndTheRememberedOrder() throws Exception {
        var mouse = order(shop, 200, "PAID", "29.90", List.of(item("无线鼠标", "白色", 1)));
        var headset = order(shop, 200, "SHIPPED", "199.90", List.of(item("蓝牙耳机", "黑色", 1)));
        String conversation = create("buyer", shop);
        assistant(send("buyer", shop, conversation, id(), "咨询此订单", mouse.number()).andExpect(status().isOk()).andReturn());
        var response = assistant(send("buyer", shop, conversation, id(), "我买的耳机发货了吗", mouse.number())
                .andExpect(status().isOk()).andReturn());
        assertThat(numbers(response)).containsExactly(headset.number());
        assertThat(response.path("reply").asString()).contains("已发货").doesNotContain(mouse.number(), "29.90");
        assertOrderContext(response, headset.number());
    }

    @Test
    void anUnmatchedNewProductNeverReadsOrFallsBackToAStaleUnownedSelection() throws Exception {
        var headset = order(shop, 200, "SHIPPED", "199.90", List.of(item("蓝牙耳机", "黑色", 1)));
        String conversation = create("buyer", shop);
        send("buyer", shop, conversation, id(), "咨询此订单", headset.number()).andExpect(status().isOk());
        inspection.sql("UPDATE support_order SET buyer_id=201 WHERE id=:id").param("id", headset.id()).update();
        var response = assistant(send("buyer", shop, conversation, id(), "我的手柄发货了吗", headset.number())
                .andExpect(status().isOk()).andReturn());
        assertThat(response.path("type").asString()).isEqualTo("TEXT");
        assertThat(response.path("reply").asString()).contains("暂未找到", "手柄").doesNotContain(headset.number(), "199.90");
        assertThat(response.path("orders").isEmpty()).isTrue();
        assertEmptyContext(response);
        assertThat(runCount()).isZero();
    }

    @Test
    void historicalOrdersWithoutProductDataAreNotAssignedInventedNames() throws Exception {
        var historical = order(shop, 200, "SHIPPED", "89.90", null);
        String conversation = create("buyer", shop);
        var unmatched = assistant(send("buyer", shop, conversation, id(), "耳机发货了吗", null)
                .andExpect(status().isOk()).andReturn());
        assertThat(unmatched.path("reply").asString()).contains("历史订单", "商品信息");
        assertThat(unmatched.path("orders").isEmpty()).isTrue();
        assertEmptyContext(unmatched);
        var direct = assistant(send("buyer", shop, conversation, id(), "咨询此订单", historical.number())
                .andExpect(status().isOk()).andReturn());
        assertThat(direct.path("orders").get(0).path("items").isEmpty()).isTrue();
        assertThat(direct.path("orders").get(0).path("itemKinds").asInt()).isZero();
    }

    @Test
    void sameProductNameNeverCrossesCurrentShopOrAuthenticatedBuyerScope() throws Exception {
        var own = order(shop, 200, "SHIPPED", "199.90", List.of(item("蓝牙耳机", "黑色", 1)));
        var otherBuyer = order(shop, 201, "SHIPPED", "777.77", List.of(item("蓝牙耳机", "其他买家专属", 1)));
        var otherShop = order(shop + 1, 200, "SHIPPED", "888.88", List.of(item("蓝牙耳机", "其他店铺专属", 1)));
        var response = assistant(send("buyer", shop, create("buyer", shop), id(), "耳机发货了吗", null)
                .andExpect(status().isOk()).andReturn());
        assertThat(numbers(response)).containsExactly(own.number());
        assertThat(response.toString()).doesNotContain(otherBuyer.number(), otherShop.number(), "777.77", "888.88");
        var buyerScope = assistant(send("other", shop, create("other", shop), id(), "耳机发货了吗", null)
                .andExpect(status().isOk()).andReturn());
        assertThat(numbers(buyerScope)).containsExactly(otherBuyer.number());
        var shopScope = assistant(send("buyer", shop + 1, create("buyer", shop + 1), id(), "耳机发货了吗", null)
                .andExpect(status().isOk()).andReturn());
        assertThat(numbers(shopScope)).containsExactly(otherShop.number());
        assertPrivateFieldsAbsent(response);
    }

    @Test
    void productLookupStillRequiresOrderPermissionBuyerRoleAuthenticationAndCsrf() throws Exception {
        order(shop, 200, "SHIPPED", "99.90", List.of(item("蓝牙耳机", "黑色", 1)));
        String conversation = create("buyer", shop);
        send("no-permission", shop, conversation, id(), "耳机发货了吗", null).andExpect(status().isForbidden());
        send("merchant", shop, conversation, id(), "耳机发货了吗", null).andExpect(status().isForbidden());
        String payload = json.writeValueAsString(Map.of("clientMessageId", id(), "message", "耳机发货了吗"));
        mvc.perform(post(messagesUrl(shop, conversation)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(messagesUrl(shop, conversation)).with(httpBasic("buyer", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        assertThat(messageCount(conversation)).isZero();
        assertThat(runCount()).isZero();
    }

    @Test
    void productCareQuestionsRemainKnowledgeQueriesInsteadOfReadingTheSelectedOrder() throws Exception {
        var order = order(shop, 200, "SHIPPED", "199.90", List.of(item("蓝牙耳机", "黑色", 1)));
        publish(shop, "耳机保养", "学习资料：耳机应放在干燥收纳盒，具体以商品说明为准。");
        var response = assistant(send("buyer", shop, create("buyer", shop), id(), "耳机怎么保养", order.number())
                .andExpect(status().isOk()).andReturn());
        assertThat(response.path("knowledge").path("status").asString()).isEqualTo("ANSWERED");
        assertThat(response.path("reply").asString()).contains("干燥收纳盒").doesNotContain("199.90", order.number());
        assertThat(response.path("orders").isEmpty()).isTrue();
        assertThat(response.path("context").path("kind").asString()).isEqualTo("KNOWLEDGE");
        assertThat(runCount()).isZero();
    }

    @Test
    void aProductNameDoesNotAuthorizeSubmittingARefundOrInheritingThePreviousOrder() throws Exception {
        var order = order(shop, 200, "SHIPPED", "99.90", List.of(item("蓝牙耳机", "黑色", 1)));
        String conversation = create("buyer", shop);
        send("buyer", shop, conversation, id(), "耳机发货了吗", null).andExpect(status().isOk());
        var response = assistant(send("buyer", shop, conversation, id(), "耳机申请退款", null)
                .andExpect(status().isOk()).andReturn());
        assertThat(response.path("type").asString()).isEqualTo("AFTER_SALE_FORM");
        assertThat(response.path("orders").isEmpty()).isTrue();
        assertThat(response.path("reply").asString()).contains("退款原因", "选择").doesNotContain(order.number());
        assertEmptyContext(response);
        assertThat(inspection.sql("SELECT COUNT(*) FROM support_after_sale WHERE tenant_id=:shop").param("shop", shop).query(Long.class).single()).isZero();
    }

    @Test
    void oldStoredResponsesWithoutProductFieldsOrSelectionIntentRemainReadable() throws Exception {
        var order = order(shop, 200, "SHIPPED", "99.90", List.of(item("蓝牙耳机", "黑色", 1)));
        String conversation = create("buyer", shop);
        var original = body(send("buyer", shop, conversation, id(), "咨询此订单", order.number())
                .andExpect(status().isOk()).andReturn());
        String assistantMessage = original.path("messages").get(1).path("id").asString();
        inspection.sql("""
                UPDATE support_chat_message SET response_json=JSON_REMOVE(response_json,
                  '$.selectionIntent','$.orders[0].items','$.orders[0].itemKinds') WHERE public_id=:id
                """).param("id", assistantMessage).update();
        JsonNode cached = history(shop, conversation).path("messages").get(1).path("response");
        assertThat(cached.path("type").asString()).isEqualTo("ORDER_DETAILS");
        assertThat(cached.path("orders").get(0).path("items").isEmpty()).isTrue();
        assertThat(cached.path("orders").get(0).path("itemKinds").asInt()).isZero();
        assertThat(cached.path("selectionIntent").isNull()).isTrue();
        var fresh = assistant(send("buyer", shop, conversation, id(), "咨询此订单", order.number())
                .andExpect(status().isOk()).andReturn());
        assertThat(fresh.path("orders").get(0).path("items").get(0).path("productName").asString()).isEqualTo("蓝牙耳机");
    }

    @Test
    void recoveryKeepsTheResolvedOrderAndOriginalRequestEvenIfProductMatchesLaterChange() throws Exception {
        var mouse = order(shop, 200, "PAID", "29.90", List.of(item("无线鼠标", "白色", 1)));
        var headset = order(shop, 200, "SHIPPED", "99.90", List.of(item("蓝牙耳机", "黑色", 1)));
        String conversation = create("buyer", shop), messageId = id(), message = "耳机物流到哪了";
        failNextLogistics(headset);
        send("buyer", shop, conversation, messageId, message, mouse.number()).andExpect(status().isServiceUnavailable());
        JsonNode paused = view(shop, conversation, messageId);
        assertThat(paused.path("status").asString()).isEqualTo("PAUSED");
        assertThat(paused.path("attempts").asInt()).isEqualTo(4);
        JsonNode stored = storedRun(messageId);
        assertThat(stored.path("selectedOrderNo").asString()).isEqualTo(headset.number());
        assertThat(stored.path("request").path("message").asString()).isEqualTo(message);
        assertThat(stored.path("request").path("orderNo").asString()).isEqualTo(mouse.number());
        inspection.sql("UPDATE support_order SET product_items_json=:items WHERE id=:id")
                .param("items", json.writeValueAsString(List.of(item("游戏手柄", "黑色", 1)))).param("id", headset.id()).update();
        var newMatch = order(shop, 200, "SHIPPED", "109.90", List.of(item("蓝牙耳机", "白色", 1)));
        JsonNode recovered = body(mvc.perform(post(runUrl(shop, conversation, messageId) + "/resume")
                .with(httpBasic("buyer", PASSWORD)).with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(assistant(recovered).path("reply").asString()).contains(headset.number(), "已录入的商品测试物流")
                .doesNotContain(mouse.number(), newMatch.number());
        assertEmptyContext(assistant(recovered));
        assertThat(view(shop, conversation, messageId).path("runId")).isEqualTo(paused.path("runId"));
        assertThat(phases(view(shop, conversation, messageId)))
                .containsExactly("PLAN", "ORDER_LOOKUP", "PLAN", "LOGISTICS_LOOKUP", "LOGISTICS_LOOKUP", "PLAN");
        assertThat(body(send("buyer", shop, conversation, messageId, message, mouse.number())
                .andExpect(status().isOk()).andReturn())).isEqualTo(recovered);
        send("buyer", shop, conversation, messageId, message, headset.number()).andExpect(status().isConflict());
        assertThat(messageCount(conversation)).isEqualTo(2);
    }

    @Test
    void ownershipIsRecheckedBeforeAResolvedProductRunCanResume() throws Exception {
        var headset = order(shop, 200, "SHIPPED", "99.90", List.of(item("蓝牙耳机", "黑色", 1)));
        String conversation = create("buyer", shop), messageId = id();
        failNextLogistics(headset);
        send("buyer", shop, conversation, messageId, "耳机物流到哪了", null).andExpect(status().isServiceUnavailable());
        inspection.sql("UPDATE support_order SET buyer_id=201 WHERE id=:id").param("id", headset.id()).update();
        mvc.perform(post(runUrl(shop, conversation, messageId) + "/resume").with(httpBasic("buyer", PASSWORD)).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(messageCount(conversation)).isZero();
    }

    @Test
    void deletedOrdersDisappearFromNewQueriesButKeepTheirHistoricalChatEvidence() throws Exception {
        var deleted = order(shop, 200, "COMPLETED", "99.90", List.of(item("蓝牙耳机", "黑色", 1)));
        String conversation = create("buyer", shop);
        JsonNode before = assistant(send("buyer", shop, conversation, id(), "咨询此订单", deleted.number())
                .andExpect(status().isOk()).andReturn());
        assertThat(numbers(before)).containsExactly(deleted.number());
        var firstVisible = order(shop, 200, "COMPLETED", "29.90", List.of(item("陶瓷杯", "白色", 1)));
        var secondVisible = order(shop, 200, "CANCELLED", "39.90", List.of(item("棉质床单", "白色", 1)));
        hide(deleted);

        mvc.perform(get("/api/v1/shops/" + shop + "/orders/" + deleted.number()).with(httpBasic("buyer", PASSWORD)))
                .andExpect(status().isNotFound());
        JsonNode list = body(mvc.perform(get("/api/v1/orders").param("afterId", Long.toString(deleted.id() - 1))
                        .param("size", "1").with(httpBasic("buyer", PASSWORD)))
                .andExpect(status().isOk()).andReturn());
        assertThat(list.path("items").size()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("orderNo").asString()).isEqualTo(firstVisible.number());
        JsonNode next = body(mvc.perform(get("/api/v1/orders").param("afterId", list.path("nextAfterId").asString())
                        .param("size", "1").with(httpBasic("buyer", PASSWORD)))
                .andExpect(status().isOk()).andReturn());
        assertThat(next.path("items").get(0).path("orderNo").asString()).isEqualTo(secondVisible.number());
        assertThat(next.path("nextAfterId").isNull()).isTrue();
        JsonNode shopList = assistant(send("buyer", shop, conversation, id(), "我的订单", null)
                .andExpect(status().isOk()).andReturn());
        assertThat(numbers(shopList)).containsExactly(firstVisible.number(), secondVisible.number());
        JsonNode product = assistant(send("buyer", shop, conversation, id(), "耳机发货了吗", null)
                .andExpect(status().isOk()).andReturn());
        assertThat(numbers(product)).isEmpty();
        assertThat(product.path("reply").asString()).contains("暂未找到").doesNotContain(deleted.number());
        send("buyer", shop, conversation, id(), "咨询此订单", deleted.number()).andExpect(status().isNotFound());

        JsonNode historic = history(shop, conversation).path("messages").get(1).path("response");
        assertThat(numbers(historic)).containsExactly(deleted.number());
        assertThat(historic.path("orders").get(0).path("items").get(0).path("productName").asString())
                .isEqualTo("蓝牙耳机");
        assertThat(inspection.sql("SELECT COUNT(*) FROM support_order WHERE id=:id")
                .param("id", deleted.id()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void deletedOrderCannotResumeAnOlderPausedAgentRun() throws Exception {
        var headset = order(shop, 200, "SHIPPED", "99.90", List.of(item("蓝牙耳机", "黑色", 1)));
        String conversation = create("buyer", shop), messageId = id();
        failNextLogistics(headset);
        send("buyer", shop, conversation, messageId, "耳机物流到哪了", null).andExpect(status().isServiceUnavailable());
        // Simulate receipt completion followed by an eligible deletion while this old run is paused.
        inspection.sql("UPDATE support_order SET status='COMPLETED',received_at=UTC_TIMESTAMP(3) WHERE id=:id")
                .param("id", headset.id()).update();
        hide(headset);
        mvc.perform(post(runUrl(shop, conversation, messageId) + "/resume").with(httpBasic("buyer", PASSWORD)).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(messageCount(conversation)).isZero();
    }

    private void hide(Fixture order) {
        // Read-side fixture only; delete authorization/transactions are covered in OrderDeletionIT.
        inspection.sql("""
                INSERT INTO support_order_buyer_deletion(order_id,tenant_id,buyer_id,order_no,actor)
                VALUES (:id,:shop,200,:number,'read-side-test')
                """).param("id", order.id()).param("shop", shop).param("number", order.number()).update();
    }

    private Fixture order(long targetShop, long buyer, String status, String amount, List<Map<String, Object>> items) throws Exception {
        String number = "SFPRODUCT" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin(); PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO support_order(tenant_id,customer_id,buyer_id,order_no,status,total_amount,currency,product_items_json)
                VALUES (?,?,?,?,?,?,'CNY',?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, targetShop); insert.setLong(2, buyer); insert.setLong(3, buyer); insert.setString(4, number);
            insert.setString(5, status); insert.setBigDecimal(6, new BigDecimal(amount));
            insert.setString(7, items == null ? null : json.writeValueAsString(items));
            insert.executeUpdate();
            try (var keys = insert.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                long id = keys.getLong(1);
                if (status.equals("SHIPPED")) try (var event = connection.prepareStatement(
                        "INSERT INTO support_shipment_event(order_id,occurred_at,description) VALUES (?,UTC_TIMESTAMP(3),?)")) {
                    event.setLong(1, id); event.setString(2, "已录入的商品测试物流 · " + number); event.executeUpdate();
                }
                return new Fixture(id, number);
            }
        }
    }

    private void publish(long targetShop, String title, String content) throws SQLException {
        String article = id();
        inspection.sql("""
                INSERT INTO support_knowledge_article(id,tenant_id,title,keywords_json,content,status,version,last_revision,published_revision,publication_slot)
                VALUES (:id,:shop,:title,JSON_ARRAY('耳机','保养'),:content,'PUBLISHED',1,1,1,1)
                """).param("id", article).param("shop", targetShop).param("title", title).param("content", content).update();
        inspection.sql("""
                INSERT INTO support_knowledge_revision(article_id,revision,tenant_id,title,keywords_json,content,actor,staff_id)
                VALUES (:id,1,:shop,:title,JSON_ARRAY('耳机','保养'),:content,'synthetic-fixture',1000)
                """).param("id", article).param("shop", targetShop).param("title", title).param("content", content).update();
    }

    private static Map<String, Object> item(String name, String specification, int quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productName", name); item.put("specification", specification); item.put("quantity", quantity);
        return item;
    }

    private void failNextLogistics(Fixture order) {
        doThrow(new DataAccessResourceFailureException("SYNTHETIC_PRODUCT_LOGISTICS_FAILURE"))
                .doCallRealMethod().when(logistics).lookup(argThat(value -> value.orderNo().equals(order.number())));
    }

    private String create(String account, long targetShop) throws Exception {
        return body(mvc.perform(post(conversationsUrl(targetShop)).with(httpBasic(account, PASSWORD)).with(csrf()))
                .andExpect(status().isCreated()).andReturn()).path("id").asString();
    }

    private ResultActions send(String account, long targetShop, String conversation, String messageId, String text, String orderNo) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientMessageId", messageId); payload.put("message", text);
        if (orderNo != null) payload.put("orderNo", orderNo);
        return mvc.perform(post(messagesUrl(targetShop, conversation)).with(httpBasic(account, PASSWORD)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)));
    }

    private JsonNode history(long targetShop, String conversation) throws Exception {
        return body(mvc.perform(get(messagesUrl(targetShop, conversation)).with(httpBasic("buyer", PASSWORD)))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode view(long targetShop, String conversation, String messageId) throws Exception {
        return body(mvc.perform(get(runUrl(targetShop, conversation, messageId)).with(httpBasic("buyer", PASSWORD)))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode storedRun(String messageId) {
        return inspection.sql("SELECT selected_order_no,request_json FROM support_agent_run WHERE client_message_id=:id")
                .param("id", messageId).query((row, index) -> {
                    var value = json.createObjectNode();
                    value.put("selectedOrderNo", row.getString("selected_order_no"));
                    value.set("request", json.readTree(row.getString("request_json")));
                    return (JsonNode) value;
                }).single();
    }

    private JsonNode body(MvcResult result) { return json.readTree(result.getResponse().getContentAsByteArray()); }
    private JsonNode assistant(MvcResult result) { return assistant(body(result)); }
    private JsonNode assistant(JsonNode turn) {
        assertThat(turn.path("messages").size()).isEqualTo(2);
        return turn.path("messages").get(1).path("response");
    }

    private List<String> numbers(JsonNode response) {
        var values = new ArrayList<String>();
        for (JsonNode order : response.path("orders")) values.add(order.path("orderNo").asString());
        return values;
    }

    private List<String> phases(JsonNode run) {
        var values = new ArrayList<String>();
        for (JsonNode step : run.path("steps")) values.add(step.path("phase").asString());
        return values;
    }

    private static void assertOrderContext(JsonNode response, String number) {
        assertThat(response.path("context").path("kind").asString()).isEqualTo("ORDER");
        assertThat(response.path("context").path("orderNo").asString()).isEqualTo(number);
    }

    private static void assertEmptyContext(JsonNode response) {
        assertThat(response.path("context").path("kind").asString()).isEqualTo("NONE");
        assertThat(response.path("context").path("orderNo").isNull()).isTrue();
    }

    private static void assertPrivateFieldsAbsent(JsonNode response) {
        for (String field : List.of("buyerId", "customerId", "tenantId", "orderId", "product_items_json", "requestHash", "processingToken"))
            assertThat(response.toString()).doesNotContain('"' + field + '"');
    }

    private long runCount() {
        return inspection.sql("SELECT COUNT(*) FROM support_agent_run WHERE tenant_id=:shop").param("shop", shop).query(Long.class).single();
    }

    private long messageCount(String conversation) {
        return inspection.sql("""
                SELECT COUNT(*) FROM support_chat_message WHERE conversation_id=(
                SELECT id FROM support_chat_conversation WHERE public_id=:id)
                """).param("id", conversation).query(Long.class).single();
    }

    private static String id() { return UUID.randomUUID().toString(); }
    private static String conversationsUrl(long shop) { return "/api/v1/shops/" + shop + "/chat/conversations"; }
    private static String messagesUrl(long shop, String conversation) { return conversationsUrl(shop) + "/" + conversation + "/messages"; }
    private static String runUrl(long shop, String conversation, String messageId) { return conversationsUrl(shop) + "/" + conversation + "/runs/" + messageId; }
    private static Connection admin() throws SQLException { return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); }
    private record Fixture(long id, String number) { }
}
