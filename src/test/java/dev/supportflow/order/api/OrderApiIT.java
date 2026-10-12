package dev.supportflow.order.api;

import dev.supportflow.SupportFlowApplication;
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
        registerAccount(registry, 0, "customer-200", 100, 200, "ORDER_READ");
        registerAccount(registry, 1, "customer-201", 100, 201, "ORDER_READ");
        registerAccount(
                registry, 2, "tenant-300-customer-200", 300, 200, "ORDER_READ"
        );
        registerAccount(registry, 3, "no-order-access", 100, 200, "");
    }

    private static void registerAccount(
            DynamicPropertyRegistry registry,
            int index,
            String username,
            long tenantId,
            long customerId,
            String permissions
    ) {
        String prefix = "supportflow.security.local.accounts[" + index + "].";
        registry.add(prefix + "username", () -> username);
        registry.add(prefix + "password-hash", () -> PASSWORD_HASH);
        registry.add(prefix + "identity.tenant-id", () -> tenantId);
        registry.add(prefix + "identity.customer-id", () -> customerId);
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
                        id, tenant_id, customer_id, order_no, status,
                        total_amount, currency, created_at, updated_at
                    ) VALUES
                    (1001, 100, 200, 'SF10001', 'SHIPPED', 199.90, 'CNY',
                     '2026-10-01 02:03:04.123', '2026-10-02 05:06:07.456'),
                    (1002, 300, 200, 'SF10001', 'PAID', 899.00, 'CNY',
                     '2026-10-03 01:02:03.123', '2026-10-04 04:05:06.456'),
                    (1003, 100, 201, 'SF20001', 'PAID', 49.50, 'CNY',
                     '2026-10-01 02:03:04.123', '2026-10-02 05:06:07.456'),
                    (1004, 100, 200, 'SF10002', 'COMPLETED', 9.90, 'CNY',
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
        expectUnauthorized(mockMvc.perform(get("/api/v1/orders/SF10001")));
    }

    @Test
    @DisplayName("错误密码返回统一401")
    void shouldRejectWrongPassword() throws Exception {
        expectUnauthorized(mockMvc.perform(
                get("/api/v1/orders/SF10001").with(
                        httpBasic("customer-200", LOGIN_PASSWORD + "-wrong")
                )
        ));
    }

    @Test
    @DisplayName("未知账号返回相同401")
    void shouldRejectUnknownAccount() throws Exception {
        expectUnauthorized(mockMvc.perform(
                get("/api/v1/orders/SF10001").with(
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
    @DisplayName("本人订单返回正确客户字段且不暴露内部归属ID")
    void shouldReturnOwnedOrderWithoutInternalIdentifiers() throws Exception {
        orderGet("customer-200", "SF10001")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.orderNo").value("SF10001"))
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
                .andExpect(jsonPath("$.customerId").doesNotExist());
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
    @DisplayName("客户编号相同但租户不同仍不能读取订单")
    void shouldHideOtherTenantOrder() throws Exception {
        expectNotFound(orderGet("tenant-300-customer-200", "SF10002"));
    }

    @Test
    @DisplayName("两个租户的相同订单号分别返回各自数据")
    void shouldDistinguishOrderNumberAcrossTenants() throws Exception {
        orderGet("customer-200", "SF10001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(199.90));

        orderGet("tenant-300-customer-200", "SF10001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.totalAmount").value(899.00));
    }

    @Test
    @DisplayName("伪造查询参数和身份请求头不能改变认证身份")
    void shouldIgnoreClientSuppliedIdentity() throws Exception {
        expectNotFound(mockMvc.perform(
                get("/api/v1/orders/SF10001")
                        .with(httpBasic("customer-201", LOGIN_PASSWORD))
                        .param("tenantId", "100")
                        .param("customerId", "200")
                        .header("X-Tenant-Id", "100")
                        .header("X-Customer-Id", "200")
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
        mockMvc.perform(post("/api/v1/orders/SF10001")
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
        return mockMvc.perform(
                get("/api/v1/orders/{orderNo}", orderNo)
                        .with(httpBasic(username, LOGIN_PASSWORD))
        );
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
