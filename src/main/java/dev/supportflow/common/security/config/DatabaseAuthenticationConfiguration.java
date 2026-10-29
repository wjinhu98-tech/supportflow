package dev.supportflow.common.security.config;

import dev.supportflow.common.security.database.DatabaseCustomerUserDetailsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@Profile({"local", "lab"})
@ConditionalOnProperty(prefix = "supportflow.security.authentication", name = "mode", havingValue = "database")
public class DatabaseAuthenticationConfiguration {
    @Bean PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean DatabaseCustomerUserDetailsService databaseCustomerUserDetailsService(JdbcTemplate jdbc) {
        return new DatabaseCustomerUserDetailsService(jdbc);
    }

    @Bean DaoAuthenticationProvider customerAuthenticationProvider(DatabaseCustomerUserDetailsService users,
                                                                 PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean AuthenticationManager authenticationManager(DaoAuthenticationProvider customerAuthenticationProvider) {
        return new ProviderManager(customerAuthenticationProvider);
    }
}
