package dev.supportflow.common.security.config;

import dev.supportflow.common.security.api.JsonAccessDeniedHandler;
import dev.supportflow.common.security.api.JsonAuthenticationEntryPoint;
import dev.supportflow.common.security.model.SecurityPermission;
import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.merchant.MerchantCapabilities;
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
 * <p>认证上下文不保存在 session，CSRF token 仍可使用 session 保存。</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "lab"})
@EnableMethodSecurity
public class ApiSecurityConfiguration {

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            AuthenticationManager authenticationManager,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        http.authenticationManager(authenticationManager)
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(
                                HttpMethod.GET, "/api/v1/chat/welcome"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/orders",
                                "/api/v1/shops/*/orders/*",
                                "/api/v1/shops/*/orders/*/after-sale-eligibility",
                                "/api/v1/shops/*/knowledge/search"
                        ).access(accountAccess(AccountType.BUYER, SecurityPermission.ORDER_READ))
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
                                        && (MerchantCapabilities.from(principal).reviewAfterSales()
                                        || MerchantCapabilities.from(principal).manageKnowledge())))
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
                                HttpMethod.GET, "/api/v1/security/csrf", "/api/v1/security/me"
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
                .httpBasic(basic -> basic
                        .authenticationEntryPoint(authenticationEntryPoint)
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
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                );

        // 保留 Spring Security 默认 CSRF 保护。
        return http.build();
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
