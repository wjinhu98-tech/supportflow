package dev.supportflow.common.traffic;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Single-process admission limits. These are not a distributed or monetary budget. */
@ConfigurationProperties(prefix = "supportflow.traffic", ignoreUnknownFields = false)
public record TrafficProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("30") int requestLimit,
        @DefaultValue("60s") Duration requestWindow,
        @DefaultValue("60") int modelLimit,
        @DefaultValue("1h") Duration modelWindow,
        @DefaultValue("10000") int maxIdentities) {
    public TrafficProperties {
        if (requestLimit < 1 || requestLimit > 10000 || modelLimit < 1 || modelLimit > 10000)
            throw new IllegalArgumentException("Traffic limits must be between 1 and 10000");
        validateWindow(requestWindow);
        validateWindow(modelWindow);
        if (maxIdentities < 1 || maxIdentities > 100000)
            throw new IllegalArgumentException("Traffic max-identities must be between 1 and 100000");
    }

    private static void validateWindow(Duration window) {
        if (window == null || window.compareTo(Duration.ofSeconds(1)) < 0
                || window.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Traffic windows must be between one second and one day");
    }

    public static TrafficProperties disabled() {
        return new TrafficProperties(false, 30, Duration.ofMinutes(1), 60, Duration.ofHours(1), 10000);
    }
}
