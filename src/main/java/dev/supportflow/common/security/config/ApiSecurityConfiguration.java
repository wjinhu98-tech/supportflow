package dev.supportflow.common.security.config;

import dev.supportflow.common.security.api.JsonAccessDeniedHandler;
import dev.supportflow.common.security.api.JsonAuthenticationEntryPoint;
import dev.supportflow.common.security.model.SecurityPermission;
import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.merchant.MerchantCapabilities;
import dev.supportflow.common.security.api.SecurityErrorResponseWriter;
import dev.supportflow.common.security.database.DatabaseCustomerUserDetailsService;
import dev.supportflow.common.security.session.BrowserAuthenticationFilter;
import dev.supportflow.common.security.session.SessionAccountValidationFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import tools.jackson.databind.ObjectMapper;
import dev.supportflow.common.observability.RequestTraceFilter;
import dev.supportflow.common.traffic.AuthenticatedRequestLimitFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.Arrays;

/**
 * 本地 API 的认证与授权规则；未声明的入口默认拒绝。
 *
 * <p>浏览器使用服务器会话；兼容 Basic 仅由显式配置保留。</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "lab"})
@EnableMethodSecurity
@EnableConfigurationProperties(BrowserAuthenticationProperties.class)
public class ApiSecurityConfiguration {

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            AuthenticationManager authenticationManager,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler,
            SecurityErrorResponseWriter errorWriter,
            BrowserAuthenticationProperties authenticationProperties,
            ObjectProvider<DatabaseCustomerUserDetailsService> databaseUsers,
            RequestTraceFilter requestTraceFilter,
            AuthenticatedRequestLimitFilter requestLimitFilter,
            ObjectMapper objectMapper
    ) throws Exception {
        var contexts = new HttpSessionSecurityContextRepository();
        var accountValidation = new SessionAccountValidationFilter(databaseUsers.getIfAvailable(), authenticationEntryPoint);
        var browserAuthentication = new BrowserAuthenticationFilter(authenticationManager, contexts,
                authenticationProperties, authenticationEntryPoint, errorWriter, objectMapper);
        http.authenticationManager(authenticationManager)
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(
                                HttpMethod.GET, "/api/v1/chat/welcome", "/api/v1/security/csrf"
                        ).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/security/login", "/api/v1/security/logout").permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/orders",
                                "/api/v1/shops/*/orders",
                                "/api/v1/shops/*/orders/*",
                                "/api/v1/shops/*/orders/*/after-sale-eligibility",
                                "/api/v1/shops/*/knowledge/search"
                        ).access(accountAccess(AccountType.BUYER, SecurityPermission.ORDER_READ))
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/shops/*/orders/*")
                        .access(accountAccess(AccountType.BUYER, SecurityPermission.ORDER_READ))
                        .requestMatchers(HttpMethod.POST, "/api/v1/shops/*/orders/*/after-sales")
                        .access(accountAccess(AccountType.BUYER, SecurityPermission.ORDER_READ))
                        .requestMatchers(HttpMethod.POST, "/api/v1/shops/*/orders/*/receipt-confirmation")
                        .access(accountAccess(AccountType.BUYER, SecurityPermission.ORDER_READ,
                                SecurityPermission.ORDER_CONFIRM_RECEIPT))
                        .requestMatchers(
                                HttpMethod.GET, "/api/v1/shops/*/after-sales"
                        ).access(accountAccess(AccountType.BUYER, SecurityPermission.ORDER_READ))
                        // Retired global queues must never become an alternate authorization path.
                        .requestMatchers("/api/v1/reviews/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/merchant/shops")
                        .access((authentication, context) -> new AuthorizationDecision(
                                authentication.get().getPrincipal() instanceof CustomerPrincipal principal
                                        && MerchantCapabilities.from(principal).any()))
                        .requestMatchers("/api/v1/merchant/shops/*/support-sessions",
                                "/api/v1/merchant/shops/*/support-sessions/**")
                        .access(accountAccess(AccountType.MERCHANT_STAFF, SecurityPermission.CHAT_SUPPORT))
                        .requestMatchers("/api/v1/merchant/shops/*/knowledge",
                                "/api/v1/merchant/shops/*/knowledge/**")
                        .access(accountAccess(AccountType.MERCHANT_STAFF, SecurityPermission.KNOWLEDGE_MANAGE))
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/merchant/shops/*/after-sales",
                                "/api/v1/merchant/shops/*/after-sales/history")
                        .access(accountAccess(AccountType.MERCHANT_STAFF, SecurityPermission.ORDER_READ,
                                SecurityPermission.AFTER_SALE_REVIEW))
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchant/shops/*/after-sales/*/decision",
                                "/api/v1/merchant/shops/*/after-sales/*/execution/retry")
                        .access(accountAccess(AccountType.MERCHANT_STAFF, SecurityPermission.ORDER_READ,
                                SecurityPermission.AFTER_SALE_REVIEW))
                        .requestMatchers(
                                HttpMethod.GET, "/api/v1/security/me"
                        ).authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/shops")
                        .access(accountAccess(AccountType.BUYER, SecurityPermission.ORDER_READ))
                        .requestMatchers(HttpMethod.GET, "/api/v1/shops/*/chat/window")
                        .access(accountAccess(AccountType.BUYER))
                        .requestMatchers(HttpMethod.POST, "/api/v1/shops/*/chat/window")
                        .access(accountAccess(AccountType.BUYER))
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/shops/*/chat/window/*")
                        .access(accountAccess(AccountType.BUYER))
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/shops/*/chat/conversations/*/runs/*")
                        .access(accountAccess(AccountType.BUYER, SecurityPermission.ORDER_READ))
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/shops/*/chat/conversations/*/runs/*/resume")
                        .access(accountAccess(AccountType.BUYER, SecurityPermission.ORDER_READ))
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/shops/*/chat/conversations/*/support",
                                "/api/v1/shops/*/chat/conversations/*/support/**")
                        .access(accountAccess(AccountType.BUYER))
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/shops/*/chat/conversations/*/support",
                                "/api/v1/shops/*/chat/conversations/*/support/**")
                        .access(accountAccess(AccountType.BUYER))
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/shops/*/chat/conversations",
                                "/api/v1/shops/*/chat/conversations/*/messages")
                        .access(accountAccess(AccountType.BUYER))
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/shops/*/chat/conversations",
                                "/api/v1/shops/*/chat/conversations/*/messages")
                        .access(accountAccess(AccountType.BUYER))
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/shops/*/chat/messages",
                                "/api/v1/chat/messages"
                        ).access(accountAccess(AccountType.BUYER))
                        .anyRequest().denyAll()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache ->
                        cache.requestCache(new NullRequestCache())
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId())
                )
                .securityContext(security -> security.securityContextRepository(contexts).requireExplicitSave(true)
                );

        if (authenticationProperties.basicEnabled()) {
            http.httpBasic(basic -> basic.authenticationEntryPoint(authenticationEntryPoint));
        } else {
            http.httpBasic(AbstractHttpConfigurer::disable);
        }
        http.addFilterAfter(accountValidation, BasicAuthenticationFilter.class);
        http.addFilterAfter(browserAuthentication, SessionAccountValidationFilter.class);
        http.addFilterBefore(requestTraceFilter, SecurityContextHolderFilter.class);
        http.addFilterAfter(requestLimitFilter, AuthorizationFilter.class);

        // 保留 Spring Security 默认 CSRF 保护。
        return http.build();
    }

    @Bean
    ServletContextInitializer browserSessionCookies() {
        return context -> {
            context.getSessionCookieConfig().setHttpOnly(true);
            context.getSessionCookieConfig().setAttribute("SameSite", "Lax");
        };
    }

    private static AuthorizationManager<RequestAuthorizationContext> accountAccess(
            AccountType accountType, SecurityPermission... permissions) {
        return (authenticationSupplier, context) -> {
            var authentication = authenticationSupplier.get();
            boolean allowed = authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof CustomerPrincipal principal
                    && principal.getAccountType() == accountType
                    && Arrays.stream(permissions).allMatch(permission ->
                    principal.getAuthorities().contains(permission));
            return new AuthorizationDecision(allowed);
        };
    }
}
