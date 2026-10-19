package dev.supportflow.order.api;

import dev.supportflow.SupportFlowApplication;
import dev.supportflow.order.domain.OrderQueryLimits;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 Spring Boot、安全过滤链、应用服务与只读 MySQL 的整链验收。
 * 测试配置替换默认配置搜索，不加载开发者的私密配置。
 */
@SpringBootTest(
        classes = SupportFlowApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.config.location=classpath:/application-day4-test.yml",
                "spring.main.banner-mode=off"
        }
)
@ActiveProfiles("local")
@Testcontainers
class OrderApiIT {

    private static final String LOGIN_PASSWORD = UUID.randomUUID().toString();
    private static final String PASSWORD_HASH = PasswordEncoderFactories
            .createDelegatingPasswordEncoder().encode(LOGIN_PASSWORD);
    private static final String QUERY_PASSWORD = UUID.randomUUID().toString();

    @Container
    private static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.0.45")
                    .withDatabaseName("supportflow_api_test")
                    .withUsername("root")
                    .withPassword(UUID.randomUUID().toString())
                    .withEnv("MYSQL_ROOT_HOST", "%")
                    .withUrlParam("connectionTimeZone", "UTC")
                    .withUrlParam("forceConnectionTimeZoneToSession", "true")
                    .withUrlParam("sslMode", "DISABLED")
                    .withUrlParam("allowPublicKeyRetrieval", "true");

    private static boolean databasePrepared;

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void configureTestApplication(
            DynamicPropertyRegistry registry
    ) throws SQLException {
        prepareDatabase();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "order_reader");
        registry.add("spring.datasource.password", () -> QUERY_PASSWORD);
        registerAccount(registry, 0, "customer-200", 200, "ORDER_READ");
        registerAccount(registry, 1, "customer-201", 201, "ORDER_READ");
        registerAccount(registry, 2, "customer-999", 999, "ORDER_READ");
        registerAccount(registry, 3, "no-order-access", 200, "");
        registerAccount(registry, 4, "empty-buyer", 888, "ORDER_READ");
    }

    private static void registerAccount(
            DynamicPropertyRegistry registry,
            int index,
            String username,
            long buyerId,
            String permissions
    ) {
        String prefix = "supportflow.security.local.accounts[" + index + "].";
        registry.add(prefix + "username", () -> username);
        registry.add(prefix + "password-hash", () -> PASSWORD_HASH);
        registry.add(prefix + "identity.buyer-id", () -> buyerId);
        registry.add(prefix + "permissions", () -> permissions);
    }

    private static synchronized void prepareDatabase() throws SQLException {
        if (databasePrepared) {
            return;
        }

        Flyway.configure()
                .dataSource(
                        MYSQL.getJdbcUrl(),
                        MYSQL.getUsername(),
                        MYSQL.getPassword()
                )
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .createSchemas(false)
                .load()
                .migrate();

        try (
                Connection connection = DriverManager.getConnection(
                        MYSQL.getJdbcUrl(),
                        MYSQL.getUsername(),
                        MYSQL.getPassword()
                );
                PreparedStatement createUser = connection.prepareStatement(
                        "CREATE USER 'order_reader'@'%' IDENTIFIED BY ?"
                );
                Statement statement = connection.createStatement()
        ) {
            createUser.setString(1, QUERY_PASSWORD);
            createUser.execute();
            statement.execute(
                    """
                    GRANT SELECT
                    ON supportflow_api_test.support_order
                    TO 'order_reader'@'%'
                    """
            );
            statement.executeUpdate(
                    """
                    INSERT INTO support_order (
                        id, tenant_id, customer_id, buyer_id, order_no, status,
                        total_amount, currency, created_at, updated_at
                    ) VALUES
                    (1001, 100, 200, 200, 'SF10001', 'SHIPPED', 199.90, 'CNY',
                     '2026-10-01 02:03:04.123', '2026-10-02 05:06:07.456'),
                    (1002, 300, 777, 200, 'SF10001', 'PAID', 899.00, 'CNY',
                     '2026-10-03 01:02:03.123', '2026-10-04 04:05:06.456'),
                    (1003, 100, 201, 201, 'SF20001', 'PAID', 49.50, 'CNY',
                     '2026-10-01 02:03:04.123', '2026-10-02 05:06:07.456'),
                    (1004, 100, 200, 200, 'SF10002', 'COMPLETED', 9.90, 'CNY',
                     '2026-10-01 02:03:04.123', '2026-10-02 05:06:07.456'),
                    (1005, 300, 200, 999, 'FOREIGN', 'PAID', 66.00, 'CNY',
                     '2026-10-01 02:03:04.123', '2026-10-02 05:06:07.456'),
                    (1006, 300, 200, NULL, 'LEGACY', 'PAID', 77.00, 'CNY',
                     '2026-10-01 02:03:04.123', '2026-10-02 05:06:07.456')
                    """
            );
        }
        databasePrepared = true;
    }

    @BeforeEach
    void setUpWebLayer() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("欢迎接口允许匿名访问")
    void shouldAllowAnonymousWelcome() throws Exception {
        mockMvc.perform(get("/api/v1/chat/welcome"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        "SupportFlow 已启动，欢迎使用 AI 售后客服助手。"
                ));
    }

    @Test
    @DisplayName("未登录查询订单返回统一401")
    void shouldRejectAnonymousOrderQuery() throws Exception {
        expectUnauthorized(mockMvc.perform(get("/api/v1/shops/100/orders/SF10001")));
    }

    @Test
    @DisplayName("错误密码返回统一401")
    void shouldRejectWrongPassword() throws Exception {
        expectUnauthorized(mockMvc.perform(
                get("/api/v1/shops/100/orders/SF10001").with(
                        httpBasic("customer-200", LOGIN_PASSWORD + "-wrong")
                )
        ));
    }

    @Test
    @DisplayName("未知账号返回相同401")
    void shouldRejectUnknownAccount() throws Exception {
        expectUnauthorized(mockMvc.perform(
                get("/api/v1/shops/100/orders/SF10001").with(
                        httpBasic("unknown-customer", LOGIN_PASSWORD)
                )
        ));
    }

    @Test
    @DisplayName("已认证但没有订单读取权限返回403")
    void shouldRejectAccountWithoutOrderPermission() throws Exception {
        orderGet("no-order-access", "SF10001")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("本人订单返回商铺信息但不暴露买家及店铺客户编号")
    void shouldReturnOwnedOrderWithoutInternalIdentifiers() throws Exception {
        orderGet("customer-200", "SF10001")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.orderNo").value("SF10001"))
                .andExpect(jsonPath("$.shopId").value(100))
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.statusDescription").value("已发货"))
                .andExpect(jsonPath("$.totalAmount").value(199.90))
                .andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.createdAt").value(
                        "2026-10-01T02:03:04.123Z"
                ))
                .andExpect(jsonPath("$.updatedAt").value(
                        "2026-10-02T05:06:07.456Z"
                ))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.buyerId").doesNotExist());
    }

    @Test
    @DisplayName("不存在的订单返回统一404")
    void shouldReturnNotFoundForMissingOrder() throws Exception {
        expectNotFound(orderGet("customer-200", "SF_NOT_EXISTS"));
    }

    @Test
    @DisplayName("同一租户的其他客户不能读取订单")
    void shouldHideOtherCustomerOrder() throws Exception {
        expectNotFound(orderGet("customer-201", "SF10001"));
    }

    @Test
    @DisplayName("店铺内客户编号相同也不能冒充其他平台买家")
    void shouldHideOtherBuyerWithSameLocalCustomerNumber() throws Exception {
        expectNotFound(orderGet("customer-200", 300L, "FOREIGN"));
        expectNotFound(orderGet("customer-999", "SF10002"));
    }

    @Test
    @DisplayName("同一个买家可以读取两家商铺的同号订单")
    void shouldDistinguishOrderNumberAcrossTenants() throws Exception {
        orderGet("customer-200", "SF10001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(199.90));

        orderGet("customer-200", 300L, "SF10001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopId").value(300))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.totalAmount").value(899.00));
    }

    @Test
    @DisplayName("伪造查询参数和身份请求头不能改变认证身份")
    void shouldIgnoreClientSuppliedIdentity() throws Exception {
        expectNotFound(mockMvc.perform(
                get("/api/v1/shops/100/orders/SF10001")
                        .with(httpBasic("customer-201", LOGIN_PASSWORD))
                        .param("tenantId", "100")
                        .param("customerId", "200")
                        .param("buyerId", "200")
                        .header("X-Tenant-Id", "100")
                        .header("X-Customer-Id", "200")
                        .header("X-Buyer-Id", "200")
        ));
    }

    @Test
    @DisplayName("订单号超过64字符返回统一400")
    void shouldRejectOversizedOrderNumber() throws Exception {
        orderGet("customer-200", "A".repeat(65))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("纯空白订单号返回统一400")
    void shouldRejectBlankOrderNumber() throws Exception {
        orderGet("customer-200", " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("SQL注入式订单号不会扩大查询范围")
    void shouldBindOrderNumberAsData() throws Exception {
        expectNotFound(orderGet("customer-200", "SF10001' OR '1'='1"));
    }

    @Test
    @DisplayName("实际应用数据源使用只读账号和UTC会话")
    void shouldUseReadOnlyDatabaseAccountAndUtcSession() {
        jdbcClient.sql(
                "SELECT CURRENT_USER() AS db_user, @@session.time_zone AS zone"
        ).query((row, number) -> {
            assertThat(row.getString("db_user")).isEqualTo("order_reader@%");
            assertThat(row.getString("zone")).isEqualTo("+00:00");
            return true;
        }).single();

        assertThatThrownBy(() -> jdbcClient.sql(
                "UPDATE support_order SET status = status WHERE id = :id"
        ).param("id", 1001L).update())
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("已认证POST缺少CSRF仍被拒绝")
    void shouldKeepCsrfProtection() throws Exception {
        mockMvc.perform(post("/api/v1/chat/messages")
                        .with(httpBasic("customer-200", LOGIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"查询订单\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("客户端取得真实CSRF token后可以发送消息")
    void shouldAcceptPostWithIssuedCsrfToken() throws Exception {
        MvcResult csrfResult = mockMvc.perform(
                get("/api/v1/security/csrf")
                        .with(httpBasic("customer-200", LOGIN_PASSWORD))
        ).andExpect(status().isOk()).andReturn();

        JsonNode csrfBody = objectMapper.readTree(
                csrfResult.getResponse().getContentAsString()
        );
        MockHttpSession session = (MockHttpSession) csrfResult.getRequest()
                .getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(post("/api/v1/chat/messages")
                        .session(session)
                        .with(httpBasic("customer-200", LOGIN_PASSWORD))
                        .header(
                                csrfBody.get("headerName").asString(),
                                csrfBody.get("token").asString()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"查询订单\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(
                        "收到你的问题：查询订单"
                ));
    }

    @Test
    @DisplayName("加入认证后原有消息参数校验仍然有效")
    void shouldPreserveChatValidation() throws Exception {
        mockMvc.perform(post("/api/v1/chat/messages")
                        .with(httpBasic("customer-200", LOGIN_PASSWORD))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("消息不能为空"));
    }

    @Test
    @DisplayName("加入认证后原有JSON格式错误处理仍然有效")
    void shouldPreserveMalformedBodyHandling() throws Exception {
        mockMvc.perform(post("/api/v1/chat/messages")
                        .with(httpBasic("customer-200", LOGIN_PASSWORD))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("CSRF入口也要求完成身份认证")
    void shouldProtectCsrfEndpoint() throws Exception {
        expectUnauthorized(mockMvc.perform(get("/api/v1/security/csrf")));
    }

    @Test
    @DisplayName("未声明的请求入口默认拒绝")
    void shouldDenyUndeclaredRoute() throws Exception {
        mockMvc.perform(get("/api/v1/undeclared")
                        .with(httpBasic("customer-200", LOGIN_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("订单接口仅开放声明的GET操作")
    void shouldDenyUndeclaredOrderOperation() throws Exception {
        mockMvc.perform(post("/api/v1/shops/100/orders/SF10001")
                        .with(httpBasic("customer-200", LOGIN_PASSWORD))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("普通订单GET不创建认证session")
    void shouldNotPersistAuthenticationInSession() throws Exception {
        MvcResult result = orderGet("customer-200", "SF10001")
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    @DisplayName("错误派发保留原始状态而非改写成认证错误")
    void shouldAllowErrorDispatchWithoutChangingOriginalStatus()
            throws Exception {
        mockMvc.perform(get("/error")
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            return request;
                        })
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(
                                RequestDispatcher.ERROR_REQUEST_URI,
                                "/api/v1/missing"
                        ))
                .andExpect(status().isNotFound());
    }

    private ResultActions orderGet(
            String username,
            String orderNo
    ) throws Exception {
        return orderGet(username, 100L, orderNo);
    }

    private ResultActions orderGet(
            String username,
            long shopId,
            String orderNo
    ) throws Exception {
        return mockMvc.perform(
                get("/api/v1/shops/{shopId}/orders/{orderNo}", shopId, orderNo)
                        .with(httpBasic(username, LOGIN_PASSWORD))
        );
    }

    @Test
    @DisplayName("我的订单包含多家商铺，排除其他买家和归属未知的历史订单")
    void shouldListOnlyAuthenticatedBuyersOrdersAcrossShops() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .with(httpBasic("customer-200", LOGIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].shopId").value(100))
                .andExpect(jsonPath("$.items[1].shopId").value(300))
                .andExpect(jsonPath("$.items[0].orderNo").value("SF10001"))
                .andExpect(jsonPath("$.items[1].orderNo").value("SF10001"))
                .andExpect(jsonPath("$.items[2].orderNo").value("SF10002"))
                .andExpect(jsonPath("$.items[0].buyerId").doesNotExist())
                .andExpect(jsonPath("$.items[0].customerId").doesNotExist())
                .andExpect(jsonPath("$.items[0].id").doesNotExist())
                .andExpect(jsonPath("$.nextAfterId").value(
                        org.hamcrest.Matchers.nullValue()
                ));
    }

    @Test
    @DisplayName("连续游标分页跨越商铺且不重复或遗漏订单")
    void shouldPageAcrossShopsWithoutDuplicates() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/v1/orders")
                        .param("size", "1")
                        .with(httpBasic("customer-200", LOGIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].shopId").value(100))
                .andReturn();
        long cursor = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("nextAfterId").asLong();

        mockMvc.perform(get("/api/v1/orders")
                        .param("afterId", Long.toString(cursor))
                        .param("size", "1")
                        .with(httpBasic("customer-200", LOGIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].shopId").value(300))
                .andExpect(jsonPath("$.nextAfterId").value(1002));

        mockMvc.perform(get("/api/v1/orders")
                        .param("afterId", "1002")
                        .param("size", "1")
                        .with(httpBasic("customer-200", LOGIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderNo").value("SF10002"))
                .andExpect(jsonPath("$.nextAfterId").value(
                        org.hamcrest.Matchers.nullValue()
                ));
    }

    @Test
    @DisplayName("修改买家参数、身份头或他人的游标不能扩大订单范围")
    void shouldKeepOwnershipWhenListIdentityAndCursorAreForged() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .param("buyerId", "200")
                        .param("customerId", "200")
                        .param("afterId", "1001")
                        .header("X-Buyer-Id", "200")
                        .with(httpBasic("customer-201", LOGIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].orderNo").value("SF20001"));
    }

    @Test
    @DisplayName("历史订单没有可信买家映射时不可读取")
    void shouldHideUnmappedLegacyOrder() throws Exception {
        expectNotFound(orderGet("customer-200", 300L, "LEGACY"));
    }

    @Test
    @DisplayName("没有订单的买家得到空列表")
    void shouldReturnEmptyOrderList() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .with(httpBasic("empty-buyer", LOGIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextAfterId").value(
                        org.hamcrest.Matchers.nullValue()
                ));
    }

    @Test
    @DisplayName("跨商铺列表同样要求认证与订单读取权限")
    void shouldProtectOrderList() throws Exception {
        expectUnauthorized(mockMvc.perform(get("/api/v1/orders")));
        mockMvc.perform(get("/api/v1/orders")
                        .with(httpBasic("no-order-access", LOGIN_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("接口允许最大页大小，超出时返回与分页规则一致的错误提示")
    void shouldEnforceSharedPageSizeLimit() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .param("size", Integer.toString(OrderQueryLimits.MAX_PAGE_SIZE))
                        .with(httpBasic("customer-200", LOGIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));

        mockMvc.perform(get("/api/v1/orders")
                        .param("size", Integer.toString(OrderQueryLimits.MAX_PAGE_SIZE + 1))
                        .with(httpBasic("customer-200", LOGIN_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value(
                        "每页最多返回" + OrderQueryLimits.MAX_PAGE_SIZE + "条"));
    }

    @Test
    @DisplayName("分页参数越界或格式错误返回400")
    void shouldValidatePageParameters() throws Exception {
        for (String size : new String[]{"0", "-1", Integer.toString(OrderQueryLimits.MAX_PAGE_SIZE + 1), "abc"}) {
            mockMvc.perform(get("/api/v1/orders")
                            .param("size", size)
                            .with(httpBasic("customer-200", LOGIN_PASSWORD)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        for (String cursor : new String[]{"-1", "abc", "9223372036854775808"}) {
            mockMvc.perform(get("/api/v1/orders")
                            .param("afterId", cursor)
                            .with(httpBasic("customer-200", LOGIN_PASSWORD)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
    }

    @Test
    @DisplayName("商铺定位必须为正数，切换商铺仍检查买家归属")
    void shouldValidateShopIdAndOwnership() throws Exception {
        orderGet("customer-200", 0L, "SF10001")
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/shops/abc/orders/SF10001")
                        .with(httpBasic("customer-200", LOGIN_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        expectNotFound(orderGet("customer-201", 300L, "SF10001"));
        expectNotFound(orderGet("customer-200", 999L, "SF10001"));
    }

    @Test
    @DisplayName("不带商铺的旧详情入口不再开放以避免同号歧义")
    void shouldDenyAmbiguousLegacyDetailRoute() throws Exception {
        mockMvc.perform(get("/api/v1/orders/SF10001")
                        .with(httpBasic("customer-200", LOGIN_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("聊天查单返回本人订单的真实状态和金额")
    void shouldReplyWithOwnedOrderDetailsFromChat() throws Exception {
        chatPost("customer-200", "查订单 100 SF10001")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.reply").value(
                        "商铺 100 的订单 SF10001：已发货，订单金额 199.90 CNY。"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.buyerId").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    @Test
    @DisplayName("聊天按输入商铺区分同一买家的同号订单")
    void shouldReplyWithRequestedShopOrderFromChat() throws Exception {
        chatPost("customer-200", "查订单 300 SF10001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(
                        "商铺 300 的订单 SF10001：待发货，订单金额 899.00 CNY。"));
    }

    @Test
    @DisplayName("聊天不能读取其他买家或归属未知的历史订单")
    void shouldHideOtherBuyersOrdersInChat() throws Exception {
        expectNotFound(chatPost("customer-201", "查订单 100 SF10001"));
        expectNotFound(chatPost("customer-200", "查订单 300 FOREIGN"));
        expectNotFound(chatPost("customer-200", "查订单 300 LEGACY"));
    }

    @Test
    @DisplayName("聊天查不到订单时沿用统一404响应")
    void shouldReturnNotFoundForMissingOrderInChat() throws Exception {
        expectNotFound(chatPost("customer-200", "查订单 100 SF_NOT_EXISTS"));
    }

    @Test
    @DisplayName("聊天请求体参数和请求头中的伪造身份不能改变认证买家")
    void shouldKeepAuthenticatedBuyerWhenChatIdentityIsForged() throws Exception {
        expectNotFound(mockMvc.perform(post("/api/v1/chat/messages")
                .with(httpBasic("customer-201", LOGIN_PASSWORD))
                .with(csrf())
                .param("buyerId", "200")
                .param("customerId", "200")
                .param("tenantId", "100")
                .header("X-Buyer-Id", "200")
                .header("X-Customer-Id", "200")
                .header("X-Tenant-Id", "100")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "message", "查订单 100 SF10001",
                        "buyerId", 200L,
                        "customerId", 200L,
                        "tenantId", 100L
                )))));
    }

    @Test
    @DisplayName("无订单权限仍可普通聊天但不能通过聊天查单")
    void shouldEnforceOrderPermissionOnlyForOrderChat() throws Exception {
        chatPost("no-order-access", "你好")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("收到你的问题：你好"));

        chatPost("no-order-access", "查订单 100 SF10001")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("匿名聊天查单在提供CSRF后仍被身份认证拒绝")
    void shouldRejectAnonymousOrderChat() throws Exception {
        expectUnauthorized(mockMvc.perform(post("/api/v1/chat/messages")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(chatBody("查订单 100 SF10001"))));
    }

    @Test
    @DisplayName("认证客户聊天查单缺少CSRF仍返回403")
    void shouldRejectOrderChatWithoutCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/chat/messages")
                        .with(httpBasic("customer-200", LOGIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody("查订单 100 SF10001")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("不完整或非法查单指令返回统一格式提示")
    void shouldRejectMalformedOrderChatCommands() throws Exception {
        String[] commands = {
                "查订单",
                "查订单 100",
                "查订单 abc SF10001",
                "查订单 0 SF10001",
                "查订单 -1 SF10001",
                "查订单 1.5 SF10001",
                "查订单 9223372036854775808 SF10001",
                "查订单 100 " + "A".repeat(65),
                "查订单 100 SF10001 extra",
                "查订单100 SF10001"
        };

        for (String command : commands) {
            chatPost("customer-200", command)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                    .andExpect(jsonPath("$.message").value(
                            "查单格式：查订单 <商铺ID> <订单号>，例如：查订单 100 SF10001"));
        }
    }

    @Test
    @DisplayName("聊天消息超过1000字符时拒绝请求")
    void shouldRejectOversizedChatMessage() throws Exception {
        chatPost("customer-200", "A".repeat(1001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("聊天消息恰好1000字符时仍可正常处理")
    void shouldAcceptMaximumLengthChatMessage() throws Exception {
        String message = "A".repeat(1000);

        chatPost("customer-200", message)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("收到你的问题：" + message));
    }

    @Test
    @DisplayName("恰好64字符的订单号进入正常查询而非格式拒绝")
    void shouldAcceptMaximumLengthOrderNumberInChat() throws Exception {
        expectNotFound(chatPost("customer-200", "查订单 100 " + "A".repeat(64)));
    }

    private ResultActions chatPost(String username, String message) throws Exception {
        return mockMvc.perform(post("/api/v1/chat/messages")
                .with(httpBasic(username, LOGIN_PASSWORD))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(chatBody(message)));
    }

    private String chatBody(String message) throws Exception {
        return objectMapper.writeValueAsString(Map.of("message", message));
    }

    private void expectUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Basic realm=\"SupportFlow\", charset=\"UTF-8\""
                ))
                .andExpect(jsonPath("$.code").value(
                        "AUTHENTICATION_REQUIRED"
                ))
                .andExpect(jsonPath("$.message").value("请先完成身份认证"));
    }

    private void expectNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("订单不存在或无权访问"));
    }
}
