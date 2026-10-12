package dev.supportflow.common.security.config;

import dev.supportflow.common.security.local.LocalCustomerAccountsProperties;
import dev.supportflow.common.security.local.LocalCustomerUserDetailsService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 仅在 local 环境启用的开发账号认证组件。
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@EnableConfigurationProperties(LocalCustomerAccountsProperties.class)
public class LocalAuthenticationConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public LocalCustomerUserDetailsService localCustomerUserDetailsService(
            LocalCustomerAccountsProperties properties
    ) {
        return new LocalCustomerUserDetailsService(properties.accounts());
    }

    @Bean
    public DaoAuthenticationProvider customerAuthenticationProvider(
            LocalCustomerUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            DaoAuthenticationProvider customerAuthenticationProvider
    ) {
        return new ProviderManager(customerAuthenticationProvider);
    }
}
