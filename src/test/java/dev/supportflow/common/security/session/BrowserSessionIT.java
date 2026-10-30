package dev.supportflow.common.security.session;

import dev.supportflow.SupportFlowApplication;
import dev.supportflow.common.security.database.DatabaseAccountAdministration;
import dev.supportflow.common.security.local.LocalCustomerAccount;
import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real MySQL plus the complete filter chain; runtime connection has SELECT only on account storage. */
@SpringBootTest(classes = SupportFlowApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"spring.config.location=classpath:/application-day4-test.yml", "spring.main.banner-mode=off",
                "supportflow.security.authentication.mode=database", "supportflow.security.authentication.basic-enabled=false",
                "supportflow.security.authentication.session-timeout=10m"})
@ActiveProfiles("local")
@Testcontainers
class BrowserSessionIT {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String PASSWORD_HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);
    private static final String READER_PASSWORD = UUID.randomUUID().toString();

    @Container private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName("browser_session_test").withUsername("root").withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%").withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("sslMode", "DISABLED");

    private static JdbcTemplate operator;
    private static boolean prepared;
    @Autowired private WebApplicationContext applicationContext;
    @Autowired private JdbcTemplate runtime;
    @Autowired private ObjectMapper mapper;
    private MockMvc mvc;
    private DatabaseAccountAdministration accounts;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        prepare();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "session_reader");
        registry.add("spring.datasource.password", () -> READER_PASSWORD);
    }

    private static synchronized void prepare() {
        if (prepared) return;
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .cleanDisabled(true).load().migrate();
        operator = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        operator.execute("CREATE USER 'session_reader'@'%' IDENTIFIED BY '" + READER_PASSWORD + "'");
        operator.execute("GRANT SELECT ON browser_session_test.support_security_account TO 'session_reader'@'%'");
        operator.update("INSERT INTO support_merchant (id, name) VALUES (1, 'Browser test merchant')");
        operator.update("INSERT INTO support_merchant_shop (shop_id, merchant_id, name) VALUES (700, 1, 'Browser test shop')");
        operator.update("""
                INSERT INTO support_merchant_staff (id, merchant_id, username, linked_buyer_id)
                VALUES (1, 1, 'browser-merchant', 700)
                """);
        operator.update("INSERT INTO support_merchant_staff_shop_grant (staff_id, shop_id, enabled) VALUES (1, 700, TRUE)");
        prepared = true;
    }

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).apply(springSecurity()).build();
        accounts = new DatabaseAccountAdministration(operator);
        accounts.importMissingConfiguredAccounts(List.of(
                account("browser-buyer", AccountType.BUYER, 200, Set.of(SecurityPermission.ORDER_READ)),
                account("browser-merchant", AccountType.MERCHANT_STAFF, 700, Set.of(SecurityPermission.KNOWLEDGE_MANAGE))));
        operator.update("""
                UPDATE support_security_account SET enabled = TRUE, password_hash = ?, chat_support = FALSE,
                  security_version = security_version + 1
                WHERE username IN ('browser-buyer', 'browser-merchant')
                """, PASSWORD_HASH);
        operator.update("UPDATE support_merchant SET status = 'ACTIVE' WHERE id = 1");
        operator.update("UPDATE support_merchant_shop SET status = 'ACTIVE', merchant_id = 1 WHERE shop_id = 700");
        operator.update("UPDATE support_merchant_staff SET status = 'ACTIVE', linked_buyer_id = 700 WHERE id = 1");
        operator.update("UPDATE support_merchant_staff_shop_grant SET enabled = TRUE WHERE staff_id = 1 AND shop_id = 700");
    }

    @Test void authenticatesWithRotatedSessionAndNoPasswordOrInternalIdentityInResponse() throws Exception {
        var session = login("browser-buyer", PASSWORD);
        assertThat(session.getMaxInactiveInterval()).isEqualTo(600);
        var context = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getCredentials()).isNull();
        var principal = (CustomerPrincipal) context.getAuthentication().getPrincipal();
        assertThat(principal.getPassword()).isNull();
        assertThat(principal.getIdentity().buyerId()).isEqualTo(200);
        var response = mvc.perform(get("/api/v1/security/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("browser-buyer"))
                .andExpect(jsonPath("$.accountType").value("BUYER")).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("password", "principalId", "buyerId", "securityVersion");
    }

    @Test void rejectsBasicWhenDisabledAndUsesGenericCredentialFailures() throws Exception {
        mvc.perform(get("/api/v1/security/me").with(httpBasic("browser-buyer", PASSWORD)))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("WWW-Authenticate"));
        for (String username : List.of("browser-buyer", "unknown-account")) {
            var csrf = csrf(null);
            mvc.perform(post("/api/v1/security/login").session(csrf.session()).header(csrf.header(), csrf.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("username", username, "password", "wrong"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
    }

    @Test void loginAndLogoutBothRequireCsrfAndLogoutInvalidatesServerSession() throws Exception {
        mvc.perform(post("/api/v1/security/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        var session = login("browser-buyer", PASSWORD);
        mvc.perform(post("/api/v1/security/logout").session(session)).andExpect(status().isForbidden());
        var csrf = csrf(session);
        mvc.perform(post("/api/v1/security/logout").session(session).header(csrf.header(), csrf.token()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/security/me")).andExpect(status().isUnauthorized());
    }

    @Test void preLoginCsrfCannotBeReusedAfterLoginAndFailedReloginClearsOldSession() throws Exception {
        var preLogin = csrf(null);
        var result = mvc.perform(post("/api/v1/security/login").session(preLogin.session())
                        .header(preLogin.header(), preLogin.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", "browser-buyer", "password", PASSWORD))))
                .andExpect(status().isNoContent()).andReturn();
        var authenticatedSession = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(post("/api/v1/security/logout").session(authenticatedSession)
                        .header(preLogin.header(), preLogin.token())).andExpect(status().isForbidden());
        var authenticatedCsrf = csrf(authenticatedSession);
        mvc.perform(post("/api/v1/security/login").session(authenticatedSession)
                        .header(authenticatedCsrf.header(), authenticatedCsrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", "browser-buyer", "password", "wrong"))))
                .andExpect(status().isUnauthorized());
        assertThat(authenticatedSession.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/security/me")).andExpect(status().isUnauthorized());
    }

    @Test void disableImmediatelyRevokesExistingSessionAndReenableDoesNotReviveIt() throws Exception {
        var session = login("browser-buyer", PASSWORD);
        assertThat(accounts.disable("browser-buyer")).isTrue();
        mvc.perform(get("/api/v1/security/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        assertThat(accounts.enable("browser-buyer")).isTrue();
        mvc.perform(get("/api/v1/security/me")).andExpect(status().isUnauthorized());
        login("browser-buyer", PASSWORD);
    }

    @Test void changingPasswordRevokesExistingSessionAndOldPassword() throws Exception {
        var session = login("browser-buyer", PASSWORD);
        String changed = UUID.randomUUID().toString();
        assertThat(accounts.changePassword("browser-buyer", changed,
                PasswordEncoderFactories.createDelegatingPasswordEncoder())).isTrue();
        mvc.perform(get("/api/v1/security/me").session(session)).andExpect(status().isUnauthorized());
        var csrf = csrf(null);
        mvc.perform(post("/api/v1/security/login").session(csrf.session()).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", "browser-buyer", "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
        login("browser-buyer", changed);
    }

    @Test void explicitSessionInvalidationWorksWithoutChangingPassword() throws Exception {
        var session = login("browser-buyer", PASSWORD);
        assertThat(accounts.invalidateSessions("browser-buyer")).isTrue();
        mvc.perform(get("/api/v1/security/me").session(session)).andExpect(status().isUnauthorized());
        login("browser-buyer", PASSWORD);
    }

    @Test void staleTabCannotReadOrLogoutNewIdentity() throws Exception {
        var session = login("browser-merchant", PASSWORD);
        mvc.perform(get("/api/v1/security/me").session(session).header("X-Session-Account", "browser-buyer"))
                .andExpect(status().isUnauthorized());
        var csrf = csrf(session);
        mvc.perform(post("/api/v1/security/logout").session(session).header(csrf.header(), csrf.token())
                        .header("X-Session-Account", "browser-buyer"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/security/me").session(session).header("X-Session-Account", "browser-merchant"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountType").value("MERCHANT_STAFF"));
        mvc.perform(get("/api/v1/orders").session(session)).andExpect(status().isForbidden());
    }

    @Test void bootstrapDoesNotOverwriteExistingAccountAndRuntimeCannotModifyAccounts() {
        accounts.disable("browser-buyer");
        int imported = accounts.importMissingConfiguredAccounts(List.of(account("browser-buyer", AccountType.MERCHANT_STAFF,
                999, Set.of(SecurityPermission.AFTER_SALE_REVIEW))));
        assertThat(imported).isZero();
        var existing = operator.queryForMap("SELECT principal_id, account_type, enabled FROM support_security_account WHERE username = ?",
                "browser-buyer");
        assertThat(existing.get("principal_id")).isEqualTo(200L);
        assertThat(existing.get("account_type")).isEqualTo("BUYER");
        assertThat(existing.get("enabled")).isEqualTo(false);
        assertThatThrownBy(() -> runtime.update("UPDATE support_security_account SET enabled = TRUE"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> new DatabaseAccountAdministration(runtime).invalidateSessions("browser-buyer"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test void chatPermissionIsExplicitAndEnablingItRevokesOldSessionsExactlyOnce() throws Exception {
        var session = login("browser-merchant", PASSWORD);
        mvc.perform(get("/api/v1/security/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.manageKnowledge").value(true))
                .andExpect(jsonPath("$.capabilities.serveChats").value(false));
        mvc.perform(get("/api/v1/merchant/shops/700/support-sessions").session(session))
                .andExpect(status().isForbidden());
        long before = securityVersion("browser-merchant");
        assertThat(accounts.enableChatSupport("browser-merchant")).isTrue();
        assertThat(securityVersion("browser-merchant")).isEqualTo(before + 1);
        assertThat(accounts.enableChatSupport("browser-merchant")).isFalse();
        assertThat(securityVersion("browser-merchant")).isEqualTo(before + 1);
        mvc.perform(get("/api/v1/security/me").session(session)).andExpect(status().isUnauthorized());
        var refreshed = login("browser-merchant", PASSWORD);
        mvc.perform(get("/api/v1/security/me").session(refreshed)).andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.serveChats").value(true));
        assertThatThrownBy(() -> new DatabaseAccountAdministration(runtime).enableChatSupport("browser-merchant"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test void chatEnableRequiresExistingMerchantAccountAndCurrentCompleteShopBinding() {
        long before = securityVersion("browser-merchant");
        assertThat(accounts.enableChatSupport("missing-account")).isFalse();
        assertThat(accounts.enableChatSupport("browser-buyer")).isFalse();
        operator.update("UPDATE support_security_account SET enabled = FALSE WHERE username = 'browser-merchant'");
        assertThat(accounts.enableChatSupport("browser-merchant")).isFalse();
        operator.update("UPDATE support_security_account SET enabled = TRUE WHERE username = 'browser-merchant'");
        operator.update("UPDATE support_merchant_staff SET linked_buyer_id = 701 WHERE id = 1");
        assertThat(accounts.enableChatSupport("browser-merchant")).isFalse();
        operator.update("UPDATE support_merchant_staff SET linked_buyer_id = 700, status = 'DISABLED' WHERE id = 1");
        assertThat(accounts.enableChatSupport("browser-merchant")).isFalse();
        operator.update("UPDATE support_merchant_staff SET status = 'ACTIVE' WHERE id = 1");
        operator.update("UPDATE support_merchant SET status = 'DISABLED' WHERE id = 1");
        assertThat(accounts.enableChatSupport("browser-merchant")).isFalse();
        operator.update("UPDATE support_merchant SET status = 'ACTIVE' WHERE id = 1");
        operator.update("UPDATE support_merchant_shop SET status = 'DISABLED' WHERE shop_id = 700");
        assertThat(accounts.enableChatSupport("browser-merchant")).isFalse();
        operator.update("UPDATE support_merchant_shop SET status = 'ACTIVE', merchant_id = 2 WHERE shop_id = 700");
        assertThat(accounts.enableChatSupport("browser-merchant")).isFalse();
        operator.update("UPDATE support_merchant_shop SET merchant_id = 1 WHERE shop_id = 700");
        operator.update("UPDATE support_merchant_staff_shop_grant SET enabled = FALSE WHERE staff_id = 1 AND shop_id = 700");
        assertThat(accounts.enableChatSupport("browser-merchant")).isFalse();
        assertThat(securityVersion("browser-merchant")).isEqualTo(before);
    }

    @Test void bootstrapCopiesExplicitChatPermissionWithoutExpandingExistingAccounts() throws Exception {
        assertThat(accounts.importMissingConfiguredAccounts(List.of(account("browser-merchant", AccountType.MERCHANT_STAFF,
                700, Set.of(SecurityPermission.CHAT_SUPPORT))))).isZero();
        var existing = login("browser-merchant", PASSWORD);
        mvc.perform(get("/api/v1/security/me").session(existing)).andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.manageKnowledge").value(true))
                .andExpect(jsonPath("$.capabilities.serveChats").value(false));
        assertThat(accounts.importMissingConfiguredAccounts(List.of(account("browser-configured-chat", AccountType.MERCHANT_STAFF,
                701, Set.of(SecurityPermission.CHAT_SUPPORT))))).isEqualTo(1);
        var configured = login("browser-configured-chat", PASSWORD);
        mvc.perform(get("/api/v1/security/me").session(configured)).andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.manageKnowledge").value(false))
                .andExpect(jsonPath("$.capabilities.serveChats").value(true));
    }

    private long securityVersion(String username) {
        return operator.queryForObject("SELECT security_version FROM support_security_account WHERE username = ?", Long.class, username);
    }

    private MockHttpSession login(String username, String password) throws Exception {
        var csrf = csrf(null);
        String oldId = csrf.session().getId();
        var result = mvc.perform(post("/api/v1/security/login").session(csrf.session()).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isNoContent()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(csrf.session().isInvalid()).isTrue();
        assertThat(session.getId()).isNotEqualTo(oldId);
        return session;
    }

    private Csrf csrf(MockHttpSession session) throws Exception {
        var request = get("/api/v1/security/csrf");
        if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        return new Csrf((MockHttpSession) result.getRequest().getSession(false), body.path("headerName").asString(),
                body.path("token").asString());
    }

    private static LocalCustomerAccount account(String name, AccountType type, long identity, Set<SecurityPermission> permissions) {
        return new LocalCustomerAccount(name, PASSWORD_HASH, new CustomerIdentity(identity), permissions, type);
    }

    private record Csrf(MockHttpSession session, String header, String token) { }
}
