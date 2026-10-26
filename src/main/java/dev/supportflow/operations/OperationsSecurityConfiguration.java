package dev.supportflow.operations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

/** Management authentication is deliberately not registered in the business AuthenticationManager. */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "lab"})
@EnableConfigurationProperties(OperationsProperties.class)
public class OperationsSecurityConfiguration {
    @Bean
    @Order(0)
    @ConditionalOnProperty(prefix = "supportflow.operations", name = "enabled", havingValue = "true")
    SecurityFilterChain operationsSecurityFilterChain(HttpSecurity http, OperationsProperties properties) throws Exception {
        var users = new InMemoryUserDetailsManager(User.withUsername(properties.username())
                .password(properties.passwordHash()).roles("OPERATIONS").build());
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authenticationManager(new ProviderManager(provider))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/**")
                        .hasRole("OPERATIONS")
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.realmName("supportflow-operations"))
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
