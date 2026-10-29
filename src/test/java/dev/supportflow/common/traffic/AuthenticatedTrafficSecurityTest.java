package dev.supportflow.common.traffic;

import dev.supportflow.common.observability.RequestTraceFilter;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real security-filter ordering and MVC, with a counted fake write instead of any daily database. */
class AuthenticatedTrafficSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void close() { context.close(); }

    @Test void secondAuthorizedWriteGets429BeforeControllerAndOtherBuyersRemainIndependent() throws Exception {
        var first = principal(200);
        mvc.perform(post("/api/v1/orders").with(user(first)).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/orders?buyerId=201&shopId=999").with(user(first)).with(csrf()))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
                .andExpect(header().exists("X-Trace-Id"));
        assertThat(context.getBean(WriteController.class).writes.get()).isEqualTo(1);
        mvc.perform(post("/api/v1/orders").with(user(principal(201))).with(csrf())).andExpect(status().isOk());
        assertThat(context.getBean(WriteController.class).writes.get()).isEqualTo(2);
    }

    @Test void csrfDeniedAnonymousAndPollingRequestsDoNotConsumeTheBuyersAllowance() throws Exception {
        var first = principal(200);
        mvc.perform(post("/api/v1/orders").with(user(first))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders").with(csrf())).andExpect(status().isUnauthorized());
        for (int i = 0; i < 3; i++) mvc.perform(get("/api/v1/orders").with(user(first))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/orders").with(user(first)).with(csrf())).andExpect(status().isOk());
        assertThat(context.getBean(WriteController.class).writes.get()).isEqualTo(1);
    }

    @Test void modelQuotaAdviceWinsOverTheGeneralCatchAllAndReturnsRetryAfter() throws Exception {
        mvc.perform(post("/api/v1/model").with(user(principal(200))).with(csrf()))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "12"));
        assertThat(context.getBean(WriteController.class).writes.get()).isZero();
    }

    static CustomerPrincipal principal(long buyerId) {
        return new CustomerPrincipal("buyer-" + buyerId, "unused", new CustomerIdentity(buyerId), List.of());
    }

    @Configuration(proxyBeanMethods = false) @EnableWebMvc @EnableWebSecurity
    static class TestConfiguration {
        @Bean WriteController writeController() { return new WriteController(); }
        @Bean TrafficExceptionHandler trafficErrors() { return new TrafficExceptionHandler(); }
        @Bean dev.supportflow.common.api.advice.GlobalExceptionHandler generalErrors() {
            return new dev.supportflow.common.api.advice.GlobalExceptionHandler();
        }
        @Bean SimpleMeterRegistry registry() { return new SimpleMeterRegistry(); }
        @Bean TrafficQuotaService quota(SimpleMeterRegistry registry) {
            return new TrafficQuotaService(new TrafficProperties(true, 1, Duration.ofSeconds(60),
                    2, Duration.ofHours(1), 16), registry, () -> 0);
        }
        @Bean SecurityFilterChain security(HttpSecurity http, TrafficQuotaService quota, SimpleMeterRegistry registry) throws Exception {
            return http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                    .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, failure) -> response.setStatus(401)))
                    .addFilterBefore(new RequestTraceFilter(registry), SecurityContextHolderFilter.class)
                    .addFilterAfter(new AuthenticatedRequestLimitFilter(quota), AuthorizationFilter.class).build();
        }
    }

    @RestController static class WriteController {
        final AtomicInteger writes = new AtomicInteger();
        @PostMapping("/api/v1/orders") int write() { return writes.incrementAndGet(); }
        @PostMapping("/api/v1/model") int model() { throw new TrafficLimitExceededException(12); }
        @GetMapping("/api/v1/orders") int poll() { return writes.get(); }
    }
}
