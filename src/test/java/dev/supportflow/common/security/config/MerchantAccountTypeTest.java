package dev.supportflow.common.security.config;

import dev.supportflow.common.security.local.LocalCustomerUserDetailsService;
import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantAccountTypeTest {
    @Test
    void oldPrincipalConstructorRemainsBuyerEvenWithReviewPermission() {
        var principal = new CustomerPrincipal("legacy", "unused", new CustomerIdentity(999),
                List.of(SecurityPermission.AFTER_SALE_REVIEW));
        assertThat(principal.getAccountType()).isEqualTo(AccountType.BUYER);
    }

    @Test
    void absentAccountTypeBindsAsBuyer() {
        context().run(application -> {
            assertThat(application).hasNotFailed();
            var principal = application.getBean(LocalCustomerUserDetailsService.class).loadUserByUsername("staff");
            assertThat(principal.getAccountType()).isEqualTo(AccountType.BUYER);
        });
    }

    @Test
    void explicitAccountTypeSurvivesAuthenticationSnapshotAndCredentialErasure() {
        context().withPropertyValues("supportflow.security.local.accounts[0].account-type=MERCHANT_STAFF")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    var principal = application.getBean(LocalCustomerUserDetailsService.class).loadUserByUsername("staff");
                    principal.eraseCredentials();
                    assertThat(principal.getPassword()).isNull();
                    assertThat(principal.getAccountType()).isEqualTo(AccountType.MERCHANT_STAFF);
                    assertThat(principal.getIdentity().buyerId()).isEqualTo(999);
                });
    }

    @Test
    void unknownAccountTypeFailsConfigurationBinding() {
        context().withPropertyValues("supportflow.security.local.accounts[0].account-type=PLATFORM_ADMIN")
                .run(application -> assertThat(application).hasFailed());
    }

    private static ApplicationContextRunner context() {
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(UUID.randomUUID().toString());
        return new ApplicationContextRunner().withUserConfiguration(LocalAuthenticationConfiguration.class)
                .withInitializer(application -> application.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("supportflow.security.local.accounts[0].username=staff",
                        "supportflow.security.local.accounts[0].password-hash=" + hash,
                        "supportflow.security.local.accounts[0].identity.buyer-id=999",
                        "supportflow.security.local.accounts[0].permissions[0]=ORDER_READ",
                        "supportflow.security.local.accounts[0].permissions[1]=AFTER_SALE_REVIEW");
    }
}
