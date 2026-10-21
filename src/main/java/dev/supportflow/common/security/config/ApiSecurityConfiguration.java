package dev.supportflow.common.security.config;

import dev.supportflow.common.security.api.JsonAccessDeniedHandler;
import dev.supportflow.common.security.api.JsonAuthenticationEntryPoint;
import dev.supportflow.common.security.model.SecurityPermission;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * 本地 API 的认证与授权规则；未声明的入口默认拒绝。
 *
 * <p>认证上下文不保存在 session，CSRF token 仍可使用 session 保存。</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
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
                                "/api/v1/shops/*/orders/*"
                        ).hasAuthority(
                                SecurityPermission.ORDER_READ.getAuthority()
                        )
                        .requestMatchers(
                                HttpMethod.GET, "/api/v1/shops/*/after-sales"
                        ).hasAuthority(SecurityPermission.ORDER_READ.getAuthority())
                        .requestMatchers(HttpMethod.GET, "/api/v1/reviews/after-sales", "/api/v1/reviews/after-sales/history")
                        .hasAuthority(SecurityPermission.AFTER_SALE_REVIEW.getAuthority())
                        .requestMatchers(HttpMethod.POST, "/api/v1/reviews/after-sales/*/decision",
                                "/api/v1/reviews/after-sales/*/execution/retry")
                        .hasAuthority(SecurityPermission.AFTER_SALE_REVIEW.getAuthority())
                        .requestMatchers(
                                HttpMethod.GET, "/api/v1/security/csrf"
                        ).authenticated()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/shops/*/chat/messages",
                                "/api/v1/chat/messages"
                        ).authenticated()
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
}
