package dev.supportflow.chat.infrastructure.deepseek;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.supportflow.assistant.planning.PlannedToolCall;
import dev.supportflow.assistant.planning.ToolName;
import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.chat.application.model.OrderIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentMatchers;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Isolated admission, cleanup and safe-diagnostic tests: no model, developer configuration or database. */
@Execution(ExecutionMode.SAME_THREAD)
class DeepSeekCallExecutorTest {
    private static final String API_KEY = "test-only-private-key-marker";
    private static final String USER_MESSAGE = "private-user-message-marker";
    private static final String ORDER_NO = "SFPRIVATE001";
    private static final String PROVIDER_TEXT = "private-provider-response-marker";
    private static final String REQUEST_BODY = "{\"message\":\"" + USER_MESSAGE
            + "\",\"orderNo\":\"" + ORDER_NO + "\"}";
    private static final byte[] OK = "ok".getBytes(StandardCharsets.UTF_8);
    private static final Set<String> OUTCOMES = Set.of("SUCCESS", "BUSY", "TIMEOUT", "INTERRUPTED",
            "PROVIDER_RATE_LIMITED", "PROVIDER_ERROR", "INVALID_RESPONSE", "TRANSPORT_ERROR", "CANCELLED");
    private static final Pattern FIELD = Pattern.compile("([A-Za-z][A-Za-z0-9]*)=([^\\s,]+)");

    private HttpClient client;
    private Logger logger;
    private Level previousLevel;
    private SafeListAppender appender;

    @BeforeEach
    void setUp() {
        client = mock(HttpClient.class);
        logger = (Logger) LoggerFactory.getLogger(DeepSeekCallExecutor.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new SafeListAppender();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void checkAndDetachSafeDiagnostics() {
        try {
            for (ILoggingEvent event : appender.events()) {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getThrowableProxy() == null).isTrue();
                assertNoSensitiveText(event.getFormattedMessage());
                if (event.getArgumentArray() != null) {
                    for (Object argument : event.getArgumentArray()) assertNoSensitiveText(String.valueOf(argument));
                }
                Map<String, String> fields = fields(event);
                assertThat(fields.keySet()).containsExactlyInAnyOrder(
                        "operation", "outcome", "durationMs", "inFlight", "limit", "httpStatus", "traceId", "agentRunId");
                assertThat(fields.get("traceId")).matches("none|[0-9a-f]{32}");
                assertThat(fields.get("agentRunId")).matches("none|[0-9a-f-]{36}");
                assertThat(fields.get("operation")).isIn("ORDER_INTENT", "TOOL_PLAN");
                assertThat(OUTCOMES.contains(fields.get("outcome"))).isTrue();
                assertThat(fields.get("durationMs")).matches("\\d+");
                assertThat(Long.parseLong(fields.get("durationMs"))).isGreaterThanOrEqualTo(0);
                int limit = Integer.parseInt(fields.get("limit"));
                assertThat(limit).isPositive();
                assertThat(Integer.parseInt(fields.get("inFlight"))).isBetween(0, limit);
                int status = Integer.parseInt(fields.get("httpStatus"));
                assertThat(status == 0 || status >= 100 && status <= 599).isTrue();
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void shouldShareOneImmediateLimitAcrossConcurrentOperationsWithoutQueueOrExtraHttpCalls() throws Exception {
        int limit = 3;
        DeepSeekCallExecutor executor = executor(Duration.ofSeconds(30), limit);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch admitted = new CountDownLatch(limit);
        CountDownLatch rejected = new CountDownLatch(8 - limit);
        List<CompletableFuture<HttpResponse<byte[]>>> pending = new CopyOnWriteArrayList<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenAnswer(invocation -> {
                    CompletableFuture<HttpResponse<byte[]>> response = new CompletableFuture<>();
                    pending.add(response);
                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                    response.whenComplete((ignored, failure) -> active.decrementAndGet());
                    admitted.countDown();
                    return response;
                });
        try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<Boolean>> results = new ArrayList<>();
            try {
                for (int index = 0; index < 8; index++) {
                    DeepSeekCallOperation operation = index % 2 == 0
                            ? DeepSeekCallOperation.ORDER_INTENT : DeepSeekCallOperation.TOOL_PLAN;
                    results.add(CompletableFuture.supplyAsync(() -> {
                        ready.countDown();
                        await(start);
                        try {
                            executor.execute(operation, REQUEST_BODY, body -> "parsed");
                            return true;
                        } catch (ModelUnavailableException unavailable) {
                            assertSafeException(unavailable);
                            rejected.countDown();
                            return false;
                        }
                    }, workers));
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();
                // All rejected calls must return while every admitted provider response is still blocked.
                assertThat(rejected.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(pending).hasSize(limit);
                assertThat(maximum.get()).isEqualTo(limit);
                verify(client, times(limit)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
                for (CompletableFuture<HttpResponse<byte[]>> response : pending) response.complete(response(200, OK));
                int successful = 0;
                for (CompletableFuture<Boolean> result : results) if (result.get(5, TimeUnit.SECONDS)) successful++;
                assertThat(successful).isEqualTo(limit);
                assertThat(outcomes()).containsExactlyInAnyOrder(
                        "SUCCESS", "SUCCESS", "SUCCESS", "BUSY", "BUSY", "BUSY", "BUSY", "BUSY");
            } finally {
                start.countDown();
                for (CompletableFuture<HttpResponse<byte[]>> response : pending) response.complete(response(200, OK));
            }
        }
    }

    @Test
    void shouldShareTheSamePermitBetweenIntentAndToolAdaptersAndReleaseAfterSuccess() throws Exception {
        DeepSeekProperties properties = properties(Duration.ofSeconds(30), 1);
        DeepSeekCallExecutor executor = new DeepSeekCallExecutor(client, properties);
        JsonMapper mapper = JsonMapper.builder().build();
        var recognizer = new DeepSeekOrderIntentRecognizer(executor, mapper, properties);
        var planner = new DeepSeekToolPlanner(executor, mapper, properties);
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        CountDownLatch sent = new CountDownLatch(1);
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenAnswer(invocation -> { sent.countDown(); return pending; });
        try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
            CompletableFuture<OrderIntent> intent = CompletableFuture.supplyAsync(
                    () -> recognizer.recognize(USER_MESSAGE), workers);
            try {
                assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
                unavailable(() -> planner.plan(ToolName.LOGISTICS_LOOKUP, USER_MESSAGE + " " + ORDER_NO, null));
                unavailable(() -> planner.plan(ToolName.LOGISTICS_LOOKUP, USER_MESSAGE + " " + ORDER_NO, null));
                verify(client, times(1)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
                pending.complete(response(200, mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of(
                        "finish_reason", "stop", "message", Map.of("content", "{\"intent\":\"OTHER\",\"orderNo\":null}")))))));
                assertThat(intent.get(5, TimeUnit.SECONDS)).isEqualTo(OrderIntent.other());
                respond(200, mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of(
                        "finish_reason", "tool_calls", "message", Map.of("role", "assistant", "tool_calls", List.of(
                                Map.of("id", "call_1", "type", "function", "function", Map.of(
                                        "name", "get_logistics", "arguments", "{\"orderNo\":\"" + ORDER_NO + "\"}")))))))));
                assertThat(planner.plan(ToolName.LOGISTICS_LOOKUP, USER_MESSAGE + " " + ORDER_NO, null))
                        .isEqualTo(new PlannedToolCall(ToolName.LOGISTICS_LOOKUP, ORDER_NO));
                assertThat(outcomes()).containsExactly("BUSY", "BUSY", "SUCCESS", "SUCCESS");
                assertThat(fields(appender.events().get(0)).get("operation")).isEqualTo("TOOL_PLAN");
                assertThat(fields(appender.events().get(2)).get("operation")).isEqualTo("ORDER_INTENT");
            } finally {
                pending.complete(response(200, OK));
            }
        }
    }

    @Test
    void shouldKeepThePermitUntilParsingHasFinished() throws Exception {
        DeepSeekCallExecutor executor = executor(Duration.ofSeconds(30), 1);
        respond(200, OK);
        CountDownLatch parsing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> executor.execute(
                    DeepSeekCallOperation.ORDER_INTENT, REQUEST_BODY, body -> {
                        parsing.countDown();
                        await(release);
                        return "parsed";
                    }), workers);
            try {
                assertThat(parsing.await(5, TimeUnit.SECONDS)).isTrue();
                unavailable(() -> executor.execute(DeepSeekCallOperation.TOOL_PLAN, REQUEST_BODY, body -> "unexpected"));
                verify(client, times(1)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("parsed");
                assertRecovery(executor);
                assertThat(outcomes()).containsExactly("BUSY", "SUCCESS", "SUCCESS");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void shouldClassify429WithoutRetryAndReleaseForTheNextCall() {
        DeepSeekCallExecutor executor = executor(Duration.ofSeconds(1), 1);
        respond(429, maliciousText().getBytes(StandardCharsets.UTF_8));
        unavailable(() -> executor.execute(DeepSeekCallOperation.TOOL_PLAN, REQUEST_BODY, body -> "unexpected"));
        verify(client, times(1)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        assertThat(outcomes()).containsExactly("PROVIDER_RATE_LIMITED");
        assertThat(fields(appender.events().getFirst()).get("httpStatus")).isEqualTo("429");
        assertRecovery(executor);
        assertThat(outcomes()).containsExactly("PROVIDER_RATE_LIMITED", "SUCCESS");
    }

    @Test
    void shouldClassifyOtherProviderErrorsWithoutLeakingTheirBodiesOrHoldingPermits() {
        DeepSeekCallExecutor executor = executor(Duration.ofSeconds(1), 1);
        for (int status : new int[]{301, 400, 401, 403, 500, 502, 503}) {
            respond(status, maliciousText().getBytes(StandardCharsets.UTF_8));
            unavailable(() -> executor.execute(DeepSeekCallOperation.ORDER_INTENT, REQUEST_BODY, body -> "unexpected"));
            assertThat(outcomes().getLast()).isEqualTo("PROVIDER_ERROR");
            assertRecovery(executor);
        }
        verify(client, times(14)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        assertThat(appender.events()).hasSize(14);
    }

    @Test
    void shouldCancelStalledResponseOnTotalTimeoutAndReleaseItsPermit() {
        DeepSeekCallExecutor executor = executor(Duration.ofMillis(100), 1);
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(pending);
        unavailable(() -> executor.execute(DeepSeekCallOperation.ORDER_INTENT, REQUEST_BODY, body -> "unexpected"));
        assertThat(pending).isCancelled();
        assertThat(outcomes()).containsExactly("TIMEOUT");
        assertRecovery(executor);
    }

    @Test
    void shouldPreserveAnInterruptedCallerCancelPendingRequestAndReleaseItsPermit() {
        DeepSeekCallExecutor executor = executor(Duration.ofSeconds(1), 1);
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(pending);
        try {
            Thread.currentThread().interrupt();
            unavailable(() -> executor.execute(DeepSeekCallOperation.TOOL_PLAN, REQUEST_BODY, body -> "unexpected"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(pending).isCancelled();
            verify(client, times(1)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
            assertThat(outcomes()).containsExactly("INTERRUPTED");
        } finally {
            Thread.interrupted();
        }
        assertRecovery(executor);
    }

    @Test
    void shouldClassifyCancelledProviderFutureAndReleaseItsPermit() {
        DeepSeekCallExecutor executor = executor(Duration.ofSeconds(1), 1);
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        pending.cancel(false);
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(pending);
        unavailable(() -> executor.execute(DeepSeekCallOperation.TOOL_PLAN, REQUEST_BODY, body -> "unexpected"));
        assertThat(outcomes()).containsExactly("CANCELLED");
        assertRecovery(executor);
    }

    @Test
    void shouldHideAsynchronousTransportFailureAndReleaseItsPermit() {
        DeepSeekCallExecutor executor = executor(Duration.ofSeconds(1), 1);
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException(maliciousText())));
        unavailable(() -> executor.execute(DeepSeekCallOperation.ORDER_INTENT, REQUEST_BODY, body -> "unexpected"));
        assertThat(outcomes()).containsExactly("TRANSPORT_ERROR");
        assertRecovery(executor);
    }

    @Test
    void shouldClassifyHttpTimeoutExceptionSeparatelyFromTransportError() {
        DeepSeekCallExecutor executor = executor(Duration.ofSeconds(1), 1);
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.failedFuture(new HttpTimeoutException(maliciousText())));
        unavailable(() -> executor.execute(DeepSeekCallOperation.ORDER_INTENT, REQUEST_BODY, body -> "unexpected"));
        assertThat(outcomes()).containsExactly("TIMEOUT");
        assertRecovery(executor);
    }

    @Test
    void shouldHideSynchronousClientFailureAndReleaseItsPermit() {
        DeepSeekCallExecutor executor = executor(Duration.ofSeconds(1), 1);
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenThrow(new IllegalArgumentException(maliciousText()));
        unavailable(() -> executor.execute(DeepSeekCallOperation.TOOL_PLAN, REQUEST_BODY, body -> "unexpected"));
        assertThat(outcomes()).containsExactly("TRANSPORT_ERROR");
        respond(200, OK);
        String recovered = executor.execute(DeepSeekCallOperation.ORDER_INTENT, REQUEST_BODY, body -> "recovered");
        assertThat(recovered).isEqualTo("recovered");
    }

    @Test
    void shouldHideParserFailureAndProviderBytesAndReleaseItsPermit() {
        DeepSeekCallExecutor executor = executor(Duration.ofSeconds(1), 1);
        respond(200, maliciousText().getBytes(StandardCharsets.UTF_8));
        unavailable(() -> executor.execute(DeepSeekCallOperation.TOOL_PLAN, REQUEST_BODY, body -> {
            throw new IllegalArgumentException(new String(body, StandardCharsets.UTF_8));
        }));
        assertThat(outcomes()).containsExactly("INVALID_RESPONSE");
        assertRecovery(executor);
    }

    @Test
    void shouldRejectMissingEmptyOrOversizedBodiesBeforeParsingAndReleaseEachPermit() {
        DeepSeekCallExecutor executor = executor(Duration.ofSeconds(1), 1);
        AtomicBoolean parsed = new AtomicBoolean();
        for (byte[] body : new byte[][]{null, new byte[0], new byte[65537]}) {
            respond(200, body);
            unavailable(() -> executor.execute(DeepSeekCallOperation.ORDER_INTENT, REQUEST_BODY, received -> {
                parsed.set(true);
                return "unexpected";
            }));
            assertThat(parsed.get()).isFalse();
            assertThat(outcomes().getLast()).isEqualTo("INVALID_RESPONSE");
            assertRecovery(executor);
        }
    }

    @Test
    void shouldAcceptExactly64KiBAndPassBytesToTheParser() {
        DeepSeekCallExecutor executor = executor(Duration.ofSeconds(1), 1);
        respond(200, new byte[65536]);
        int receivedBytes = executor.execute(DeepSeekCallOperation.ORDER_INTENT, REQUEST_BODY, body -> body.length);
        assertThat(receivedBytes).isEqualTo(65536);
        assertThat(outcomes()).containsExactly("SUCCESS");
        assertRecovery(executor);
    }

    @Test
    void shouldCancelStreamingSubscriptionAtTheByteBoundAndReportInvalidResponse() {
        DeepSeekCallExecutor executor = executor(Duration.ofSeconds(1), 1);
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenAnswer(invocation -> {
                    HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                    HttpResponse.BodySubscriber<byte[]> subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
                    subscriber.onSubscribe(subscription);
                    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[32768])));
                    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[32769])));
                    return subscriber.getBody().toCompletableFuture().thenApply(bytes -> response(200, bytes));
                });
        unavailable(() -> executor.execute(DeepSeekCallOperation.TOOL_PLAN, REQUEST_BODY, body -> "unexpected"));
        verify(subscription).cancel();
        assertThat(outcomes()).containsExactly("INVALID_RESPONSE");
        assertRecovery(executor);
    }

    private DeepSeekCallExecutor executor(Duration timeout, int limit) {
        return new DeepSeekCallExecutor(client, properties(timeout, limit));
    }

    private DeepSeekProperties properties(Duration timeout, int limit) {
        return new DeepSeekProperties(true, API_KEY, "deepseek-flash", Duration.ofSeconds(1), timeout, limit);
    }

    private void assertRecovery(DeepSeekCallExecutor executor) {
        respond(200, OK);
        String recovered = executor.execute(DeepSeekCallOperation.ORDER_INTENT, REQUEST_BODY, body -> "recovered");
        assertThat(recovered).isEqualTo("recovered");
        assertThat(outcomes().getLast()).isEqualTo("SUCCESS");
    }

    private void respond(int status, byte[] body) {
        doReturn(CompletableFuture.completedFuture(response(status, body))).when(client)
                .sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private void unavailable(Executable operation) {
        try {
            operation.execute();
        } catch (ModelUnavailableException failure) {
            assertSafeException(failure);
            return;
        } catch (Throwable unexpected) {
            // Do not attach or render an unexpected provider/parser exception containing test secrets.
            throw new AssertionError("Only the safe model-unavailable exception may cross the provider boundary");
        }
        fail("Expected the safe model-unavailable exception");
    }

    private void assertSafeException(ModelUnavailableException failure) {
        assertThat(failure.getCause() == null).isTrue();
        assertThat(failure.getSuppressed().length).isZero();
        StringWriter stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        assertNoSensitiveText(stack.toString());
    }

    private static void assertNoSensitiveText(String text) {
        // Boolean-only failures never echo the inspected body, message, key or unsafe exception text.
        for (String marker : List.of(API_KEY, USER_MESSAGE, ORDER_NO, PROVIDER_TEXT, REQUEST_BODY)) {
            assertThat(text == null || !text.contains(marker)).as("Sensitive data must not be exposed").isTrue();
        }
    }

    private List<String> outcomes() {
        return appender.events().stream().map(event -> fields(event).get("outcome")).toList();
    }

    private Map<String, String> fields(ILoggingEvent event) {
        Map<String, String> values = new HashMap<>();
        var matcher = FIELD.matcher(event.getFormattedMessage());
        while (matcher.find()) values.put(matcher.group(1), matcher.group(2));
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs().forEach(pair -> {
                assertNoSensitiveText(String.valueOf(pair.value));
                values.put(pair.key, String.valueOf(pair.value));
            });
        }
        return values;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Controlled test gate did not open");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Controlled test worker was interrupted");
        }
    }

    private static String maliciousText() {
        return API_KEY + " " + USER_MESSAGE + " " + ORDER_NO + " " + PROVIDER_TEXT;
    }

    private static final class SafeListAppender extends ListAppender<ILoggingEvent> {
        @Override
        protected synchronized void append(ILoggingEvent event) {
            super.append(event);
        }

        synchronized List<ILoggingEvent> events() { return List.copyOf(list); }
    }
}
