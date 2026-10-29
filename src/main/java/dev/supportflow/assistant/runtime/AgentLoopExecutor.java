package dev.supportflow.assistant.runtime;

import dev.supportflow.assistant.planning.ExplicitOrderNumbers;
import dev.supportflow.assistant.runtime.model.AgentAction;
import dev.supportflow.assistant.runtime.model.AgentDecision;
import dev.supportflow.assistant.runtime.model.AgentLoopState;
import dev.supportflow.assistant.runtime.model.AgentObservation;
import dev.supportflow.assistant.runtime.planning.AgentDecisionInput;
import dev.supportflow.assistant.runtime.planning.AgentDecisionPlanner;
import dev.supportflow.assistant.runtime.store.AgentFailureCode;
import dev.supportflow.assistant.runtime.store.AgentPhase;
import dev.supportflow.assistant.runtime.store.AgentRun;
import dev.supportflow.assistant.runtime.store.AgentRunInput;
import dev.supportflow.assistant.runtime.store.AgentRunStatus;
import dev.supportflow.assistant.runtime.store.AgentRunStore;
import dev.supportflow.assistant.runtime.store.AgentStepFailure;
import dev.supportflow.assistant.tool.LogisticsTool;
import dev.supportflow.assistant.tool.OrderTool;
import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.chat.conversation.ConversationException;
import dev.supportflow.common.observability.TraceContext;
import dev.supportflow.common.observability.StageTrace;
import dev.supportflow.common.traffic.TrafficLimitExceededException;
import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.ArrayList;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Observe -> decide -> validate -> execute -> checkpoint. Only server-owned, read-only capabilities exist. */
@Service
public class AgentLoopExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(AgentLoopExecutor.class);
    private final AgentRunStore store;
    private final AgentDecisionPlanner planner;
    private final OrderTool orders;
    private final LogisticsTool logistics;
    private final OrderQueryService authorization;
    private final AgentRuntimeProperties properties;
    private final AgentRetryProperties retries;
    private final AgentRetryMetrics metrics;

    public AgentLoopExecutor(AgentRunStore store, AgentDecisionPlanner planner, OrderTool orders,
                              LogisticsTool logistics, OrderQueryService authorization, AgentRuntimeProperties properties) {
        this(store, planner, orders, logistics, authorization, properties,
                new AgentRetryProperties(1, Duration.ofMillis(250)), new SimpleMeterRegistry());
    }

    @Autowired
    public AgentLoopExecutor(AgentRunStore store, AgentDecisionPlanner planner, OrderTool orders,
                              LogisticsTool logistics, OrderQueryService authorization, AgentRuntimeProperties properties,
                              AgentRetryProperties retries, MeterRegistry registry) {
        this.store = store;
        this.planner = planner;
        this.orders = orders;
        this.logistics = logistics;
        this.authorization = authorization;
        this.properties = properties;
        this.retries = retries;
        this.metrics = new AgentRetryMetrics(registry);
    }

    public AgentRun create(AgentRunInput input) {
        return store.createLoop(input, properties.maxSteps(), properties.totalTimeoutSeconds(),
                properties.budget(planner.profile()), retries.policy());
    }

    public AgentRun advance(CustomerPrincipal principal, AgentRun initial) {
        requireOwner(principal, initial);
        try (var trace = TraceContext.agentRun(initial.id())) {
            return advanceTraced(principal, initial);
        }
    }

    private AgentRun advanceTraced(CustomerPrincipal principal, AgentRun initial) {
        AgentRun run = initial;
        requireOwner(principal, run);
        try {
            if (run.workflowVersion() != 2 || run.loopState() == null || run.budget() == null) throw rejected();
            if (run.orderNo() != null) ownedOrder(principal, run);
            // One final guard-only pass lets the store persist the exhausted state atomically.
            for (int iteration = 0; iteration <= initial.maxSteps(); iteration++) {
                if (run.status() == AgentRunStatus.SUCCEEDED) return run;
                if (run.status() != AgentRunStatus.RUNNING || Thread.currentThread().isInterrupted())
                    throw ConversationException.conflict("本次查询已停止，请查看运行进度");
                // Reusing a saved observation is not permission to send it to a model after ownership changes.
                if (run.phase() == AgentPhase.PLAN && !run.loopState().observations().isEmpty()) ownedOrder(principal, run);
                run = run.phase() == AgentPhase.PLAN ? store.beginDecision(run.key(), run.generation())
                        : store.beginStep(run.key(), run.generation(), run.phase());
                long started = System.nanoTime();
                AgentPhase phase = run.phase();
                boolean success = false;
                try (var stageTrace = StageTrace.start(traceStage(phase))) {
                    run = switch (phase) {
                        case PLAN -> decide(run);
                        case ORDER_LOOKUP, LOGISTICS_LOOKUP -> executeTool(principal, run);
                        case COMPLETE -> throw rejected();
                    };
                    success = true;
                    stageTrace.succeeded();
                } catch (RuntimeException failure) {
                    // A checkpoint conflict or storage failure is never a reason to replay a remote request.
                    if (failure instanceof ConversationException || failure instanceof TrafficLimitExceededException) throw failure;
                    AgentStepFailure kind = classify(failure);
                    boolean scheduled = store.retryStep(run.key(), run.generation(), phase, kind);
                    metrics.failure(phase, kind, scheduled);
                    LOG.info("agent_retry phase={} failure={} scheduled={}", phase, kind, scheduled);
                    if (!scheduled) throw failure;
                    waitBeforeRetry(run.retryPolicy().delayMillis());
                } finally {
                    metrics.step(phase, System.nanoTime() - started, success);
                }
            }
            if (run.status() == AgentRunStatus.SUCCEEDED) return run;
            throw ConversationException.conflict("本次查询已达到步骤上限");
        } catch (RuntimeException failure) {
            if (!(failure instanceof ConversationException conflict
                    && conflict.code() == ConversationException.Code.CONVERSATION_CONFLICT)) {
                AgentFailureCode code = failure instanceof DecisionRejectedException || failure instanceof AccessDeniedException
                        || failure instanceof OrderNotFoundException ? AgentFailureCode.VALIDATION_REJECTED
                        : failure instanceof ModelUnavailableException unavailable
                        && unavailable.kind() == dev.supportflow.chat.application.ModelFailureKind.INTERRUPTED
                        ? AgentFailureCode.EXECUTION_INTERRUPTED
                        : failure instanceof ModelUnavailableException || failure instanceof TrafficLimitExceededException ? AgentFailureCode.MODEL_UNAVAILABLE
                        : AgentFailureCode.TOOL_UNAVAILABLE;
                try { store.pause(run.key(), run.generation(), code); }
                catch (RuntimeException ignored) { LOG.warn("Agent loop failure marker unavailable; lease remains bounded"); }
                LOG.warn("Agent loop stopped: phase={}, outcome={}", run.phase(), code);
            }
            if (failure instanceof ConversationException || failure instanceof ModelUnavailableException || failure instanceof TrafficLimitExceededException
                    || failure instanceof AccessDeniedException || failure instanceof OrderNotFoundException) throw failure;
            throw new ModelUnavailableException();
        }
    }

    private static StageTrace.Stage traceStage(AgentPhase phase) {
        return switch (phase) {
            case PLAN, COMPLETE -> StageTrace.Stage.AGENT_PLAN;
            case ORDER_LOOKUP -> StageTrace.Stage.ORDER_TOOL;
            case LOGISTICS_LOOKUP -> StageTrace.Stage.LOGISTICS_TOOL;
        };
    }

    private AgentRun decide(AgentRun run) {
        if (!run.budget().profile().equals(planner.profile())) throw rejected();
        var outcome = planner.decide(new AgentDecisionInput(run.request().message(), run.selectedOrderNo(), run.loopState(),
                run.budget().inputAllowance(), run.budget().outputAllowance(), callTimeout(run)));
        if (outcome == null) throw new ModelUnavailableException();
        // Validate usage before any checkpoint; unknown/invalid outcomes retain the entire reservation.
        try { run.budget().settle(outcome.usage()); }
        catch (IllegalArgumentException failure) { throw new ModelUnavailableException(); }
        AgentDecision decision = outcome.decision();
        validateDecision(run, decision);
        AgentPhase next = switch (decision.action()) {
            case ORDER_LOOKUP -> AgentPhase.ORDER_LOOKUP;
            case LOGISTICS_LOOKUP -> AgentPhase.LOGISTICS_LOOKUP;
            case ANSWER, CLARIFY -> AgentPhase.COMPLETE;
        };
        String result = decision.action() == AgentAction.CLARIFY
                ? "请先在「订单」中选择一笔订单，或明确需要查询的订单号。"
                : decision.action() == AgentAction.ANSWER ? run.loopState().observations().stream()
                .map(AgentObservation::content).collect(Collectors.joining("\n")) : null;
        AgentLoopState state = new AgentLoopState(run.loopState().observations(), next == AgentPhase.COMPLETE ? null : decision);
        return checkpoint(() -> store.checkpointLoop(run.key(), run.generation(), AgentPhase.PLAN, next,
                decision.orderNo(), result, state, outcome.usage()));
    }

    private void validateDecision(AgentRun run, AgentDecision decision) {
        AgentLoopState state = run.loopState();
        String number = decision.orderNo();
        if (state.observations().stream().anyMatch(observation -> observation.callId().equals(decision.callId()))) throw rejected();
        if (run.orderNo() != null && !run.orderNo().equals(number)) throw rejected();
        var explicit = ExplicitOrderNumbers.extract(run.request().message());
        if (decision.action() == AgentAction.CLARIFY) {
            if (!state.observations().isEmpty() || number != null
                    || run.selectedOrderNo() != null && explicit.size() <= 1 || explicit.size() == 1) throw rejected();
            return;
        }
        if (number == null || explicit.size() > 1 || !number.equals(run.selectedOrderNo())
                && !supplied(run.request().message(), number) && !supplied(run.message(), number)) throw rejected();
        if (explicit.size() == 1 && !explicit.contains(number)) throw rejected();
        switch (decision.action()) {
            case ORDER_LOOKUP -> { if (state.order() != null) throw rejected(); }
            case LOGISTICS_LOOKUP -> {
                if (state.order() == null || state.logistics() != null || !shipped(state.order().orderStatus())) throw rejected();
            }
            case ANSWER -> {
                if (state.order() == null || shipped(state.order().orderStatus()) && state.logistics() == null) throw rejected();
            }
            case CLARIFY -> throw rejected();
        }
    }

    private AgentRun executeTool(CustomerPrincipal principal, AgentRun run) {
        AgentDecision decision = run.loopState().pending();
        if (decision == null || !decision.orderNo().equals(run.orderNo())
                || run.phase() == AgentPhase.ORDER_LOOKUP && decision.action() != AgentAction.ORDER_LOOKUP
                || run.phase() == AgentPhase.LOGISTICS_LOOKUP && decision.action() != AgentAction.LOGISTICS_LOOKUP) throw rejected();
        validateDecision(run, decision);
        SupportOrder order = ownedOrder(principal, run);
        String content;
        if (decision.action() == AgentAction.ORDER_LOOKUP) {
            order = orders.lookup(principal.getIdentity(), run.key().shopId(), run.orderNo());
            verifyOrder(order, run);
            content = "店铺 " + run.key().shopId() + " 的订单 " + order.orderNo() + "："
                    + order.status().getDescription() + "，订单金额 " + order.totalAmount().toPlainString() + " " + order.currency() + "。";
        } else content = logistics.lookup(order);
        var observation = new AgentObservation(decision.action(), run.orderNo(), decision.callId(), content, order.status());
        var observations = new ArrayList<>(run.loopState().observations());
        observations.add(observation);
        return checkpoint(() -> store.checkpointLoop(run.key(), run.generation(), run.phase(), AgentPhase.PLAN,
                run.orderNo(), content, new AgentLoopState(observations, null), null));
    }

    private static AgentStepFailure classify(RuntimeException failure) {
        if (failure instanceof ModelUnavailableException model) return switch (model.kind()) {
            case TIMEOUT -> AgentStepFailure.MODEL_TIMEOUT;
            case TRANSPORT -> AgentStepFailure.MODEL_TRANSPORT;
            case PROVIDER_TEMPORARY -> AgentStepFailure.MODEL_TEMPORARY;
            case RATE_LIMITED -> AgentStepFailure.MODEL_RATE_LIMITED;
            case PROVIDER_REJECTED -> AgentStepFailure.MODEL_REJECTED;
            case INVALID_RESPONSE -> AgentStepFailure.MODEL_INVALID_RESPONSE;
            case CIRCUIT_OPEN -> AgentStepFailure.MODEL_CIRCUIT_OPEN;
            case BUSY -> AgentStepFailure.MODEL_BUSY;
            case INTERRUPTED -> AgentStepFailure.EXECUTION_INTERRUPTED;
            case CANCELLED -> AgentStepFailure.MODEL_CANCELLED;
            case UNCLASSIFIED -> AgentStepFailure.MODEL_UNAVAILABLE;
        };
        if (failure instanceof OrderNotFoundException || failure instanceof AccessDeniedException
                || failure instanceof DecisionRejectedException) return AgentStepFailure.VALIDATION_REJECTED;
        if (failure instanceof TransientDataAccessException || failure instanceof RecoverableDataAccessException)
            return AgentStepFailure.TOOL_TRANSIENT;
        return AgentStepFailure.TOOL_UNAVAILABLE;
    }

    private static AgentRun checkpoint(Supplier<AgentRun> save) {
        try { return save.get(); }
        catch (ConversationException conflict) { throw conflict; }
        catch (RuntimeException failure) { throw new CheckpointUnavailableException(); }
    }

    private static Duration callTimeout(AgentRun run) {
        Instant until = run.leaseUntil().isBefore(run.deadlineAt()) ? run.leaseUntil() : run.deadlineAt();
        long millis = Math.max(1, Math.min(60000, Duration.between(Instant.now(), until).toMillis() - 50));
        return Duration.ofMillis(millis);
    }

    private static void waitBeforeRetry(int millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException(dev.supportflow.chat.application.ModelFailureKind.INTERRUPTED);
        }
    }

    private SupportOrder ownedOrder(CustomerPrincipal principal, AgentRun run) {
        requireOwner(principal, run);
        SupportOrder order = authorization.getOwnedOrder(principal.getIdentity(), run.key().shopId(), run.orderNo());
        verifyOrder(order, run);
        return order;
    }
    private static void verifyOrder(SupportOrder order, AgentRun run) {
        if (order == null || order.buyerId() != run.key().buyerId() || order.tenantId() != run.key().shopId()
                || !order.orderNo().equals(run.orderNo())) throw new OrderNotFoundException();
    }
    private static void requireOwner(CustomerPrincipal principal, AgentRun run) {
        if (principal == null || principal.getAccountType() != AccountType.BUYER
                || principal.getIdentity().buyerId() != run.key().buyerId()
                || !principal.getAuthorities().contains(SecurityPermission.ORDER_READ)) throw new AccessDeniedException("Owned read-only tools required");
    }
    private static boolean supplied(String message, String number) {
        return Pattern.compile("(?<![A-Za-z0-9_.-])" + Pattern.quote(number) + "(?![A-Za-z0-9_.-])").matcher(message).find();
    }
    private static boolean shipped(OrderStatus status) { return status == OrderStatus.SHIPPED || status == OrderStatus.COMPLETED; }
    private static DecisionRejectedException rejected() { return new DecisionRejectedException(); }
    private static final class DecisionRejectedException extends RuntimeException { }
    private static final class CheckpointUnavailableException extends RuntimeException { }
}
