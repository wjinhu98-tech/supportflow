package dev.supportflow.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Wraps the security chain. Correlates synchronous handling only; no query strings, paths or headers are logged. */
public final class RequestTraceFilter extends OncePerRequestFilter {
    private final MeterRegistry registry;

    public RequestTraceFilter(MeterRegistry registry) { this.registry = java.util.Objects.requireNonNull(registry); }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        boolean completed = false;
        try (var context = TraceContext.beginRequest(); var stage = StageTrace.start(StageTrace.Stage.HTTP_REQUEST)) {
            response.setHeader("X-Trace-Id", TraceContext.traceId());
            try {
                chain.doFilter(request, response);
                completed = true;
                if (response.getStatus() < 400) stage.succeeded();
            } finally {
                String outcome = !completed || response.getStatus() >= 500 ? "SERVER_ERROR"
                        : response.getStatus() >= 400 ? "REJECTED" : "SUCCESS";
                Timer.builder("supportflow.request.stage.duration").tag("stage", "HTTP_REQUEST").tag("outcome", outcome)
                        .register(registry).record(Math.max(0, System.nanoTime() - started), TimeUnit.NANOSECONDS);
            }
        }
    }
}
