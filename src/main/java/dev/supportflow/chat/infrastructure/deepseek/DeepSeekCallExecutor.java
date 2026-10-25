package dev.supportflow.chat.infrastructure.deepseek;

import dev.supportflow.chat.application.ModelUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** One per application instance: bounded calls, no waiting queue, no retry and no raw provider logging. */
public final class DeepSeekCallExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(DeepSeekCallExecutor.class);
    private static final URI ENDPOINT = URI.create("https://api.deepseek.com/chat/completions");
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final HttpClient client;
    private final DeepSeekProperties properties;
    private final Semaphore slots;

    public DeepSeekCallExecutor(HttpClient client, DeepSeekProperties properties) {
        this.client = Objects.requireNonNull(client, "HTTP client is required");
        this.properties = Objects.requireNonNull(properties, "DeepSeek configuration is required");
        slots = new Semaphore(properties.maxConcurrentRequests());
    }

    public <T> T execute(DeepSeekCallOperation operation, String requestBody, Function<byte[], T> parser) {
        Objects.requireNonNull(operation, "Operation is required");
        Objects.requireNonNull(requestBody, "Request body is required");
        Objects.requireNonNull(parser, "Response parser is required");
        long started = System.nanoTime();
        if (!slots.tryAcquire()) {
            record(operation, DeepSeekCallOutcome.BUSY, started, 0);
            throw new ModelUnavailableException();
        }

        CompletableFuture<HttpResponse<byte[]>> pending = null;
        DeepSeekCallOutcome outcome = DeepSeekCallOutcome.TRANSPORT_ERROR;
        int httpStatus = 0;
        try {
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(properties.requestTimeout())
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            pending = client.sendAsync(request, ignored -> new DeepSeekResponseBodySubscriber(MAX_RESPONSE_BYTES));
            // Bound headers AND the entire body, including a peer that stalls after sending headers.
            long remaining = properties.requestTimeout().toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
            outcome = DeepSeekCallOutcome.INVALID_RESPONSE;
            if (response == null) throw new ModelUnavailableException();
            int status = response.statusCode();
            httpStatus = status >= 100 && status <= 599 ? status : 0;
            if (status != 200) {
                outcome = status == 429 ? DeepSeekCallOutcome.PROVIDER_RATE_LIMITED
                        : DeepSeekCallOutcome.PROVIDER_ERROR;
                throw new ModelUnavailableException();
            }
            byte[] body = response.body();
            if (body == null || body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
                throw new ModelUnavailableException();
            }
            T result = parser.apply(body);
            if (result == null) throw new ModelUnavailableException();
            outcome = DeepSeekCallOutcome.SUCCESS;
            return result;
        } catch (InterruptedException interrupted) {
            outcome = DeepSeekCallOutcome.INTERRUPTED;
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException();
        } catch (TimeoutException timeout) {
            outcome = DeepSeekCallOutcome.TIMEOUT;
            throw new ModelUnavailableException();
        } catch (CancellationException cancelled) {
            outcome = DeepSeekCallOutcome.CANCELLED;
            throw new ModelUnavailableException();
        } catch (ExecutionException failure) {
            outcome = classifyFailure(failure.getCause());
            throw new ModelUnavailableException();
        } catch (RuntimeException failure) {
            // Never attach a provider/parser exception: even its message may contain credentials or data.
            throw new ModelUnavailableException();
        } finally {
            try {
                if (outcome != DeepSeekCallOutcome.SUCCESS && pending != null) pending.cancel(true);
            } catch (RuntimeException ignored) {
                // Cancellation is best effort; a broken transport must not leak application capacity.
            } finally {
                slots.release();
                record(operation, outcome, started, httpStatus);
            }
        }
    }

    private static DeepSeekCallOutcome classifyFailure(Throwable failure) {
        // A bounded cause walk classifies types only; no message, payload or stack trace is logged.
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof HttpTimeoutException || failure instanceof TimeoutException) {
                return DeepSeekCallOutcome.TIMEOUT;
            }
            if (failure instanceof DeepSeekResponseBodySubscriber.ResponseLimitExceededException) {
                return DeepSeekCallOutcome.INVALID_RESPONSE;
            }
            if (failure instanceof CancellationException) return DeepSeekCallOutcome.CANCELLED;
        }
        return DeepSeekCallOutcome.TRANSPORT_ERROR;
    }

    private void record(DeepSeekCallOperation operation, DeepSeekCallOutcome outcome, long started, int httpStatus) {
        long millis = TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - started));
        LOG.info("deepseek_call operation={} outcome={} durationMs={} inFlight={} limit={} httpStatus={}",
                operation, outcome, millis, properties.maxConcurrentRequests() - slots.availablePermits(),
                properties.maxConcurrentRequests(), httpStatus);
    }
}
