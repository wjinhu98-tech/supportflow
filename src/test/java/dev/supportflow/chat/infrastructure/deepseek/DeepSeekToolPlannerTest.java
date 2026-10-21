package dev.supportflow.chat.infrastructure.deepseek;

import dev.supportflow.assistant.planning.PlannedToolCall;
import dev.supportflow.assistant.planning.ToolName;
import dev.supportflow.chat.application.ModelUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepSeekToolPlannerTest {
    private static final String API_KEY = "test-only-not-a-real-api-key";
    private static final String MESSAGE = "帮我看看SF10001物流到了吗";
    private static final String ARGUMENTS = "{\"orderNo\":\"SF10001\"}";
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private HttpClient client;
    private DeepSeekToolPlanner planner;

    @BeforeEach
    void setUp() {
        client = mock(HttpClient.class);
        planner = new DeepSeekToolPlanner(client, mapper, properties(Duration.ofSeconds(1)));
    }

    @Test
    void shouldSendOneAllowlistedOfficialToolWithoutIdentityOrBusinessFacts() {
        respond(200, envelope(ToolName.LOGISTICS_LOOKUP, ARGUMENTS));

        assertThat(planner.plan(ToolName.LOGISTICS_LOOKUP, MESSAGE, null))
                .isEqualTo(new PlannedToolCall(ToolName.LOGISTICS_LOOKUP, "SF10001"));

        HttpRequest request = capturedRequest();
        assertThat(request.uri()).isEqualTo(URI.create("https://api.deepseek.com/chat/completions"));
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.timeout()).contains(Duration.ofSeconds(1));
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer " + API_KEY);
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
        String json = readRequestBody(request);
        JsonNode payload = mapper.readTree(json);
        assertThat(payload.get("tools").size()).isEqualTo(1);
        JsonNode function = payload.get("tools").get(0).get("function");
        assertThat(function.get("name").asString()).isEqualTo("get_logistics");
        assertThat(function.get("parameters").get("additionalProperties").asBoolean()).isFalse();
        assertThat(function.get("parameters").get("properties").size()).isEqualTo(1);
        assertThat(function.get("parameters").get("properties").has("orderNo")).isTrue();
        assertThat(function.get("parameters").get("required").get(0).asString()).isEqualTo("orderNo");
        assertThat(payload.get("tool_choice").get("function").get("name").asString()).isEqualTo("get_logistics");
        assertThat(payload.get("thinking").get("type").asString()).isEqualTo("disabled");
        assertThat(payload.get("max_tokens").asInt()).isEqualTo(512);
        assertThat(payload.get("stream").asBoolean()).isFalse();
        assertThat(payload.get("temperature").asInt()).isZero();
        assertThat(payload.has("response_format")).isFalse();
        JsonNode input = mapper.readTree(payload.get("messages").get(1).get("content").asString());
        assertThat(input.get("message").asString()).isEqualTo(MESSAGE);
        assertThat(input.get("selectedOrderNo").isNull()).isTrue();
        assertThat(json).doesNotContain(API_KEY, "shopId", "buyerId", "tenantId", "customerId", "199.90");
    }

    @Test
    void shouldAcceptMissingOrderForServerClarification() {
        respond(200, envelope(ToolName.ORDER_LOOKUP, "{\"orderNo\":null}"));
        assertThat(planner.plan(ToolName.ORDER_LOOKUP, "查订单", null))
                .isEqualTo(new PlannedToolCall(ToolName.ORDER_LOOKUP, null));
    }

    @Test
    void shouldAcceptOnlyExactSelectedOrderEvenWhenMessageHasNoNumber() {
        respond(200, envelope(ToolName.LOGISTICS_LOOKUP, ARGUMENTS));
        assertThat(planner.plan(ToolName.LOGISTICS_LOOKUP, "到哪里了", "SF10001").orderNo()).isEqualTo("SF10001");
        assertUnavailable(ToolName.LOGISTICS_LOOKUP, "到哪里了", "sf10001");
    }

    @Test
    void shouldPlanPendingAfterSaleRequestWithoutApprovalParameters() {
        respond(200, envelope(ToolName.AFTER_SALE_REQUEST, ARGUMENTS));
        assertThat(planner.plan(ToolName.AFTER_SALE_REQUEST, "SF10001申请售后", null).tool())
                .isEqualTo(ToolName.AFTER_SALE_REQUEST);
        assertThat(readRequestBody(capturedRequest())).contains("request_after_sale", "never execute a refund")
                .doesNotContain("get_order", "get_logistics");
    }

    @Test
    void shouldClarifyMultipleExplicitOrdersEvenWhenModelSelectsOneForReadOrWrite() {
        for (ToolName tool : ToolName.values()) {
            for (String returned : List.of("SF10001", "SF10002")) {
                respond(200, envelope(tool, "{\"orderNo\":\"" + returned + "\"}"));
                assertThat(planner.plan(tool, "申请售后 SF10001 SF10002 商品破损", null))
                        .as("%s returned %s", tool, returned)
                        .isEqualTo(new PlannedToolCall(tool, null));
            }
        }
    }

    @Test
    void shouldNotLetSelectedOrderOverrideMultipleExplicitOrders() {
        respond(200, envelope(ToolName.AFTER_SALE_REQUEST, ARGUMENTS));
        assertThat(planner.plan(ToolName.AFTER_SALE_REQUEST,
                "申请售后 SF10001 SF10002 商品破损", "SF10001"))
                .isEqualTo(new PlannedToolCall(ToolName.AFTER_SALE_REQUEST, null));
    }

    @Test
    void shouldNotTreatRepeatedIdenticalOrderNumbersAsAmbiguous() {
        respond(200, envelope(ToolName.AFTER_SALE_REQUEST, ARGUMENTS));
        assertThat(planner.plan(ToolName.AFTER_SALE_REQUEST,
                "申请售后 SF10001 商品破损，确认订单SF10001", null))
                .isEqualTo(new PlannedToolCall(ToolName.AFTER_SALE_REQUEST, "SF10001"));
    }

    @Test
    void shouldStillRejectInventedOrderAndWrongToolWhenUserInputIsAmbiguous() {
        String message = "申请售后 SF10001 SF10002 商品破损";
        respond(200, envelope(ToolName.AFTER_SALE_REQUEST, "{\"orderNo\":\"SF99999\"}"));
        assertUnavailable(ToolName.AFTER_SALE_REQUEST, message, null);
        respond(200, envelope(ToolName.LOGISTICS_LOOKUP, ARGUMENTS));
        assertUnavailable(ToolName.AFTER_SALE_REQUEST, message, null);
    }

    @Test
    void shouldRejectDifferentOrUnknownToolRegardlessOfUserInstructions() {
        for (String name : List.of("get_order", "request_after_sale", "execute_refund", "get_logistics ")) {
            respond(200, envelopeForName(name, ARGUMENTS));
            assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE, null);
        }
    }

    @Test
    void shouldRejectAdditionalIdentitySqlOrApprovalArguments() {
        for (String field : List.of("shopId", "buyerId", "customerId", "tenantId", "approved", "sql", "amount")) {
            respond(200, envelope(ToolName.LOGISTICS_LOOKUP,
                    "{\"orderNo\":\"SF10001\",\"" + field + "\":true}"));
            assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE, null);
        }
    }

    @Test
    void shouldRejectInvalidArgumentShapesTypesAndValues() {
        for (String arguments : List.of("not json", "null", "[]", "{}", "{\"orderNo\":1}",
                "{\"orderNo\":true}", "{\"orderNo\":[]}", "{\"orderNo\":{}}", "{\"orderNo\":\"\"}",
                "{\"orderNo\":\"SF 10001\"}", "{\"orderNo\":\"SF\\n10001\"}",
                "{\"orderNo\":\"" + "S".repeat(65) + "\"}")) {
            respond(200, envelope(ToolName.LOGISTICS_LOOKUP, arguments));
            assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE + " SF 10001 SF\n10001 " + "S".repeat(65), null);
        }
    }

    @Test
    void shouldRejectDuplicateKeysAndTrailingTokensAtBothJsonLevels() {
        for (String arguments : List.of(ARGUMENTS + " {}", "{\"orderNo\":null,\"orderNo\":\"SF10001\"}")) {
            respond(200, envelope(ToolName.LOGISTICS_LOOKUP, arguments));
            assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE, null);
        }
        respond(200, (new String(envelope(ToolName.LOGISTICS_LOOKUP, ARGUMENTS), StandardCharsets.UTF_8) + " {}")
                .getBytes(StandardCharsets.UTF_8));
        assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE, null);
        respond(200, "{\"choices\":[],\"choices\":[]}".getBytes(StandardCharsets.UTF_8));
        assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE, null);
    }

    @Test
    void shouldRejectInventedNormalizedOrPartialOrderNumbers() {
        respond(200, envelope(ToolName.LOGISTICS_LOOKUP, ARGUMENTS));
        for (String message : List.of("SF99999发货了吗", "sf10001", "SF100010", "XSF10001", "SF10001-2", "SF10001.2", "SF10001_2")) {
            assertUnavailable(ToolName.LOGISTICS_LOOKUP, message, null);
        }
    }

    @Test
    void shouldAcceptOriginalNumberAlongsideChineseOrPunctuation() {
        respond(200, envelope(ToolName.LOGISTICS_LOOKUP, ARGUMENTS));
        for (String message : List.of("SF10001", "订单SF10001到了吗", "查一下【SF10001】")) {
            assertThat(planner.plan(ToolName.LOGISTICS_LOOKUP, message, null).orderNo()).isEqualTo("SF10001");
        }
    }

    @Test
    void shouldRejectMalformedProviderEnvelopes() {
        for (String body : List.of("not json", "null", "[]", "{}", "{\"choices\":[]}", "{\"choices\":{}}",
                "{\"choices\":[null]}", "{\"choices\":[{},{}]}")) {
            respond(200, body.getBytes(StandardCharsets.UTF_8));
            assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE, null);
        }
    }

    @Test
    void shouldRejectWrongFinishReasonMissingAssistantOrMultipleCalls() {
        String call = mapper.writeValueAsString(toolCall("get_logistics", ARGUMENTS));
        for (String choice : List.of(
                "{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"tool_calls\":[" + call + "]}}",
                "{\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"tool_calls\":[" + call + "]}}",
                "{\"finish_reason\":\"tool_calls\"}",
                "{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"user\",\"tool_calls\":[" + call + "]}}",
                "{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"tool_calls\":[]}}",
                "{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"tool_calls\":[" + call + "," + call + "]}}")) {
            respond(200, ("{\"choices\":[" + choice + "]}").getBytes(StandardCharsets.UTF_8));
            assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE, null);
        }
    }

    @Test
    void shouldRejectNonFunctionCallOrMissingCallIdentifier() {
        for (Map<String, Object> call : List.<Map<String, Object>>of(
                Map.of("id", "call_1", "type", "custom", "function", Map.of("name", "get_logistics", "arguments", ARGUMENTS)),
                Map.of("id", "", "type", "function", "function", Map.of("name", "get_logistics", "arguments", ARGUMENTS)),
                Map.of("id", "call_1", "type", "function", "function", Map.of("name", "get_logistics", "arguments", Map.of("orderNo", "SF10001"))))) {
            respond(200, envelopeForCall(call));
            assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE, null);
        }
    }

    @Test
    void shouldFailClosedForNonSuccessfulHttpResponse() {
        for (int status : List.of(301, 302, 401, 403, 429, 500, 502, 503)) {
            respond(status, envelope(ToolName.LOGISTICS_LOOKUP, ARGUMENTS));
            assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE, null);
        }
    }

    @Test
    void shouldRejectEmptyTruncatedOrOversizedProviderBody() {
        for (byte[] body : List.of(new byte[0], new byte[65537], "{\"choices\":".getBytes(StandardCharsets.UTF_8))) {
            respond(200, body);
            assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE, null);
        }
    }

    @Test
    void shouldHideNetworkErrorAndCredentialWithoutNestedCause() {
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("provider leaked " + API_KEY)));
        assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE, null);
    }

    @Test
    void shouldHideSynchronousHttpClientFailure() {
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenThrow(new IllegalArgumentException(API_KEY));
        assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE, null);
    }

    @Test
    void shouldTimeoutEntireStalledBodyAndCancelRequest() {
        planner = new DeepSeekToolPlanner(client, mapper, properties(Duration.ofMillis(100)));
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenAnswer(invocation -> {
                    HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                    HttpResponse.BodySubscriber<byte[]> subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
                    subscriber.onSubscribe(mock(Flow.Subscription.class));
                    subscriber.onNext(List.of(ByteBuffer.wrap("{".getBytes(StandardCharsets.UTF_8))));
                    return pending;
                });
        long started = System.nanoTime();
        assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE, null);
        assertThat(pending).isCancelled();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void shouldPreserveInterruptionAndCancelPendingHttpRequest() {
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(pending);
        try {
            Thread.currentThread().interrupt();
            assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE, null);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(pending).isCancelled();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldUseStreamingResponseBoundBeforeMaterializingBody() {
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenAnswer(invocation -> {
                    HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                    HttpResponse.BodySubscriber<byte[]> subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
                    Flow.Subscription subscription = mock(Flow.Subscription.class);
                    subscriber.onSubscribe(subscription);
                    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[65537])));
                    verify(subscription).cancel();
                    assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
                            .isInstanceOf(CompletionException.class).hasCauseInstanceOf(IOException.class);
                    return CompletableFuture.failedFuture(new IOException("too large"));
                });
        assertUnavailable(ToolName.LOGISTICS_LOOKUP, MESSAGE, null);
    }

    private HttpRequest capturedRequest() {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).sendAsync(captor.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        return captor.getValue();
    }

    private String readRequestBody(HttpRequest request) {
        HttpResponse.BodySubscriber<byte[]> collector = HttpResponse.BodySubscribers.ofByteArray();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { collector.onSubscribe(subscription); }
            public void onNext(ByteBuffer buffer) { collector.onNext(List.of(buffer)); }
            public void onError(Throwable throwable) { collector.onError(throwable); }
            public void onComplete() { collector.onComplete(); }
        });
        return new String(collector.getBody().toCompletableFuture().join(), StandardCharsets.UTF_8);
    }

    private void assertUnavailable(ToolName tool, String message, String selected) {
        assertThatThrownBy(() -> planner.plan(tool, message, selected)).isExactlyInstanceOf(ModelUnavailableException.class)
                .hasNoCause().hasMessageNotContaining(API_KEY).hasMessageNotContaining(MESSAGE);
    }

    private byte[] envelope(ToolName tool, String arguments) { return envelopeForName(tool.code(), arguments); }

    private byte[] envelopeForName(String name, String arguments) { return envelopeForCall(toolCall(name, arguments)); }

    private Map<String, Object> toolCall(String name, String arguments) {
        return Map.of("id", "call_1", "type", "function", "function", Map.of("name", name, "arguments", arguments));
    }

    private byte[] envelopeForCall(Map<String, Object> call) {
        return mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", "tool_calls",
                "message", Map.of("role", "assistant", "tool_calls", List.of(call))))));
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private DeepSeekProperties properties(Duration timeout) {
        return new DeepSeekProperties(true, API_KEY, "deepseek-flash", Duration.ofSeconds(1), timeout);
    }
}
