package dev.supportflow.operations;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Separate operations credentials, never a business identity or a global merchant grant. */
@ConfigurationProperties(prefix = "supportflow.operations", ignoreUnknownFields = false)
public record OperationsProperties(@DefaultValue("false") boolean enabled,
                                   @DefaultValue("lab-ops") String username,
                                   @DefaultValue("") String passwordHash) {
    public OperationsProperties {
        username = username == null ? "" : username;
        passwordHash = passwordHash == null ? "" : passwordHash;
        if (enabled && (!username.matches("[a-z][a-z0-9-]{2,63}")
                || !passwordHash.matches("\\{bcrypt}\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}"))) {
            throw new IllegalArgumentException("Operations authentication requires a valid username and BCrypt hash");
        }
    }

    @Override
    public String toString() {
        return "OperationsProperties[enabled=" + enabled + ", credentials=[REDACTED]]";
    }
}
