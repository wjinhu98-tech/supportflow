package dev.supportflow.assistant.runtime;

import dev.supportflow.assistant.planning.ExplicitOrderNumbers;
import dev.supportflow.assistant.planning.PlannedToolCall;
import dev.supportflow.assistant.planning.ToolName;
import dev.supportflow.assistant.planning.ToolPlanner;
import dev.supportflow.assistant.runtime.store.AgentFailureCode;
import dev.supportflow.assistant.runtime.store.AgentPhase;
import dev.supportflow.assistant.runtime.store.AgentRun;
import dev.supportflow.assistant.runtime.store.AgentRunInput;
import dev.supportflow.assistant.runtime.store.AgentRunKey;
import dev.supportflow.assistant.runtime.store.AgentRunStatus;
import dev.supportflow.assistant.runtime.store.AgentRunStore;
import dev.supportflow.assistant.tool.LogisticsTool;
import dev.supportflow.assistant.tool.OrderTool;
import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.chat.conversation.ConversationException;
import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import dev.supportflow.order.domain.model.SupportOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Durable, bounded read-only phases. The model proposes an identifier once; Java owns all transitions.
 * This is deliberately not an autonomous model loop or a replay mechanism for refund writes.
 */
@Service
public class ReadOnlyAgentRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(ReadOnlyAgentRuntime.class);
    private static final int PHASES_PER_INVOCATION = 3;
    private static final String NEED_ORDER = "请先在「订单」中选择一笔订单，或提供当前店铺的订单号，再查询物流。";

    private final AgentRunStore store;
    private final ToolPlanner planner;
    private final OrderTool orderTool;
    private final LogisticsTool logistics;
    private final OrderQueryService orders;
    private final AgentRuntimeProperties properties;
    private final Clock clock;

    @Autowired
    public ReadOnlyAgentRuntime(AgentRunStore store, ToolPlanner planner, OrderTool orderTool,
                                LogisticsTool logistics, OrderQueryService orders,
                                AgentRuntimeProperties properties) {
        this(store, planner, orderTool, logistics, orders, properties, Clock.systemUTC());
    }

    ReadOnlyAgentRuntime(AgentRunStore store, ToolPlanner planner, OrderTool orderTool,
                         LogisticsTool logistics, OrderQueryService orders,
                         AgentRuntimeProperties properties, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.planner = Objects.requireNonNull(planner);
        this.orderTool = Objects.requireNonNull(orderTool);
        this.logistics = Objects.requireNonNull(logistics);
        this.orders = Objects.requireNonNull(orders);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AgentRun execute(CustomerPrincipal principal, AgentRunInput input) {
        requireOwner(principal, input.key());
        // A selection is an untrusted identifier, not an authorization to read it or send it to a model.
        if (input.selectedOrderNo() != null) ownedOrder(principal, input.key(), input.selectedOrderNo());
        store.create(input, properties.maxSteps(), properties.totalTimeoutSeconds());
        AgentRun claimed = store.claim(input.key(), false);
        validateCheckpointScope(claimed, input.key());
        return advance(principal, claimed);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AgentRun resume(CustomerPrincipal principal, AgentRunKey key) {
        return resumeClaimed(principal, claimResume(principal, key));
    }

    /** Claim the run first, then let the coordinator reserve the matching conversation-turn generation. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AgentRun claimResume(CustomerPrincipal principal, AgentRunKey key) {
        findOwned(principal, key);
        AgentRun claimed = store.claim(key, true);
        validateCheckpointScope(claimed, key);
        return claimed;
    }

    /** Continue only the lease already claimed by this coordinator; do not claim a second generation. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AgentRun resumeClaimed(CustomerPrincipal principal, AgentRun claimed) {
        Objects.requireNonNull(claimed, "claimed run must not be null");
        requireOwner(principal, claimed.key());
        AgentRun current = store.find(claimed.key());
        validateCheckpointScope(current, claimed.key());
        if (!current.id().equals(claimed.id()) || current.generation() != claimed.generation()
                || current.status() != AgentRunStatus.SUCCEEDED && current.phase() != claimed.phase()) {
            throw ConversationException.conflict("运行已由其他执行者接管，请刷新进度。");
        }
        // advance rechecks current ownership even when only a completed result needs filling into the turn.
        return advance(principal, current);
    }

    /** Internal access for binding recovery back to the exact original conversation request. */
    public AgentRun findOwned(CustomerPrincipal principal, AgentRunKey key) {
        requireOwner(principal, key);
        AgentRun run = store.find(key);
        validateCheckpointScope(run, key);
        recheckKnownOrder(principal, run);
        return run;
    }

    public AgentRunView view(CustomerPrincipal principal, AgentRunKey key) {
        AgentRun run = findOwned(principal, key);
        Instant now = clock.instant();
        boolean remaining = now.isBefore(run.deadlineAt()) && run.attempts() < run.maxSteps();
        boolean resumable = run.status() == AgentRunStatus.SUCCEEDED
                || remaining && (run.status() == AgentRunStatus.PAUSED
                || run.status() == AgentRunStatus.RUNNING && run.leaseUntil() != null
                && !now.isBefore(run.leaseUntil()));
        return new AgentRunView(run.id(), key.clientMessageId(), run.status(), run.phase(), run.attempts(),
                run.maxSteps(), run.deadlineAt(), resumable,
                run.status() == AgentRunStatus.SUCCEEDED ? run.result() : null, store.steps(key));
    }

    private AgentRun advance(CustomerPrincipal principal, AgentRun initial) {
        AgentRun run = initial;
        requireOwner(principal, run.key());
        try {
            recheckKnownOrder(principal, run);
            for (int phase = 0; phase < PHASES_PER_INVOCATION; phase++) {
                if (run.status() == AgentRunStatus.SUCCEEDED) return completed(run);
                if (run.status() != AgentRunStatus.RUNNING || run.phase() == AgentPhase.COMPLETE) {
                    throw ConversationException.conflict("本次任务当前不能继续，请先查看处理进度。");
                }
                if (Thread.currentThread().isInterrupted()) {
                    store.pause(run.key(), run.generation(), AgentFailureCode.EXECUTION_INTERRUPTED);
                    throw ConversationException.conflict("本次任务已停止，请先查看处理进度。");
                }
                // The store atomically checks the original deadline, consumes an attempt, and persists intent.
                run = store.beginStep(run.key(), run.generation(), run.phase());
                run = switch (run.phase()) {
                    case PLAN -> plan(run);
                    case ORDER_LOOKUP -> lookupOrder(principal, run);
                    case LOGISTICS_LOOKUP -> lookupLogistics(principal, run);
                    case COMPLETE -> throw ConversationException.unavailable();
                };
            }
            if (run.status() == AgentRunStatus.SUCCEEDED) return completed(run);
            throw ConversationException.unavailable();
        } catch (RuntimeException failure) {
            // A lease/generation conflict belongs to the store; an old worker must not change that state.
            if (!(failure instanceof ConversationException conflict
                    && conflict.code() == ConversationException.Code.CONVERSATION_CONFLICT)) {
                pauseSafely(run, failure);
            }
            if (failure instanceof AccessDeniedException || failure instanceof OrderNotFoundException
                    || failure instanceof ModelUnavailableException || failure instanceof ConversationException) {
                throw failure;
            }
            throw ConversationException.unavailable();
        }
    }

    private AgentRun plan(AgentRun run) {
        PlannedToolCall call = planner.plan(ToolName.LOGISTICS_LOOKUP, run.message(), run.selectedOrderNo());
        if (call == null || call.tool() != ToolName.LOGISTICS_LOOKUP) throw new ModelUnavailableException();
        String orderNo = call.orderNo();
        if (orderNo != null && !orderNo.equals(run.selectedOrderNo())
                && !Pattern.compile("(?<![A-Za-z0-9_.-])" + Pattern.quote(orderNo)
                + "(?![A-Za-z0-9_.-])").matcher(run.message()).find()) {
            throw new ModelUnavailableException();
        }
        Set<String> explicit = ExplicitOrderNumbers.extract(run.message());
        if (explicit.size() > 1) orderNo = null;
        else if (explicit.size() == 1 && orderNo != null && !explicit.contains(orderNo)) {
            throw new ModelUnavailableException();
        }
        return store.checkpoint(run.key(), run.generation(), AgentPhase.PLAN,
                orderNo == null ? AgentPhase.COMPLETE : AgentPhase.ORDER_LOOKUP,
                orderNo, orderNo == null ? NEED_ORDER : null);
    }

    private AgentRun lookupOrder(CustomerPrincipal principal, AgentRun run) {
        requireOrderNumber(run);
        ownedOrder(principal, run.key(), run.orderNo());
        SupportOrder order = orderTool.lookup(principal.getIdentity(), run.key().shopId(), run.orderNo());
        validateOrderScope(order, run.key(), run.orderNo());
        // Persist only a safe tool-result summary, never an internal primary key or customer/buyer identifier.
        String observation = "订单 " + order.orderNo() + " 已通过当前店铺归属校验，状态为 "
                + order.status().getDescription() + "。";
        return store.checkpoint(run.key(), run.generation(), AgentPhase.ORDER_LOOKUP,
                AgentPhase.LOGISTICS_LOOKUP, run.orderNo(), observation);
    }

    private AgentRun lookupLogistics(CustomerPrincipal principal, AgentRun run) {
        requireOrderNumber(run);
        // Re-read current ownership and state. A completed order-step snapshot is never authorization.
        SupportOrder order = ownedOrder(principal, run.key(), run.orderNo());
        String result = logistics.lookup(order);
        if (result == null || result.isBlank()) throw ConversationException.unavailable();
        return store.checkpoint(run.key(), run.generation(), AgentPhase.LOGISTICS_LOOKUP,
                AgentPhase.COMPLETE, run.orderNo(), result);
    }

    private void recheckKnownOrder(CustomerPrincipal principal, AgentRun run) {
        String number = run.orderNo() != null ? run.orderNo() : run.selectedOrderNo();
        if (number != null) ownedOrder(principal, run.key(), number);
    }

    private SupportOrder ownedOrder(CustomerPrincipal principal, AgentRunKey key, String number) {
        requireOwner(principal, key);
        SupportOrder order = orders.getOwnedOrder(principal.getIdentity(), key.shopId(), number);
        validateOrderScope(order, key, number);
        return order;
    }

    private static void validateOrderScope(SupportOrder order, AgentRunKey key, String number) {
        if (order == null || order.tenantId() != key.shopId() || order.buyerId() != key.buyerId()
                || !number.equals(order.orderNo())) {
            throw new OrderNotFoundException();
        }
    }

    private static void requireOwner(CustomerPrincipal principal, AgentRunKey key) {
        Objects.requireNonNull(key, "key must not be null");
        if (principal == null || principal.getAccountType() != AccountType.BUYER
                || principal.getIdentity().buyerId() != key.buyerId()
                || !principal.getAuthorities().contains(SecurityPermission.ORDER_READ)) {
            throw new AccessDeniedException("Agent reads require the authenticated buyer and order-read permission");
        }
    }

    private static void validateCheckpointScope(AgentRun run, AgentRunKey key) {
        if (run == null || !key.equals(run.key())) throw ConversationException.notFound();
    }

    private static void requireOrderNumber(AgentRun run) {
        if (run.orderNo() == null) throw ConversationException.unavailable();
    }

    private static AgentRun completed(AgentRun run) {
        if (run.phase() != AgentPhase.COMPLETE || run.result() == null || run.result().isBlank()) {
            throw ConversationException.unavailable();
        }
        return run;
    }

    private void pauseSafely(AgentRun run, RuntimeException failure) {
        AgentFailureCode code = Thread.currentThread().isInterrupted() ? AgentFailureCode.EXECUTION_INTERRUPTED
                : failure instanceof ModelUnavailableException ? AgentFailureCode.MODEL_UNAVAILABLE
                : failure instanceof AccessDeniedException || failure instanceof OrderNotFoundException
                ? AgentFailureCode.VALIDATION_REJECTED : AgentFailureCode.TOOL_UNAVAILABLE;
        try {
            // Terminal states and stale lease generations must be no-ops, enforced by the store.
            store.pause(run.key(), run.generation(), code);
        } catch (RuntimeException ignored) {
            LOG.warn("Agent run failure could not be marked; its lease remains bounded");
        }
        LOG.warn("Read-only agent run paused: phase={}, outcome={}", run.phase(), code);
    }
}
