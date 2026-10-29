package dev.supportflow.chat.infrastructure.deepseek;

import dev.supportflow.assistant.planning.ToolPlanner;
import dev.supportflow.chat.application.model.OrderIntent;
import dev.supportflow.chat.application.port.OrderIntentRecognizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DeepSeekConfiguration.class, dev.supportflow.common.traffic.TrafficConfiguration.class)
            .withBean(io.micrometer.core.instrument.MeterRegistry.class,
                    io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

    @Test
    @DisplayName("默认关闭模型，不配置密钥也能启动并保留普通聊天")
    void shouldDisableModelByDefaultWithoutApiKey() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(OrderIntentRecognizer.class);
            assertThat(context).doesNotHaveBean("deepSeekHttpClient");
            assertThat(context).doesNotHaveBean(DeepSeekCallExecutor.class);
            assertThat(context.getBean(OrderIntentRecognizer.class).recognize("你好"))
                    .isEqualTo(OrderIntent.other());
            DeepSeekProperties properties = context.getBean(DeepSeekProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.apiKey()).isEmpty();
            assertThat(properties.model()).isEqualTo("deepseek-flash");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.maxConcurrentRequests()).isEqualTo(4);
        });
    }

    @Test
    @DisplayName("公开YAML的空密钥占位符可绑定，默认关闭模型仍能启动")
    void shouldBindPublicConfigurationWithoutImportingPrivateFiles() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "public-application-template", new ClassPathResource("application.yml")
        );
        contextRunner.withInitializer(context -> sources.forEach(source ->
                        context.getEnvironment().getPropertySources().addLast(source)))
                .withPropertyValues("DEEPSEEK_API_KEY=")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(OrderIntentRecognizer.class);
                    DeepSeekProperties properties = context.getBean(DeepSeekProperties.class);
                    assertThat(properties.enabled()).isFalse();
                    assertThat(properties.apiKey()).isEmpty();
                    assertThat(properties.maxConcurrentRequests()).isEqualTo(4);
                    assertThat(context).doesNotHaveBean(DeepSeekCallExecutor.class);
                    assertThat(context.getBean(OrderIntentRecognizer.class).recognize("你好"))
                            .isEqualTo(OrderIntent.other());
                });
    }

    @Test
    @DisplayName("显式关闭模型时不会创建网络适配器")
    void shouldKeepDisabledRecognizerWhenExplicitlyDisabled() {
        contextRunner.withPropertyValues("supportflow.ai.deepseek.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(OrderIntentRecognizer.class);
                    assertThat(context).doesNotHaveBean(DeepSeekCallExecutor.class)
                            .doesNotHaveBean("deepSeekHttpClient");
                    assertThat(context.getBean(OrderIntentRecognizer.class))
                            .isNotInstanceOf(DeepSeekOrderIntentRecognizer.class);
                    assertThat(context.getBean(OrderIntentRecognizer.class).recognize("查一下我的订单"))
                            .isEqualTo(OrderIntent.other());
                });
    }

    @Test
    @DisplayName("开启模型但未配置密钥时启动失败")
    void shouldRequireApiKeyWhenEnabled() {
        contextRunner.withPropertyValues("supportflow.ai.deepseek.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("DeepSeek api-key is required when enabled");
                });
    }

    @Test
    @DisplayName("配置合法密钥时创建真实适配器，创建Bean不会调用远程接口")
    void shouldCreateNetworkAdapterOnlyWhenEnabledWithKey() {
        enabledContext().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(OrderIntentRecognizer.class)
                    .hasSingleBean(ToolPlanner.class).hasSingleBean(DeepSeekCallExecutor.class);
            OrderIntentRecognizer recognizer = context.getBean(OrderIntentRecognizer.class);
            ToolPlanner planner = context.getBean(ToolPlanner.class);
            DeepSeekCallExecutor executor = context.getBean(DeepSeekCallExecutor.class);
            assertThat(ReflectionTestUtils.getField(executor, "quotas"))
                    .isSameAs(context.getBean(dev.supportflow.common.traffic.TrafficQuotaService.class));
            assertThat(recognizer)
                    .isInstanceOf(DeepSeekOrderIntentRecognizer.class);
            assertThat(planner).isInstanceOf(DeepSeekToolPlanner.class);
            assertThat(ReflectionTestUtils.getField(recognizer, "executor")).isSameAs(executor);
            assertThat(ReflectionTestUtils.getField(planner, "executor")).isSameAs(executor);
        });
    }

    @Test
    void enabledQuotaConfigurationIsBoundAndCannotBeSilentlyBypassedByTheExecutor() {
        enabledContext().withPropertyValues("supportflow.traffic.enabled=true", "supportflow.traffic.model-limit=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var quotas = context.getBean(dev.supportflow.common.traffic.TrafficQuotaService.class);
                    assertThat(context.getBean(dev.supportflow.common.traffic.TrafficProperties.class).enabled()).isTrue();
                    assertThat(ReflectionTestUtils.getField(context.getBean(DeepSeekCallExecutor.class), "quotas")).isSameAs(quotas);
                });
        enabledContext().withPropertyValues("supportflow.traffic.enabled=true", "supportflow.traffic.model-limit=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("应用上下文关闭时会关闭托管的HTTP客户端")
    void shouldCloseManagedHttpClientWhenContextCloses() {
        AtomicReference<HttpClient> client = new AtomicReference<>();
        enabledContext().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(HttpClient.class);
            HttpClient httpClient = context.getBean("deepSeekHttpClient", HttpClient.class);
            assertThat(httpClient.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
            assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(3));
            assertThat(httpClient.isTerminated()).isFalse();
            client.set(httpClient);
        });
        assertThat(client.get()).isNotNull();
        assertThat(client.get().isTerminated()).isTrue();
    }

    @Test
    @DisplayName("密钥中的空白及换行控制字符会被拒绝且异常不含密钥")
    void shouldRejectUnsafeApiKeysWithoutIncludingThemInFailure() {
        for (String unsafeKey : new String[]{"fake key", "fake\r\nInjected: value", "fake\u0000key"}) {
            contextRunner.withPropertyValues(
                    "supportflow.ai.deepseek.enabled=true",
                    "supportflow.ai.deepseek.api-key=" + unsafeKey
            ).run(context -> {
                assertThat(context).hasFailed();
                Throwable failure = context.getStartupFailure();
                while (failure.getCause() != null) {
                    failure = failure.getCause();
                }
                assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("DeepSeek api-key contains invalid characters");
                assertThat(failure.getMessage()).doesNotContain(unsafeKey);
            });
        }
    }

    @Test
    @DisplayName("模型名称和配置字段拼写错误会阻止启动")
    void shouldRejectUnsafeModelNamesAndUnknownConfiguration() {
        for (String model : new String[]{"", "deepseek/model", "deepseek flash", "x".repeat(129)}) {
            enabledContext().withPropertyValues("supportflow.ai.deepseek.model=" + model)
                    .run(context -> assertThat(context).hasFailed());
        }
        enabledContext().withPropertyValues("supportflow.ai.deepseek.request-timeot=15s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("过短或过长的连接及请求超时会阻止启动")
    void shouldRejectOutOfRangeTimeouts() {
        for (String property : new String[]{
                "connect-timeout=99ms", "connect-timeout=10001ms",
                "request-timeout=99ms", "request-timeout=60001ms", "request-timeout=-1ms"
        }) {
            enabledContext().withPropertyValues("supportflow.ai.deepseek." + property)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    @DisplayName("超时边界内的合法值可以绑定")
    void shouldAcceptTimeoutBoundaries() {
        enabledContext().withPropertyValues(
                "supportflow.ai.deepseek.connect-timeout=100ms",
                "supportflow.ai.deepseek.request-timeout=60s"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            DeepSeekProperties properties = context.getBean(DeepSeekProperties.class);
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(100));
            assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(60));
        });
        enabledContext().withPropertyValues(
                "supportflow.ai.deepseek.connect-timeout=10s",
                "supportflow.ai.deepseek.request-timeout=100ms"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void shouldBindConcurrentRequestCapacityAtBothSupportedBoundaries() {
        for (int capacity : new int[]{1, 64}) {
            enabledContext().withPropertyValues("supportflow.ai.deepseek.max-concurrent-requests=" + capacity)
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(DeepSeekCallExecutor.class);
                        assertThat(context.getBean(DeepSeekProperties.class).maxConcurrentRequests()).isEqualTo(capacity);
                    });
        }
    }

    @Test
    void shouldRejectConcurrentRequestCapacityOutsideItsSupportedRange() {
        for (int capacity : new int[]{-1, 0, 65, Integer.MAX_VALUE}) {
            enabledContext().withPropertyValues("supportflow.ai.deepseek.max-concurrent-requests=" + capacity)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                                .hasRootCauseMessage("DeepSeek max-concurrent-requests must be between 1 and 64");
                    });
        }
    }

    @Test
    void shouldRejectNonIntegralOrUnrepresentableConcurrentRequestCapacity() {
        for (String capacity : new String[]{"not-a-number", "1.5", "999999999999999999999"}) {
            enabledContext().withPropertyValues("supportflow.ai.deepseek.max-concurrent-requests=" + capacity)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void shouldKeepTheLegacyPropertiesConstructorAtDefaultCapacity() {
        var properties = new DeepSeekProperties(false, "", "deepseek-flash",
                Duration.ofSeconds(3), Duration.ofSeconds(15));
        assertThat(properties.maxConcurrentRequests()).isEqualTo(4);
    }

    @Test
    @DisplayName("配置对象的字符串输出始终隐藏API密钥")
    void shouldRedactApiKeyFromToString() {
        enabledContext().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DeepSeekProperties.class).toString())
                    .contains("apiKey=[REDACTED]", "enabled=true", "model=deepseek-flash", "maxConcurrentRequests=4")
                    .doesNotContain("fake-test-key");
        });
    }

    private ApplicationContextRunner enabledContext() {
        return contextRunner.withPropertyValues(
                "supportflow.ai.deepseek.enabled=true",
                "supportflow.ai.deepseek.api-key=fake-test-key"
        );
    }
}
