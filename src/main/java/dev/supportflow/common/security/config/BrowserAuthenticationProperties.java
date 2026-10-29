package dev.supportflow.common.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "supportflow.security.authentication")
public record BrowserAuthenticationProperties(
        @DefaultValue("configured") String mode,
        @DefaultValue("true") boolean basicEnabled,
        @DefaultValue("30m") Duration sessionTimeout
) {
    public BrowserAuthenticationProperties {
        if (!"configured".equals(mode) && !"database".equals(mode)) {
            throw new IllegalArgumentException("authentication mode must be configured or database");
        }
        if (sessionTimeout == null || sessionTimeout.compareTo(Duration.ofMinutes(1)) < 0
                || sessionTimeout.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("session timeout must be between 1 minute and 24 hours");
        }
    }
}
