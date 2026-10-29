package dev.supportflow.assistant;

import dev.supportflow.SupportFlowApplication;
import dev.supportflow.testsupport.MerchantFixtures;
import dev.supportflow.testsupport.OrderVisibilityFixtures;
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

/** Account role, live merchant bindings and case ownership are independent authorization boundaries. */
@SpringBootTest(classes = SupportFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"spring.config.location=classpath:/application-day4-test.yml", "spring.main.banner-mode=off",
                "supportflow.ai.deepseek.enabled=false", "supportflow.after-sale.execution.enabled=false"})
@ActiveProfiles("local")
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class MerchantAuthorizationIT {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);
    private static final String DB_PASSWORD = UUID.randomUUID().toString();
    private static final String DATABASE = "supportflow_merchant_test";
    private static final String RUNTIME = "merchant_runtime";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName(DATABASE).withUsername("root").withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%")
            .withUrlParam("connectionTimeZone", "UTC").withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withUrlParam("sslMode", "DISABLED").withUrlParam("allowPublicKeyRetrieval", "true");
    private static boolean prepared;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    private MockMvc mvc;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        prepareDatabase();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME);
        registry.add("spring.datasource.password", () -> DB_PASSWORD);
        account(registry, 0, "buyer", 200, "ORDER_READ", null);
        account(registry, 1, "staff", 900, "ORDER_READ,AFTER_SALE_REVIEW", "MERCHANT_STAFF");
        account(registry, 2, "staff-no-review", 902, "ORDER_READ", "MERCHANT_STAFF");
        account(registry, 3, "buyer-with-review", 200, "ORDER_READ,AFTER_SALE_REVIEW", null);
        account(registry, 4, "unbound", 903, "ORDER_READ,AFTER_SALE_REVIEW", "MERCHANT_STAFF");
        account(registry, 5, "disabled", 904, "ORDER_READ,AFTER_SALE_REVIEW", "MERCHANT_STAFF");
        account(registry, 6, "other-merchant", 901, "ORDER_READ,AFTER_SALE_REVIEW", "MERCHANT_STAFF");
        account(registry, 7, "self", 200, "ORDER_READ,AFTER_SALE_REVIEW", "MERCHANT_STAFF");
        account(registry, 8, "identity-mismatch", 998, "ORDER_READ,AFTER_SALE_REVIEW", "MERCHANT_STAFF");
        account(registry, 9, "staff-only-review", 905, "AFTER_SALE_REVIEW", "MERCHANT_STAFF");
    }

    private static void account(DynamicPropertyRegistry registry, int index, String username, long buyerId,
                                String permissions, String accountType) {
        String prefix = "supportflow.security.local.accounts[" + index + "].";
        registry.add(prefix + "username", () -> username);
        registry.add(prefix + "password-hash", () -> HASH);
        registry.add(prefix + "identity.buyer-id", () -> buyerId);
        registry.add(prefix + "permissions", () -> permissions);
        if (accountType != null) registry.add(prefix + "account-type", () -> accountType);
    }

    private static synchronized void prepareDatabase() throws SQLException {
        if (prepared) return;
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true).createSchemas(false).load().migrate();
        try (Connection connection = admin(); PreparedStatement create = connection.prepareStatement(
                "CREATE USER 'merchant_runtime'@'%' IDENTIFIED BY ?"); Statement sql = connection.createStatement()) {
            create.setString(1, DB_PASSWORD);
            create.execute();
            for (String table : List.of("support_order", "support_shipment_event")) {
                sql.execute("GRANT SELECT ON " + DATABASE + "." + table + " TO '" + RUNTIME + "'@'%'");
            }
            for (String table : List.of("support_after_sale", "support_after_sale_execution")) {
                sql.execute("GRANT SELECT,INSERT,UPDATE ON " + DATABASE + "." + table + " TO '" + RUNTIME + "'@'%'");
            }
            for (String table : List.of("support_after_sale_audit", "support_simulated_refund")) {
                sql.execute("GRANT SELECT,INSERT ON " + DATABASE + "." + table + " TO '" + RUNTIME + "'@'%'");
            }
            MerchantFixtures.grantRuntimeRead(connection, DATABASE, RUNTIME);
            OrderVisibilityFixtures.grantRead(connection, DATABASE, RUNTIME);
            MerchantFixtures.merchant(connection, 1, "Merchant one", "ACTIVE");
            MerchantFixtures.merchant(connection, 2, "Merchant two", "ACTIVE");
            MerchantFixtures.shop(connection, 100, 1, "Authorized shop", "ACTIVE");
            MerchantFixtures.shop(connection, 101, 1, "Unassigned shop", "ACTIVE");
            MerchantFixtures.shop(connection, 200, 2, "Other merchant shop", "ACTIVE");
            MerchantFixtures.staff(connection, 1000, 1, "staff", 900, "ACTIVE");
            MerchantFixtures.staff(connection, 1001, 1, "staff-no-review", 902, "ACTIVE");
            MerchantFixtures.staff(connection, 1002, 1, "buyer-with-review", 200, "ACTIVE");
            MerchantFixtures.staff(connection, 1003, 1, "disabled", 904, "DISABLED");
            MerchantFixtures.staff(connection, 1004, 1, "self", 200, "ACTIVE");
            MerchantFixtures.staff(connection, 1005, 1, "identity-mismatch", 999, "ACTIVE");
            MerchantFixtures.staff(connection, 1006, 1, "staff-only-review", 905, "ACTIVE");
            MerchantFixtures.staff(connection, 2000, 2, "other-merchant", 901, "ACTIVE");
            for (long staff : List.of(1000L, 1001L, 1002L, 1003L, 1004L, 1005L, 1006L)) {
                MerchantFixtures.grant(connection, staff, 100, true);
            }
            MerchantFixtures.grant(connection, 2000, 200, true);
        }
        prepared = true;
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void shouldExposeOnlyTrustedAccountTypeAndUsername() throws Exception {
        mvc.perform(get("/api/v1/security/me").with(httpBasic("buyer", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountType").value("BUYER"))
                .andExpect(jsonPath("$.username").value("buyer"));
        String response = mvc.perform(get("/api/v1/security/me").with(httpBasic("staff", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountType").value("MERCHANT_STAFF"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("password", "buyerId", "staffId", "merchantId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"buyer", "buyer-with-review", "staff-no-review", "staff-only-review", "unbound", "disabled", "identity-mismatch"})
    void shouldRequireRolePermissionActiveBindingAndMatchingIdentity(String username) throws Exception {
        mvc.perform(get("/api/v1/merchant/shops").with(httpBasic(username, PASSWORD)))
                .andExpect(status().isForbidden());
        pending(username, 100).andExpect(status().isForbidden());
        decision(username, 100, UUID.randomUUID().toString(), Map.of("decision", "REJECT", "note", "check"), true)
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRequireAuthenticationAndReturnOnlyAssignedShops() throws Exception {
        mvc.perform(get("/api/v1/merchant/shops")).andExpect(status().isUnauthorized());
        JsonNode shops = body(mvc.perform(get("/api/v1/merchant/shops").with(httpBasic("staff", PASSWORD)))
                .andExpect(status().isOk()).andReturn());
        assertThat(shops.size()).isEqualTo(1);
        assertThat(shops.get(0).get("id").asLong()).isEqualTo(100);
        assertThat(shops.get(0).get("merchantId").asLong()).isEqualTo(1);
        assertThat(shops.toString()).doesNotContain("staffId", "linkedBuyerId");
        pending("staff", 101).andExpect(status().isForbidden());
        pending("staff", 200).andExpect(status().isForbidden());
        pending("staff", 999).andExpect(status().isForbidden());
    }

    @Test
    void shouldScopeBothQueueAndHistoryAndHideForeignCaseIdentifiers() throws Exception {
        Ticket mine = submit(100);
        Ticket unassigned = submit(101);
        Ticket foreign = submit(200);
        String queue = pending("staff", 100).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(queue).contains(mine.id()).doesNotContain(unassigned.id(), foreign.id());
        decision("staff", 100, unassigned.id(), rejection(), true).andExpect(status().isNotFound());
        decision("staff", 100, foreign.id(), rejection(), true).andExpect(status().isNotFound());
        decision("staff", 101, unassigned.id(), rejection(), true).andExpect(status().isForbidden());
        decision("other-merchant", 200, foreign.id(), rejection(), true).andExpect(status().isOk());
        decision("staff", 100, mine.id(), rejection(), true).andExpect(status().isOk());
        String history = mvc.perform(get("/api/v1/merchant/shops/100/after-sales/history")
                .with(httpBasic("staff", PASSWORD))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(history).contains(mine.id()).doesNotContain(unassigned.id(), foreign.id());
    }

    @Test
    void shouldRejectAnAccidentalCrossMerchantGrantRatherThanTrustTheGrantRowAlone() throws Exception {
        try (Connection connection = admin()) { MerchantFixtures.grant(connection, 1000, 200, true); }
        try {
            pending("staff", 200).andExpect(status().isForbidden());
            JsonNode shops = body(mvc.perform(get("/api/v1/merchant/shops").with(httpBasic("staff", PASSWORD)))
                    .andExpect(status().isOk()).andReturn());
            assertThat(shops.size()).isEqualTo(1);
            assertThat(shops.get(0).get("id").asLong()).isEqualTo(100);
        } finally {
            execute("UPDATE support_merchant_staff_shop_grant SET enabled=FALSE WHERE staff_id=1000 AND shop_id=200");
        }
    }

    @Test
    void shouldIgnoreClientSuppliedStaffIdentityAndShopOverrideAndRecordDatabaseStaffId() throws Exception {
        Ticket mine = submit(100);
        decision("staff", 100, mine.id(), Map.of("decision", "REJECT", "note", "Checked item",
                "shopId", 200, "merchantId", 2, "staffId", 2000, "reviewer", "other-merchant"), true)
                .andExpect(status().isOk()).andExpect(jsonPath("$.shopId").value(100))
                .andExpect(jsonPath("$.reviewer").value("staff"));
        assertThat(scalar("SELECT staff_id FROM support_after_sale_audit WHERE request_id=? AND action='REJECTED'", mine.id()))
                .isEqualTo(1000L);
        assertThat(scalar("SELECT COUNT(*) FROM support_after_sale_audit WHERE request_id=? AND action='SUBMITTED' AND staff_id IS NULL", mine.id()))
                .isEqualTo(1L);
    }

    @Test
    void shouldKeepCsrfAndLinkedBuyerSelfReviewProtection() throws Exception {
        Ticket mine = submit(100);
        decision("staff", 100, mine.id(), rejection(), false).andExpect(status().isForbidden());
        decision("self", 100, mine.id(), rejection(), true).andExpect(status().isForbidden());
        assertThat(scalar("SELECT COUNT(*) FROM support_after_sale_audit WHERE request_id=?", mine.id())).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM support_after_sale_execution WHERE request_id=?", mine.id())).isZero();
    }

    @Test
    void shouldApplyTheSameLiveShopBoundaryBeforeExecutionRetry() throws Exception {
        Ticket foreign = submit(200);
        mvc.perform(post("/api/v1/merchant/shops/100/after-sales/{id}/execution/retry", foreign.id())
                .with(httpBasic("staff", PASSWORD)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/merchant/shops/200/after-sales/{id}/execution/retry", foreign.id())
                .with(httpBasic("staff", PASSWORD)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/merchant/shops/200/after-sales/{id}/execution/retry", foreign.id())
                .with(httpBasic("buyer-with-review", PASSWORD)).with(csrf())).andExpect(status().isForbidden());
        assertThat(scalar("SELECT COUNT(*) FROM support_after_sale_execution WHERE request_id=?", foreign.id())).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM support_after_sale_audit WHERE request_id=?", foreign.id())).isEqualTo(1);
    }

    @Test
    void shouldDenyEveryLegacyGlobalReviewRouteEvenToAuthorizedStaff() throws Exception {
        for (String path : List.of("/api/v1/reviews/after-sales", "/api/v1/reviews/after-sales/history")) {
            mvc.perform(get(path).with(httpBasic("staff", PASSWORD))).andExpect(status().isForbidden());
        }
        for (String suffix : List.of("decision", "execution/retry")) {
            mvc.perform(post("/api/v1/reviews/after-sales/{id}/" + suffix, UUID.randomUUID())
                    .with(httpBasic("staff", PASSWORD)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"REJECT\",\"note\":\"check\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void shouldDenyStaffAllBuyerBusinessEndpointsEvenWhenItHasOrderRead() throws Exception {
        Ticket order = submit(100);
        for (String path : List.of("/api/v1/orders?shopId=100", "/api/v1/shops/100/orders/" + order.orderNo(),
                "/api/v1/shops/100/after-sales", "/api/v1/shops/100/chat/conversations")) {
            mvc.perform(get(path).with(httpBasic("staff", PASSWORD))).andExpect(status().isForbidden());
        }
        for (String path : List.of("/api/v1/shops/100/chat/messages", "/api/v1/shops/100/chat/conversations",
                "/api/v1/shops/100/orders/" + order.orderNo() + "/after-sales",
                "/api/v1/shops/100/orders/" + order.orderNo() + "/receipt-confirmation")) {
            mvc.perform(post(path).with(httpBasic("staff", PASSWORD)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void shouldObserveGrantRevocationOnTheNextRequestAndNotCachePreviousAccess() throws Exception {
        Ticket mine = submit(100);
        pending("staff", 100).andExpect(status().isOk());
        execute("UPDATE support_merchant_staff_shop_grant SET enabled=FALSE WHERE staff_id=1000 AND shop_id=100");
        try {
            pending("staff", 100).andExpect(status().isForbidden());
            decision("staff", 100, mine.id(), rejection(), true).andExpect(status().isForbidden());
            JsonNode shops = body(mvc.perform(get("/api/v1/merchant/shops").with(httpBasic("staff", PASSWORD)))
                    .andExpect(status().isOk()).andReturn());
            assertThat(shops.isEmpty()).isTrue();
        } finally {
            execute("UPDATE support_merchant_staff_shop_grant SET enabled=TRUE WHERE staff_id=1000 AND shop_id=100");
        }
        pending("staff", 100).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"support_merchant", "support_merchant_shop", "support_merchant_staff"})
    void shouldRejectNewRequestsWhenMerchantShopOrStaffIsDisabled(String table) throws Exception {
        String condition = switch (table) {
            case "support_merchant" -> "id=1";
            case "support_merchant_shop" -> "shop_id=100";
            case "support_merchant_staff" -> "id=1000";
            default -> throw new IllegalArgumentException();
        };
        execute("UPDATE " + table + " SET status='DISABLED' WHERE " + condition);
        try {
            pending("staff", 100).andExpect(status().isForbidden());
        } finally {
            execute("UPDATE " + table + " SET status='ACTIVE' WHERE " + condition);
        }
        pending("staff", 100).andExpect(status().isOk());
    }

    @Test
    void shouldRecheckGrantAfterWaitingForItsConcurrentRevocation() throws Exception {
        Ticket mine = submit(100);
        try (Connection revocation = admin(); PreparedStatement update = revocation.prepareStatement(
                "UPDATE support_merchant_staff_shop_grant SET enabled=FALSE WHERE staff_id=1000 AND shop_id=100")) {
            revocation.setAutoCommit(false);
            update.executeUpdate();
            CompletableFuture<MvcResult> waiting = CompletableFuture.supplyAsync(() -> {
                try { return decision("staff", 100, mine.id(), rejection(), true).andReturn(); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            try {
                assertThat(awaitLockWait("support_merchant_staff_shop_grant")).isTrue();
                assertThat(waiting).isNotDone();
                revocation.commit();
                assertThat(waiting.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(403);
                assertThat(scalar("SELECT COUNT(*) FROM support_after_sale_audit WHERE request_id=?", mine.id())).isEqualTo(1);
            } finally {
                revocation.rollback();
                if (!waiting.isDone()) waiting.get(30, TimeUnit.SECONDS);
                execute("UPDATE support_merchant_staff_shop_grant SET enabled=TRUE WHERE staff_id=1000 AND shop_id=100");
            }
        }
    }

    @Test
    void shouldKeepAuthorizedDecisionAndAuditAtomicBeforeALaterRevocation() throws Exception {
        Ticket mine = submit(100);
        try (Connection orderUpdate = admin(); PreparedStatement holdOrder = orderUpdate.prepareStatement(
                "UPDATE support_order SET total_amount=total_amount WHERE tenant_id=100 AND order_no=?")) {
            orderUpdate.setAutoCommit(false);
            holdOrder.setString(1, mine.orderNo());
            assertThat(holdOrder.executeUpdate()).isEqualTo(1);
            CompletableFuture<MvcResult> approval = CompletableFuture.supplyAsync(() -> {
                try {
                    return decision("staff", 100, mine.id(), Map.of("decision", "APPROVE", "note", "Verified item"), true)
                            .andReturn();
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            CompletableFuture<Void> revocation = null;
            try {
                assertThat(awaitLockWait("support_order")).isTrue();
                revocation = CompletableFuture.runAsync(() -> {
                    try { execute("UPDATE support_merchant_staff_shop_grant SET enabled=FALSE WHERE staff_id=1000 AND shop_id=100"); }
                    catch (SQLException failure) { throw new IllegalStateException(failure); }
                });
                assertThat(awaitLockWait("support_merchant_staff_shop_grant")).isTrue();
                assertThat(revocation).isNotDone();
                orderUpdate.commit();
                assertThat(approval.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
                revocation.get(30, TimeUnit.SECONDS);
                assertThat(scalar("SELECT staff_id FROM support_after_sale_audit WHERE request_id=? AND action='APPROVED'", mine.id()))
                        .isEqualTo(1000L);
                pending("staff", 100).andExpect(status().isForbidden());
            } finally {
                orderUpdate.rollback();
                if (!approval.isDone()) approval.get(30, TimeUnit.SECONDS);
                if (revocation != null && !revocation.isDone()) revocation.get(30, TimeUnit.SECONDS);
                execute("UPDATE support_merchant_staff_shop_grant SET enabled=TRUE WHERE staff_id=1000 AND shop_id=100");
            }
        }
    }

    @Test
    void shouldGiveRuntimeOnlyReadPermissionsOnAuthorizationTables() throws Exception {
        try (Connection limited = DriverManager.getConnection(MYSQL.getJdbcUrl(), RUNTIME, DB_PASSWORD)) {
            for (String table : List.of("support_merchant", "support_merchant_shop", "support_merchant_staff",
                    "support_merchant_staff_shop_grant")) {
                try (Statement statement = limited.createStatement()) {
                    assertThatThrownBy(() -> statement.executeUpdate("UPDATE " + table + " SET "
                            + (table.endsWith("_grant") ? "enabled=enabled" : "status=status") + " WHERE 1=0"))
                            .isInstanceOf(SQLException.class).hasMessageContaining("denied");
                }
            }
        }
    }

    private ResultActions pending(String username, long shop) throws Exception {
        return mvc.perform(get("/api/v1/merchant/shops/{shopId}/after-sales", shop).with(httpBasic(username, PASSWORD)));
    }

    private ResultActions decision(String username, long shop, String id, Map<String, ?> content, boolean withCsrf)
            throws Exception {
        var request = post("/api/v1/merchant/shops/{shopId}/after-sales/{id}/decision", shop, id)
                .with(httpBasic(username, PASSWORD)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(content));
        return mvc.perform(withCsrf ? request.with(csrf()) : request);
    }

    private Map<String, String> rejection() { return Map.of("decision", "REJECT", "note", "Checked in isolated test"); }

    private Ticket submit(long shop) throws Exception {
        String number = "SFMERCHANT" + UUID.randomUUID().toString().replace("-", "");
        execute("""
                INSERT INTO support_order (tenant_id,customer_id,buyer_id,order_no,status,total_amount,currency)
                VALUES (?,200,200,?,'PAID',199.90,'CNY')
                """, shop, number);
        JsonNode response = body(mvc.perform(post("/api/v1/shops/{shop}/orders/{order}/after-sales", shop, number)
                .with(httpBasic("buyer", PASSWORD)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Item damaged\"}")).andExpect(status().isOk()).andReturn());
        return new Ticket(response.get("id").asString(), number);
    }

    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }

    private long scalar(String sql, Object argument) throws SQLException {
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setObject(1, argument);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private boolean awaitLockWait(String table) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection connection = admin(); PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits waits
                JOIN performance_schema.data_locks requested ON requested.ENGINE_LOCK_ID=waits.REQUESTING_ENGINE_LOCK_ID
                WHERE requested.OBJECT_SCHEMA=? AND requested.OBJECT_NAME=? AND requested.LOCK_STATUS='WAITING'
                """)) {
            query.setString(1, DATABASE);
            query.setString(2, table);
            while (System.nanoTime() < until) {
                try (ResultSet rows = query.executeQuery()) {
                    rows.next();
                    if (rows.getLong(1) > 0) return true;
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        return false;
    }

    private static void execute(String sql, Object... arguments) throws SQLException {
        try (Connection connection = admin(); PreparedStatement update = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) update.setObject(index + 1, arguments[index]);
            update.executeUpdate();
        }
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private record Ticket(String id, String orderNo) { }
}
