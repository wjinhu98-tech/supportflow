package dev.supportflow.chat.infrastructure.deepseek;

import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.chat.application.ModelFailureKind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.time.Duration;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Shared admission and circuit. Each invocation makes at most one HTTP request; retries belong to the durable runtime. */
public final class DeepSeekCallExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(DeepSeekCallExecutor.class);
    private static final URI ENDPOINT = URI.create("https://api.deepseek.com/chat/completions");
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final HttpClient client;
    private final DeepSeekProperties properties;
    private final Semaphore slots;
    private final DeepSeekCallMetrics metrics;
    private final DeepSeekCircuitBreaker circuit;

    public DeepSeekCallExecutor(HttpClient client, DeepSeekProperties properties) {
        this(client, properties, new SimpleMeterRegistry());
    }

    public DeepSeekCallExecutor(HttpClient client, DeepSeekProperties properties, MeterRegistry registry) {
        this.client = Objects.requireNonNull(client, "HTTP client is required");
        this.properties = Objects.requireNonNull(properties, "DeepSeek configuration is required");
        slots = new Semaphore(properties.maxConcurrentRequests());
        metrics = new DeepSeekCallMetrics(registry, slots, properties.maxConcurrentRequests());
        circuit = new DeepSeekCircuitBreaker(properties.circuit(), registry, System::nanoTime);
    }

    public <T> T execute(DeepSeekCallOperation operation, String requestBody, Function<byte[], T> parser) {
        return execute(operation, requestBody, parser, properties.requestTimeout());
    }

    public <T> T execute(DeepSeekCallOperation operation, String requestBody, Function<byte[], T> parser, Duration remainingTime) {
        Objects.requireNonNull(operation, "Operation is required");
        Objects.requireNonNull(requestBody, "Request body is required");
        Objects.requireNonNull(parser, "Response parser is required");
        if (remainingTime == null || remainingTime.isNegative() || remainingTime.isZero())
            throw new IllegalArgumentException("A positive call deadline is required");
        Duration effectiveTimeout = remainingTime.compareTo(properties.requestTimeout()) < 0 ? remainingTime : properties.requestTimeout();
        long started = System.nanoTime();
        if (!slots.tryAcquire()) {
            record(operation, DeepSeekCallOutcome.BUSY, started, 0);
            throw new ModelUnavailableException(ModelFailureKind.BUSY);
        }

        CompletableFuture<HttpResponse<byte[]>> pending = null;
        DeepSeekCallOutcome outcome = DeepSeekCallOutcome.TRANSPORT_ERROR;
        int httpStatus = 0;
        DeepSeekCircuitBreaker.Permit permit = null;
        try {
            permit = circuit.tryAcquire();
            if (permit == null) {
                outcome = DeepSeekCallOutcome.CIRCUIT_OPEN;
                throw new ModelUnavailableException();
            }
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(effectiveTimeout)
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            pending = client.sendAsync(request, ignored -> new DeepSeekResponseBodySubscriber(MAX_RESPONSE_BYTES));
            // Bound headers AND the entire body, including a peer that stalls after sending headers.
            long remaining = effectiveTimeout.toNanos() - (System.nanoTime() - started);
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
            throw new ModelUnavailableException(ModelFailureKind.INTERRUPTED);
        } catch (TimeoutException timeout) {
            outcome = DeepSeekCallOutcome.TIMEOUT;
            throw new ModelUnavailableException(ModelFailureKind.TIMEOUT);
        } catch (CancellationException cancelled) {
            outcome = DeepSeekCallOutcome.CANCELLED;
            throw new ModelUnavailableException(ModelFailureKind.CANCELLED);
        } catch (ExecutionException failure) {
            outcome = classifyFailure(failure.getCause());
            ModelFailureKind kind = outcome == DeepSeekCallOutcome.TRANSPORT_ERROR && !causedByIo(failure.getCause())
                    ? ModelFailureKind.UNCLASSIFIED : kind(outcome, httpStatus);
            throw new ModelUnavailableException(kind);
        } catch (RuntimeException failure) {
            // Never attach a provider/parser exception: even its message may contain credentials or data.
            throw new ModelUnavailableException(outcome == DeepSeekCallOutcome.TRANSPORT_ERROR
                    ? ModelFailureKind.UNCLASSIFIED : kind(outcome, httpStatus));
        } finally {
            try {
                if (outcome != DeepSeekCallOutcome.SUCCESS && pending != null) pending.cancel(true);
            } catch (RuntimeException ignored) {
                // Cancellation is best effort; a broken transport must not leak application capacity.
            } finally {
                try { circuit.complete(permit, outcome); }
                finally {
                    slots.release();
                    record(operation, outcome, started, httpStatus);
                }
            }
        }
    }

    private static ModelFailureKind kind(DeepSeekCallOutcome outcome, int status) {
        return switch (outcome) {
            case TIMEOUT -> ModelFailureKind.TIMEOUT;
            case TRANSPORT_ERROR -> ModelFailureKind.TRANSPORT;
            case PROVIDER_RATE_LIMITED -> ModelFailureKind.RATE_LIMITED;
            case PROVIDER_ERROR -> status == 500 || status == 502 || status == 503 || status == 504
                    ? ModelFailureKind.PROVIDER_TEMPORARY : ModelFailureKind.PROVIDER_REJECTED;
            case INVALID_RESPONSE -> ModelFailureKind.INVALID_RESPONSE;
            case CIRCUIT_OPEN -> ModelFailureKind.CIRCUIT_OPEN;
            case CANCELLED -> ModelFailureKind.CANCELLED;
            case INTERRUPTED -> ModelFailureKind.INTERRUPTED;
            case BUSY -> ModelFailureKind.BUSY;
            default -> ModelFailureKind.UNCLASSIFIED;
        };
    }

    private static boolean causedByIo(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof IOException) return true;
        }
        return false;
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
        long elapsedNanos = Math.max(0, System.nanoTime() - started);
        metrics.record(operation, outcome, elapsedNanos);
        long millis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        LOG.info("deepseek_call operation={} outcome={} durationMs={} inFlight={} limit={} httpStatus={}",
                operation, outcome, millis, properties.maxConcurrentRequests() - slots.availablePermits(),
                properties.maxConcurrentRequests(), httpStatus);
    }
}
