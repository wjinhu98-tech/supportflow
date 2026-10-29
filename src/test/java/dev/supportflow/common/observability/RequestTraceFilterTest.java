package dev.supportflow.common.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestTraceFilterTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RequestTraceFilter filter = new RequestTraceFilter(registry);
    private final Logger logger = (Logger) LoggerFactory.getLogger(StageTrace.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>() {
        @Override protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    @BeforeEach void capture() { MDC.clear(); events.start(); logger.addAppender(events); }
    @AfterEach void clear() { logger.detachAppender(events); events.stop(); MDC.clear(); registry.close(); }

    @Test void generatesAnOpaqueServerIdAndCorrelatesRunAndStagesWithoutRequestData() throws Exception {
        String privateData = "buyer-secret-order-account-key";
        var request = new MockHttpServletRequest("POST", "/api/v1/shops/987/chat/messages");
        request.addHeader("X-Trace-Id", privateData + "\r\nforged=true");
        request.addHeader("Authorization", privateData);
        request.setContent(privateData.getBytes(StandardCharsets.UTF_8));
        request.setQueryString("buyerId=987&token=" + privateData);
        var response = new MockHttpServletResponse();
        var seen = new AtomicReference<String>();
        UUID run = UUID.randomUUID();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            seen.set(TraceContext.traceId());
            try (var scoped = TraceContext.agentRun(run); var stage = StageTrace.start(StageTrace.Stage.ORDER_TOOL)) {
                assertThat(TraceContext.agentRunId()).isEqualTo(run.toString());
                stage.succeeded();
            }
            assertThat(TraceContext.agentRunId()).isEqualTo("none");
        });
        assertThat(response.getHeader("X-Trace-Id")).matches("[0-9a-f]{32}").isEqualTo(seen.get());
        assertThat(events.list).hasSize(2).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(privateData, "buyerId", "987", "Authorization", "\r", "\n");
            assertThat(event.getMDCPropertyMap().get("traceId")).isEqualTo(seen.get());
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(events.list.getFirst().getMDCPropertyMap().get("agentRunId")).isEqualTo(run.toString());
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("agentRunId")).isNull();
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isIn("stage", "outcome")));
    }

    @Test void failureStillReturnsTraceAndCleansCallerThreadForNextRequest() throws Exception {
        var first = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), first,
                (request, response) -> { throw new ServletException("private-exception-payload"); }))
                .isInstanceOf(ServletException.class);
        assertThat(first.getHeader("X-Trace-Id")).matches("[0-9a-f]{32}");
        assertThat(MDC.get("traceId")).isNull();
        var second = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), second, (request, response) -> { });
        assertThat(second.getHeader("X-Trace-Id")).isNotEqualTo(first.getHeader("X-Trace-Id"));
        assertThat(events.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("private-exception-payload"));
        assertThat(registry.get("supportflow.request.stage.duration").tag("outcome", "SERVER_ERROR").timer().count()).isEqualTo(1);
    }

    @Test void nestedScopeRestoresPreviousContextAndDoesNotInheritIntoNewThread() throws Exception {
        try (var context = TraceContext.beginRequest()) {
            String trace = TraceContext.traceId();
            try (var scoped = TraceContext.agentRun(UUID.randomUUID())) { assertThat(TraceContext.traceId()).isEqualTo(trace); }
            assertThat(TraceContext.agentRunId()).isEqualTo("none");
            var other = new AtomicReference<String>();
            Thread worker = new Thread(() -> other.set(TraceContext.traceId()));
            worker.start();
            worker.join();
            assertThat(other.get()).isEqualTo("none");
        }
    }
}
