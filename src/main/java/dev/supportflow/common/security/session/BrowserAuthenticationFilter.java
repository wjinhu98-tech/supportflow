package dev.supportflow.common.security.session;

import dev.supportflow.common.api.error.ErrorCode;
import dev.supportflow.common.security.api.JsonAuthenticationEntryPoint;
import dev.supportflow.common.security.api.SecurityErrorResponseWriter;
import dev.supportflow.common.security.config.BrowserAuthenticationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** JSON login runs after CSRF validation; credentials never become a response or session attribute. */
public final class BrowserAuthenticationFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES = 4096;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;
    private final BrowserAuthenticationProperties properties;
    private final JsonAuthenticationEntryPoint entryPoint;
    private final SecurityErrorResponseWriter errors;
    private final ObjectReader credentialsReader;

    public BrowserAuthenticationFilter(AuthenticationManager authenticationManager, SecurityContextRepository contexts,
                                       BrowserAuthenticationProperties properties, JsonAuthenticationEntryPoint entryPoint,
                                       SecurityErrorResponseWriter errors, ObjectMapper mapper) {
        this.authenticationManager = authenticationManager;
        this.contexts = contexts;
        this.properties = properties;
        this.entryPoint = entryPoint;
        this.errors = errors;
        this.credentialsReader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!"POST".equals(request.getMethod()) || (!path.equals("/api/v1/security/login")
                && !path.equals("/api/v1/security/logout"))) {
            chain.doFilter(request, response);
            return;
        }

        // Includes failed re-login: old identity must not silently remain authenticated.
        new SecurityContextLogoutHandler().logout(request, response,
                SecurityContextHolder.getContext().getAuthentication());
        if (path.endsWith("/logout")) {
            String configuredCookieName = request.getServletContext().getSessionCookieConfig().getName();
            var expiredCookie = new Cookie(configuredCookieName == null ? "JSESSIONID" : configuredCookieName, "");
            expiredCookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
            expiredCookie.setHttpOnly(true);
            expiredCookie.setSecure(request.isSecure());
            expiredCookie.setMaxAge(0);
            expiredCookie.setAttribute("SameSite", "Lax");
            response.addCookie(expiredCookie);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        JsonNode credentials;
        try {
            if (request.getContentType() == null
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()))) {
                rejectBody(response);
                return;
            }
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                rejectBody(response);
                return;
            }
            credentials = credentialsReader.readTree(new String(bytes, StandardCharsets.UTF_8));
            if (credentials == null || !credentials.isObject() || credentials.size() != 2
                    || !credentials.path("username").isString() || !credentials.path("password").isString()
                    || credentials.path("username").asString().isBlank()
                    || credentials.path("username").asString().length() > 100
                    || credentials.path("password").asString().isEmpty()
                    || credentials.path("password").asString().getBytes(StandardCharsets.UTF_8).length > 72) {
                rejectBody(response);
                return;
            }
        } catch (RuntimeException exception) {
            // Malformed bodies and passwords must never be included in error output or logs.
            rejectBody(response);
            return;
        }

        try {
            var result = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                    credentials.path("username").asString(), credentials.path("password").asString()));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(result);
            SecurityContextHolder.setContext(context);
            // The anonymous/previous session was invalidated; this is always a fresh session identifier and CSRF token.
            request.getSession(true).setMaxInactiveInterval(Math.toIntExact(properties.sessionTimeout().toSeconds()));
            contexts.saveContext(context, request, response);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, exception);
        }
    }

    private void rejectBody(HttpServletResponse response) throws IOException {
        errors.write(response, HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT);
    }
}
