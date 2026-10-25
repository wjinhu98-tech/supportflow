package dev.supportflow.chat.infrastructure.deepseek;

import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.chat.application.model.OrderIntent;
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
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeepSeekOrderIntentRecognizerTest {

    private static final String API_KEY = "test-only-not-a-real-api-key";
    private static final String MESSAGE = "帮我看看SF10001订单发货了吗？";
    private static final String LOOKUP = "{\"intent\":\"ORDER_LOOKUP\",\"orderNo\":\"SF10001\"}";
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private HttpClient httpClient;
    private DeepSeekOrderIntentRecognizer recognizer;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        recognizer = new DeepSeekOrderIntentRecognizer(httpClient, objectMapper, properties(Duration.ofSeconds(1)));
    }

    @Test
    void shouldHideRequestSerializationFailureBeforeSendingAnyHttpRequest() {
        String sensitiveMarker = "serialization-private-marker";
        ObjectMapper failingMapper = spy(objectMapper);
        doThrow(new IllegalStateException(sensitiveMarker + " " + API_KEY + " " + MESSAGE))
                .when(failingMapper).writeValueAsString(any());
        recognizer = new DeepSeekOrderIntentRecognizer(httpClient, failingMapper, properties(Duration.ofSeconds(1)));

        assertThatThrownBy(() -> recognizer.recognize(MESSAGE)).isExactlyInstanceOf(ModelUnavailableException.class)
                .hasNoCause().hasMessageNotContaining(sensitiveMarker)
                .hasMessageNotContaining(API_KEY).hasMessageNotContaining(MESSAGE);

        verifyNoInteractions(httpClient);
    }

    @Test
    void shouldSendOfficialRequestAndRecognizeOnlyExplicitQueryParameters() {
        respond(200, envelope(LOOKUP));

        assertThat(recognizer.recognize(MESSAGE)).isEqualTo(OrderIntent.lookup("SF10001"));

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).sendAsync(requestCaptor.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        HttpRequest request = requestCaptor.getValue();
        assertThat(request.uri()).isEqualTo(URI.create("https://api.deepseek.com/chat/completions"));
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.timeout()).contains(Duration.ofSeconds(1));
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer " + API_KEY);
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
        HttpResponse.BodySubscriber<byte[]> requestBody = HttpResponse.BodySubscribers.ofByteArray();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                requestBody.onSubscribe(subscription);
            }

            @Override
            public void onNext(ByteBuffer buffer) {
                requestBody.onNext(List.of(buffer));
            }

            @Override
            public void onError(Throwable throwable) {
                requestBody.onError(throwable);
            }

            @Override
            public void onComplete() {
                requestBody.onComplete();
            }
        });
        String json = new String(requestBody.getBody().toCompletableFuture().join(), StandardCharsets.UTF_8);
        JsonNode payload = objectMapper.readTree(json);
        assertThat(payload.get("model").asString()).isEqualTo("deepseek-flash");
        assertThat(payload.get("thinking").get("type").asString()).isEqualTo("disabled");
        assertThat(payload.get("response_format").get("type").asString()).isEqualTo("json_object");
        assertThat(payload.get("max_tokens").asInt()).isEqualTo(512);
        assertThat(payload.get("stream").asBoolean()).isFalse();
        assertThat(payload.get("temperature").asInt()).isZero();
        assertThat(payload.get("messages").size()).isEqualTo(2);
        assertThat(payload.get("messages").get(0).get("role").asString()).isEqualTo("system");
        assertThat(payload.get("messages").get(1).get("role").asString()).isEqualTo("user");
        assertThat(payload.get("messages").get(1).get("content").asString()).isEqualTo(MESSAGE);
        assertThat(json).doesNotContain(API_KEY, "shopId", "customerId", "buyerId", "199.90");
    }

    @Test
    void shouldRetainMissingParametersForServiceToRequestClarification() {
        respond(200, envelope("{\"intent\":\"ORDER_LOOKUP\",\"orderNo\":null}"));
        assertThat(recognizer.recognize("查一下订单")).isEqualTo(OrderIntent.lookup(null));
    }

    @Test
    void shouldRecognizeOtherWithoutInventingOrderParameters() {
        respond(200, envelope("{\"intent\":\"OTHER\",\"orderNo\":null}"));
        assertThat(recognizer.recognize("你好")).isEqualTo(OrderIntent.other());
    }

    @Test
    void shouldRejectMalformedProviderEnvelope() {
        for (String body : List.of("not json", "null", "[]", "{}", "{\"choices\":[]}",
                "{\"choices\":{}}", "{\"choices\":[null]}", "{\"choices\":[{},{}]}")) {
            respond(200, body.getBytes(StandardCharsets.UTF_8));
            assertUnavailable(MESSAGE);
        }
    }

    @Test
    void shouldRejectIncompleteOrInterruptedModelCompletion() {
        for (String finishReason : List.of("length", "tool_calls", "content_filter", "")) {
            respond(200, objectMapper.writeValueAsBytes(Map.of("choices", List.of(Map.of(
                    "finish_reason", finishReason, "message", Map.of("content", LOOKUP))))));
            assertUnavailable(MESSAGE);
        }
        respond(200, "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}".getBytes(StandardCharsets.UTF_8));
        assertUnavailable(MESSAGE);
    }

    @Test
    void shouldRejectMissingBlankOrNonTextAssistantContent() {
        for (String choice : List.of(
                "{\"finish_reason\":\"stop\"}",
                "{\"finish_reason\":\"stop\",\"message\":{}}",
                "{\"finish_reason\":\"stop\",\"message\":{\"content\":null}}",
                "{\"finish_reason\":\"stop\",\"message\":{\"content\":true}}",
                "{\"finish_reason\":\"stop\",\"message\":{\"content\":[]}}",
                "{\"finish_reason\":\"stop\",\"message\":{\"content\":\"  \"}}")) {
            respond(200, ("{\"choices\":[" + choice + "]}").getBytes(StandardCharsets.UTF_8));
            assertUnavailable(MESSAGE);
        }
    }

    @Test
    void shouldRejectUnknownMissingOrAdditionalIntentFields() {
        for (String content : List.of(
                "{}", "[]", "null", "not json", "```json\n" + LOOKUP + "\n```",
                "{\"intent\":\"ORDER_LIST\",\"orderNo\":null}",
                "{\"intent\":\"ORDER_LOOKUP\"}",
                "{\"orderNo\":\"SF10001\"}",
                "{\"intent\":true,\"orderNo\":\"SF10001\"}",
                "{\"intent\":\"ORDER_LOOKUP\",\"orderNo\":\"SF10001\",\"unexpected\":null}",
                "{\"intent\":\"OTHER\",\"orderNo\":\"SF10001\"}")) {
            respond(200, envelope(content));
            assertUnavailable(MESSAGE);
        }
    }

    @Test
    void shouldRejectDuplicateKeysAndTrailingJsonRatherThanSilentlyChooseOne() {
        for (String content : List.of(LOOKUP + " {}",
                "{\"intent\":\"OTHER\",\"intent\":\"ORDER_LOOKUP\",\"orderNo\":\"SF10001\"}")) {
            respond(200, envelope(content));
            assertUnavailable(MESSAGE);
        }
        respond(200, (new String(envelope(LOOKUP), StandardCharsets.UTF_8) + " {}").getBytes(StandardCharsets.UTF_8));
        assertUnavailable(MESSAGE);
        respond(200, "{\"choices\":[],\"choices\":[]}".getBytes(StandardCharsets.UTF_8));
        assertUnavailable(MESSAGE);
    }

    @Test
    void shouldRejectModelSuppliedShopOrBuyerFields() {
        for (String field : List.of("shopId", "buyerId", "tenantId", "customerId")) {
            respond(200, envelope("{\"intent\":\"ORDER_LOOKUP\",\"orderNo\":\"SF10001\",\"" + field + "\":100}"));
            assertUnavailable(MESSAGE);
        }
        respond(200, envelope("{\"intent\":\"OTHER\",\"orderNo\":null,\"shopId\":null}"));
        assertUnavailable(MESSAGE);
    }

    @Test
    void shouldRejectWrongOrderNumberTypeAndInvalidDomainValues() {
        for (String orderNo : List.of("10001", "true", "[]", "{}", "\"\"", "\"SF 10001\"", "\"SF\\n10001\"")) {
            respond(200, envelope("{\"intent\":\"ORDER_LOOKUP\",\"orderNo\":" + orderNo + "}"));
            assertUnavailable(MESSAGE + " SF 10001 SF\n10001");
        }
        String tooLong = "S".repeat(65);
        respond(200, envelope(objectMapper.writeValueAsString(Map.of("intent", "ORDER_LOOKUP", "orderNo", tooLong))));
        assertUnavailable(MESSAGE + " " + tooLong);
    }

    @Test
    void shouldRecognizeOrderWithoutShopInUserMessage() {
        respond(200, envelope(LOOKUP));
        assertThat(recognizer.recognize("帮我看看SF10001发货了吗"))
                .isEqualTo(OrderIntent.lookup("SF10001"));
    }

    @Test
    void shouldRecognizeOriginalOrderNumberBesideChineseOrPunctuation() {
        respond(200, envelope(LOOKUP));
        for (String message : List.of("订单SF10001发货了吗", "SF10001", "查询：SF10001，谢谢")) {
            assertThat(recognizer.recognize(message)).isEqualTo(OrderIntent.lookup("SF10001"));
        }
    }

    @Test
    void shouldRejectInventedOrCaseChangedOrderNumber() {
        respond(200, envelope(LOOKUP));
        assertUnavailable("商铺100的SF99999发货了吗");
        assertUnavailable("商铺100的sf10001发货了吗");
        assertUnavailable("商铺100的SF100010发货了吗");
        assertUnavailable("商铺100的XSF10001发货了吗");
        assertUnavailable("商铺100的SF10001-2发货了吗");
        assertUnavailable("商铺100的SF10001.2发货了吗");
        assertUnavailable("商铺100的SF10001_2发货了吗");
    }

    @Test
    void shouldFailClosedForRedirectAuthenticationRateLimitAndProviderErrors() {
        for (int status : List.of(301, 302, 401, 403, 429, 500, 502, 503)) {
            respond(status, envelope(LOOKUP));
            assertUnavailable(MESSAGE);
        }
    }

    @Test
    void shouldRejectEmptyTruncatedAndOversizedBody() {
        for (byte[] body : List.of(new byte[0], "{\"choices\":".getBytes(StandardCharsets.UTF_8), new byte[65537])) {
            respond(200, body);
            assertUnavailable(MESSAGE);
        }
    }

    @Test
    void shouldHideProviderFailureAndCredentialsWithoutCause() {
        CompletableFuture<HttpResponse<byte[]>> failed = CompletableFuture.failedFuture(
                new IOException("provider echoed " + API_KEY + " " + MESSAGE));
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(failed);
        assertUnavailable(MESSAGE);
    }

    @Test
    void shouldHideSynchronousClientFailureWithoutCause() {
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenThrow(new IllegalArgumentException("invalid credential " + API_KEY));
        assertUnavailable(MESSAGE);
    }

    @Test
    void shouldTreatProviderHttpTimeoutAsUnavailable() {
        CompletableFuture<HttpResponse<byte[]>> failed = CompletableFuture.failedFuture(new HttpTimeoutException("timeout"));
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(failed);
        assertUnavailable(MESSAGE);
    }

    @Test
    void shouldTimeOutAndCancelWhenHeadersArriveButBodyDoesNotComplete() {
        recognizer = new DeepSeekOrderIntentRecognizer(httpClient, objectMapper, properties(Duration.ofMillis(100)));
        CompletableFuture<HttpResponse<byte[]>> stalled = new CompletableFuture<>();
        AtomicBoolean headersReceived = new AtomicBoolean();
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
            HttpResponse.BodySubscriber<byte[]> subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
            subscriber.onSubscribe(mock(Flow.Subscription.class));
            subscriber.onNext(List.of(ByteBuffer.wrap("{\"choices\":".getBytes(StandardCharsets.UTF_8))));
            headersReceived.set(true);
            return stalled;
        });

        long started = System.nanoTime();
        assertUnavailable(MESSAGE);
        assertThat(headersReceived).isTrue();
        assertThat(stalled).isCancelled();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void shouldRestoreInterruptionAndCancelPendingRequest() {
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(pending);
        try {
            Thread.currentThread().interrupt();
            assertUnavailable(MESSAGE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(pending).isCancelled();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldCollectBodyAtExactBound() {
        DeepSeekResponseBodySubscriber subscriber = new DeepSeekResponseBodySubscriber(8);
        subscriber.onSubscribe(mock(Flow.Subscription.class));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3}), ByteBuffer.wrap(new byte[]{4, 5})));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{6, 7, 8})));
        subscriber.onComplete();

        assertThat(subscriber.getBody().toCompletableFuture().join()).isEqualTo(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
    }

    @Test
    void shouldCancelBeforeCollectingResponsePastAccumulatedBound() {
        DeepSeekResponseBodySubscriber subscriber = new DeepSeekResponseBodySubscriber(8);
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[4])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[5])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        subscriber.onComplete();

        verify(subscription).cancel();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(IOException.class);
    }

    private void assertUnavailable(String message) {
        assertThatThrownBy(() -> recognizer.recognize(message))
                .isExactlyInstanceOf(ModelUnavailableException.class)
                .hasNoCause()
                .hasMessageNotContaining(API_KEY)
                .hasMessageNotContaining(MESSAGE);
    }

    private byte[] envelope(String content) {
        return objectMapper.writeValueAsBytes(Map.of("choices", List.of(Map.of(
                "finish_reason", "stop", "message", Map.of("content", content)))));
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private DeepSeekProperties properties(Duration requestTimeout) {
        return new DeepSeekProperties(true, API_KEY, "deepseek-flash", Duration.ofSeconds(1), requestTimeout);
    }
}
