package dev.supportflow.common.traffic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Runs after authentication, CSRF and path authorization, before controllers or business writes. */
public final class AuthenticatedRequestLimitFilter extends OncePerRequestFilter {
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final TrafficQuotaService quotas;

    public AuthenticatedRequestLimitFilter(TrafficQuotaService quotas) { this.quotas = quotas; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !WRITE_METHODS.contains(request.getMethod()) || !path.startsWith("/api/v1/")
                || path.startsWith("/api/v1/security/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            quotas.requireRequest();
        } catch (TrafficLimitExceededException limited) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(limited.retryAfterSeconds()));
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"请求较频繁，请稍后再试。\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
