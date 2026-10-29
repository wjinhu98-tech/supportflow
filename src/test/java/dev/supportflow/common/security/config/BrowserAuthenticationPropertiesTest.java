package dev.supportflow.common.security.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserAuthenticationPropertiesTest {
    @Test void rejectsUnknownAuthenticationMode() {
        assertThatThrownBy(() -> new BrowserAuthenticationProperties("other", true, Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void boundsSessionInactivityExpiry() {
        for (var timeout : new Duration[]{Duration.ZERO, Duration.ofDays(2)}) {
            assertThatThrownBy(() -> new BrowserAuthenticationProperties("database", false, timeout))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
