package dev.supportflow.common.security.session;

import dev.supportflow.common.security.api.JsonAuthenticationEntryPoint;
import dev.supportflow.common.security.database.DatabaseCustomerUserDetailsService;
import dev.supportflow.common.security.model.CustomerPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Validates database revocation on every authenticated API request, never from client identity parameters. */
public final class SessionAccountValidationFilter extends OncePerRequestFilter {
    private final DatabaseCustomerUserDetailsService databaseUsers;
    private final JsonAuthenticationEntryPoint entryPoint;

    public SessionAccountValidationFilter(DatabaseCustomerUserDetailsService databaseUsers,
                                          JsonAuthenticationEntryPoint entryPoint) {
        this.databaseUsers = databaseUsers;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!request.getRequestURI().equals(request.getContextPath() + "/api/v1/security/login")
                && authentication != null && authentication.getPrincipal() instanceof CustomerPrincipal principal) {
            if (databaseUsers != null && !databaseUsers.isCurrent(principal)) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                entryPoint.commence(request, response, new BadCredentialsException("Authentication revoked"));
                return;
            }
            String expectedAccount = request.getHeader("X-Session-Account");
            if (expectedAccount != null && !expectedAccount.equals(principal.getUsername())) {
                // A stale tab must not invalidate the newer session belonging to another account.
                entryPoint.commence(request, response, new BadCredentialsException("Session account changed"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
