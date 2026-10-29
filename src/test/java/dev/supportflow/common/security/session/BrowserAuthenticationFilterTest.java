package dev.supportflow.common.security.session;

import dev.supportflow.common.security.api.JsonAuthenticationEntryPoint;
import dev.supportflow.common.security.api.SecurityErrorResponseWriter;
import dev.supportflow.common.security.config.BrowserAuthenticationProperties;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class BrowserAuthenticationFilterTest {
    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final SecurityErrorResponseWriter errors = new SecurityErrorResponseWriter(JsonMapper.builder().build());
    private final BrowserAuthenticationFilter filter = new BrowserAuthenticationFilter(authenticationManager, contexts,
            new BrowserAuthenticationProperties("configured", false, Duration.ofMinutes(10)),
            new JsonAuthenticationEntryPoint(errors), errors, JsonMapper.builder().build());

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test void loginInvalidatesOldSessionAndSavesCredentialFreeFreshContext() throws Exception {
        var principal = new CustomerPrincipal("buyer", "{bcrypt}not-used", new CustomerIdentity(200), List.of());
        principal.eraseCredentials();
        when(authenticationManager.authenticate(any())).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        var request = login("{\"username\":\"buyer\",\"password\":\"password\"}");
        var oldSession = new MockHttpSession();
        String oldId = oldSession.getId();
        request.setSession(oldSession);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        assertThat(oldSession.isInvalid()).isTrue();
        assertThat(request.getSession().getId()).isNotEqualTo(oldId);
        assertThat(request.getSession().getMaxInactiveInterval()).isEqualTo(600);
        assertThat(request.getSession().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNotNull();
        assertThat(principal.getPassword()).isNull();
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test void invalidReloginDoesNotRetainPreviousIdentity() throws Exception {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("internal detail"));
        var request = login("{\"username\":\"buyer\",\"password\":\"bad-secret\"}");
        var oldSession = new MockHttpSession();
        request.setSession(oldSession);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        assertThat(oldSession.isInvalid()).isTrue();
        assertThat(request.getSession(false)).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).doesNotContain("bad-secret", "internal detail");
    }

    @Test void rejectsOversizedOrUnknownCredentialFieldsWithoutAuthentication() throws Exception {
        for (String body : List.of("{\"username\":\"buyer\",\"password\":\"secret\",\"role\":\"ADMIN\"}",
                "{\"username\":\"buyer\",\"password\":\"secret\",\"password\":\"other\"}",
                "{\"username\":\"buyer\",\"password\":\"secret\"} {}",
                "x".repeat(4097), "{\"username\":\"buyer\",\"password\":\"" + "密".repeat(30) + "\"}")) {
            var response = new MockHttpServletResponse();
            filter.doFilter(login(body), response, mock(FilterChain.class));
            assertThat(response.getStatus()).isEqualTo(400);
        }
        verifyNoInteractions(authenticationManager);
    }

    @Test void logoutInvalidatesContextAndExpiresHttpOnlyCookie() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/security/logout");
        var session = new MockHttpSession();
        request.setSession(session);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        assertThat(session.isInvalid()).isTrue();
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getCookie("JSESSIONID").isHttpOnly()).isTrue();
        assertThat(response.getCookie("JSESSIONID").getMaxAge()).isZero();
        assertThat(response.getCookie("JSESSIONID").getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test void logoutExpiresTheConfiguredLabSessionCookie() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/security/logout");
        request.getServletContext().getSessionCookieConfig().setName("SF_LAB_SESSION");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        assertThat(response.getCookie("SF_LAB_SESSION").getMaxAge()).isZero();
    }

    private MockHttpServletRequest login(String body) {
        var request = new MockHttpServletRequest("POST", "/api/v1/security/login");
        request.setContentType("application/json");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }
}
