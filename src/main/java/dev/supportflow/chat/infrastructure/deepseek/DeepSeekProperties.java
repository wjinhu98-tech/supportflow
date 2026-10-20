package dev.supportflow.chat.infrastructure.deepseek;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.regex.Pattern;

/** DeepSeek 的开关和调用边界；密钥仅从环境或本地私密配置绑定。 */
@ConfigurationProperties(prefix = "supportflow.ai.deepseek", ignoreUnknownFields = false)
public record DeepSeekProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String apiKey,
        @DefaultValue("deepseek-flash") String model,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("15s") Duration requestTimeout
) {

    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public DeepSeekProperties {
        apiKey = apiKey == null ? "" : apiKey;
        if (enabled && apiKey.isBlank()) {
            throw new IllegalArgumentException("DeepSeek api-key is required when enabled");
        }
        if (apiKey.length() > 4096 || apiKey.codePoints().anyMatch(codePoint ->
                Character.isWhitespace(codePoint) || Character.isISOControl(codePoint))) {
            throw new IllegalArgumentException("DeepSeek api-key contains invalid characters");
        }
        if (model == null || !MODEL_NAME.matcher(model).matches()) {
            throw new IllegalArgumentException("DeepSeek model must be a safe name of 1 to 128 characters");
        }
        requireTimeout(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(10), "connect-timeout");
        requireTimeout(requestTimeout, Duration.ofMillis(100), Duration.ofSeconds(60), "request-timeout");
    }

    private static void requireTimeout(Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("DeepSeek " + name + " is outside the allowed range");
        }
    }

    @Override
    public String toString() {
        return "DeepSeekProperties[enabled=" + enabled + ", apiKey=[REDACTED], model=" + model
                + ", connectTimeout=" + connectTimeout + ", requestTimeout=" + requestTimeout + "]";
    }
}
