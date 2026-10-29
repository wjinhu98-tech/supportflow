package dev.supportflow.common.traffic;

import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrafficQuotaServiceTest {
    private final AtomicLong clock = new AtomicLong();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TrafficQuotaService quotas = new TrafficQuotaService(
            new TrafficProperties(true, 1, Duration.ofMinutes(1), 2, Duration.ofHours(1), 8), registry, clock::get);

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); registry.close(); }

    @Test void oneBuyersRequestsDoNotSpendAnothersAndAliasesCannotBypass() {
        authenticate("first", 200, AccountType.BUYER);
        quotas.requireRequest();
        authenticate("another-login-same-buyer", 200, AccountType.BUYER);
        assertThatThrownBy(quotas::requireRequest).isInstanceOf(TrafficLimitExceededException.class);
        authenticate("second", 201, AccountType.BUYER);
        assertThatCode(quotas::requireRequest).doesNotThrowAnyException();
        authenticate("merchant", 200, AccountType.MERCHANT_STAFF);
        assertThatCode(quotas::requireRequest).doesNotThrowAnyException();
        assertThat(registry.getMeters()).allSatisfy(meter -> {
            assertThat(meter.getId().getTags()).allSatisfy(tag ->
                    assertThat(tag.getKey()).isIn("scope", "outcome"));
        });
    }

    @Test void repeatedModelCallsShareOneBucketButNotRequestAllowance() {
        authenticate("first", 200, AccountType.BUYER);
        quotas.requireModelCall();
        quotas.requireModelCall();
        assertThatThrownBy(quotas::requireModelCall).isInstanceOfSatisfying(TrafficLimitExceededException.class,
                failure -> assertThat(failure.retryAfterSeconds()).isEqualTo(3600));
        assertThatCode(quotas::requireRequest).doesNotThrowAnyException();
        authenticate("second", 201, AccountType.BUYER);
        assertThatCode(quotas::requireModelCall).doesNotThrowAnyException();
        clock.set(Duration.ofHours(1).toNanos());
        authenticate("first", 200, AccountType.BUYER);
        assertThatCode(quotas::requireModelCall).doesNotThrowAnyException();
    }

    @Test void enabledModelQuotaFailsClosedWithoutATrustedPrincipal() {
        assertThatThrownBy(quotas::requireModelCall).isInstanceOf(AccessDeniedException.class);
        authenticate("first", 200, AccountType.BUYER);
        assertThatCode(quotas::requireRequest).doesNotThrowAnyException();
    }

    @Test void legacyIsolatedCallersRemainExplicitlyDisabledByDefault() {
        var disabled = new TrafficQuotaService(TrafficProperties.disabled(), registry, clock::get);
        assertThatCode(disabled::requireModelCall).doesNotThrowAnyException();
        assertThatCode(disabled::requireRequest).doesNotThrowAnyException();
    }

    @Test void invalidConfigurationCannotSilentlyDisableLimits() {
        assertThatThrownBy(() -> new TrafficProperties(true, 0, Duration.ofSeconds(1), 1, Duration.ofHours(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrafficProperties(true, 1, Duration.ofMillis(999), 1, Duration.ofHours(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrafficProperties(true, 1, Duration.ofSeconds(1), 1, Duration.ofDays(2), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrafficProperties(true, 1, Duration.ofSeconds(1), 1, Duration.ofHours(1), 100001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void authenticate(String username, long buyerId, AccountType type) {
        var principal = new CustomerPrincipal(username, "unused-hash", new CustomerIdentity(buyerId), List.of(), type);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }
}
