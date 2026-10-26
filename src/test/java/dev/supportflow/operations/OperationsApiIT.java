package dev.supportflow.operations;

import dev.supportflow.SupportFlowApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = SupportFlowApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"spring.config.location=classpath:/application-day4-test.yml", "spring.main.banner-mode=off",
                "management.endpoints.access.default=none", "management.endpoints.access.max-permitted=read-only",
                "management.endpoints.web.exposure.include=health,prometheus,metrics",
                "management.endpoint.health.access=read-only", "management.endpoint.prometheus.access=read-only",
                "management.endpoint.metrics.access=read-only", "management.endpoint.health.show-details=never",
                "management.endpoint.health.show-components=never", "supportflow.operations.enabled=true",
                "supportflow.operations.username=ops-test"})
@ActiveProfiles("local")
@Testcontainers
class OperationsApiIT {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);

    @Container
    private static final org.testcontainers.mysql.MySQLContainer MYSQL =
            new org.testcontainers.mysql.MySQLContainer("mysql:8.0.45")
                    .withDatabaseName("supportflow_operations_test").withUsername("operations_test")
                    .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("supportflow.operations.password-hash", () -> HASH);
        registry.add("supportflow.security.local.accounts[0].username", () -> "buyer-test");
        registry.add("supportflow.security.local.accounts[0].password-hash", () -> HASH);
        registry.add("supportflow.security.local.accounts[0].identity.buyer-id", () -> "200");
        registry.add("supportflow.security.local.accounts[0].permissions", () -> "ORDER_READ");
    }

    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousHealthShowsNoDatabaseOrConfigurationDetails() throws Exception {
        String body = mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("jdbc", "password", "components", "details", "operations_test");
    }

    @Test
    void prometheusAndMetricInspectionRequireTheIndependentOperationsIdentity() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(httpBasic("buyer-test", PASSWORD)))
                .andExpect(status().isUnauthorized());
        String body = mvc.perform(get("/actuator/prometheus").with(httpBasic("ops-test", PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("jvm_memory_used_bytes").doesNotContain(HASH, PASSWORD);
        mvc.perform(get("/actuator/metrics").with(httpBasic("ops-test", PASSWORD))).andExpect(status().isOk());
    }

    @Test
    void operationsCredentialsDoNotAuthorizeBusinessApiAndBuyerCredentialsStillWork() throws Exception {
        mvc.perform(get("/api/v1/security/me").with(httpBasic("ops-test", PASSWORD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/security/me").with(httpBasic("buyer-test", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountType").value("BUYER"));
    }

    @Test
    void sensitiveManagementEndpointsAndWriteOperationsRemainUnavailable() throws Exception {
        for (String endpoint : new String[]{"env", "configprops", "heapdump", "logfile", "shutdown", "beans", "loggers"}) {
            int code = mvc.perform(get("/actuator/" + endpoint).with(httpBasic("ops-test", PASSWORD)))
                    .andReturn().getResponse().getStatus();
            assertThat(code).isIn(401, 403, 404);
        }
        mvc.perform(post("/actuator/metrics").with(httpBasic("ops-test", PASSWORD))).andExpect(status().isForbidden());
    }
}
