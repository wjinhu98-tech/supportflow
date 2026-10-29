package dev.supportflow.common.traffic;

import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Trusted authenticated ownership only; request parameters and model-operation names never select buckets. */
public final class TrafficQuotaService {
    private final TrafficProperties properties;
    private final BoundedWindowLimiter requests;
    private final BoundedWindowLimiter modelCalls;
    private final MeterRegistry registry;

    public TrafficQuotaService(TrafficProperties properties, MeterRegistry registry, LongSupplier nanoTime) {
        this.properties = Objects.requireNonNull(properties);
        this.registry = Objects.requireNonNull(registry);
        requests = new BoundedWindowLimiter(properties.requestLimit(), properties.requestWindow(), properties.maxIdentities(), nanoTime);
        modelCalls = new BoundedWindowLimiter(properties.modelLimit(), properties.modelWindow(), properties.maxIdentities(), nanoTime);
    }

    public void requireRequest() { require(requests, "request"); }

    /** Each provider attempt, including an Agent retry, consumes the same owner's shared model-call bucket. */
    public void requireModelCall() { require(modelCalls, "model"); }

    private void require(BoundedWindowLimiter limiter, String scope) {
        if (!properties.enabled()) return;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CustomerPrincipal principal))
            throw new AccessDeniedException("Authenticated account required for quota admission");
        // Buyers sharing an identity also share a bucket, so another login cannot bypass a buyer quota.
        String key = principal.getAccountType() == AccountType.BUYER
                ? "buyer:" + principal.getIdentity().buyerId() : "merchant:" + principal.getUsername();
        var admission = limiter.acquire(key);
        registry.counter("supportflow.traffic.admissions", "scope", scope,
                "outcome", admission.outcome().name()).increment();
        if (!admission.allowed()) throw new TrafficLimitExceededException(admission.retryAfterSeconds());
    }
}
