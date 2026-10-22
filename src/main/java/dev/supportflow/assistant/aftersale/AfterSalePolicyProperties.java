package dev.supportflow.assistant.aftersale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "supportflow.after-sale.policy", ignoreUnknownFields = false)
public record AfterSalePolicyProperties(@DefaultValue("15") int windowDays) {
    public AfterSalePolicyProperties {
        if (windowDays < 1 || windowDays > 365) {
            throw new IllegalArgumentException("After-sale windowDays must be between 1 and 365");
        }
    }
}
