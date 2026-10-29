package dev.supportflow.common.traffic;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedWindowLimiterTest {
    private final AtomicLong clock = new AtomicLong();

    @Test void repeatedSequentialCallsHaveOneFixedWindowAndIndependentOwners() {
        var limiter = new BoundedWindowLimiter(2, Duration.ofSeconds(10), 4, clock::get);
        assertThat(limiter.acquire("first").allowed()).isTrue();
        clock.set(Duration.ofSeconds(5).toNanos());
        assertThat(limiter.acquire("first").allowed()).isTrue();
        var limited = limiter.acquire("first");
        assertThat(limited.outcome()).isEqualTo(BoundedWindowLimiter.Outcome.LIMIT_REACHED);
        assertThat(limited.retryAfterSeconds()).isEqualTo(5);
        assertThat(limiter.acquire("second").allowed()).isTrue();
        clock.set(Duration.ofSeconds(10).toNanos());
        assertThat(limiter.acquire("first").allowed()).isTrue();
        assertThat(limiter.retainedIdentities()).isEqualTo(2);
    }

    @Test void capacityCannotEvictAnotherOwnersActiveQuotaAndExpiredEntriesAreReclaimed() {
        var limiter = new BoundedWindowLimiter(1, Duration.ofSeconds(10), 2, clock::get);
        limiter.acquire("first");
        clock.set(Duration.ofSeconds(1).toNanos());
        limiter.acquire("second");
        var rejected = limiter.acquire("third");
        assertThat(rejected.outcome()).isEqualTo(BoundedWindowLimiter.Outcome.CAPACITY_REACHED);
        assertThat(rejected.retryAfterSeconds()).isEqualTo(9);
        assertThat(limiter.acquire("first").allowed()).isFalse();
        assertThat(limiter.retainedIdentities()).isEqualTo(2);
        clock.set(Duration.ofSeconds(10).toNanos());
        assertThat(limiter.acquire("third").allowed()).isTrue();
        assertThat(limiter.acquire("second").allowed()).isFalse();
        clock.set(Duration.ofSeconds(21).toNanos());
        assertThat(limiter.retainedIdentities()).isZero();
    }

    @Test void simultaneousAdmissionsCannotOverspend() throws Exception {
        var limiter = new BoundedWindowLimiter(3, Duration.ofMinutes(1), 4, clock::get);
        var ready = new CountDownLatch(12);
        var start = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(12)) {
            for (int i = 0; i < 12; i++) workers.submit(() -> {
                ready.countDown();
                try { start.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                if (limiter.acquire("same").allowed()) accepted.incrementAndGet();
            });
            try { assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
        } finally { start.countDown(); }
        assertThat(accepted.get()).isEqualTo(3);
    }
}
