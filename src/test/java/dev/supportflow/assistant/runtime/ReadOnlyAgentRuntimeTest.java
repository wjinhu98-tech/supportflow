package dev.supportflow.assistant.runtime;

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
import dev.supportflow.assistant.runtime.store.AgentStepStatus;
import dev.supportflow.assistant.runtime.store.AgentStepView;
import dev.supportflow.assistant.tool.LogisticsTool;
import dev.supportflow.assistant.tool.OrderTool;
import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.chat.conversation.ConversationException;
import dev.supportflow.chat.conversation.ConversationMessageRequest;
import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReadOnlyAgentRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AgentRunStore store = mock(AgentRunStore.class);
    private final ToolPlanner planner = mock(ToolPlanner.class);
    private final OrderTool orderTool = mock(OrderTool.class);
    private final LogisticsTool logistics = mock(LogisticsTool.class);
    private final OrderQueryService orders = mock(OrderQueryService.class);
    private final CustomerIdentity identity = new CustomerIdentity(200);
    private final CustomerPrincipal buyer = principal(identity, AccountType.BUYER,
            List.of(SecurityPermission.ORDER_READ));
    private final AgentRunKey key = new AgentRunKey(UUID.randomUUID(), UUID.randomUUID(), 200, 100);
    private final ConversationMessageRequest request = new ConversationMessageRequest(key.clientMessageId(),
            "请查询SF10001的物流", null);
    private final AgentRunInput input = new AgentRunInput(key, request, "SF10001物流", null);
    private final SupportOrder order = new SupportOrder(912_345, 100, 987_654, 200,
            "SF10001", OrderStatus.SHIPPED, new BigDecimal("100.00"), "CNY", NOW, NOW);
    private final AtomicReference<AgentRun> state = new AtomicReference<>();
    private ReadOnlyAgentRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = runtime();
        state.set(run(AgentPhase.PLAN, AgentRunStatus.PAUSED, null, null, 0, 0));
        when(store.create(any(), anyInt(), anyInt())).thenAnswer(ignored -> state.get());
        when(store.find(key)).thenAnswer(ignored -> state.get());
        when(store.claim(eq(key), anyBoolean())).thenAnswer(ignored -> {
            AgentRun previous = state.get();
            if (previous.status() == AgentRunStatus.SUCCEEDED) return previous;
            AgentRun claimed = copy(previous, previous.phase(), AgentRunStatus.RUNNING, previous.orderNo(),
                    previous.result(), previous.attempts(), previous.generation() + 1);
            state.set(claimed);
            return claimed;
        });
        when(store.beginStep(eq(key), anyLong(), any())).thenAnswer(ignored -> {
            AgentRun previous = state.get();
            AgentRun started = copy(previous, previous.phase(), AgentRunStatus.RUNNING, previous.orderNo(),
                    previous.result(), previous.attempts() + 1, previous.generation());
            state.set(started);
            return started;
        });
        when(store.checkpoint(eq(key), anyLong(), any(), any(), any(), any())).thenAnswer(invocation -> {
            AgentRun previous = state.get();
            AgentPhase next = invocation.getArgument(3);
            AgentRun checkpoint = copy(previous, next,
                    next == AgentPhase.COMPLETE ? AgentRunStatus.SUCCEEDED : AgentRunStatus.RUNNING,
                    invocation.getArgument(4), invocation.getArgument(5), previous.attempts(), previous.generation());
            state.set(checkpoint);
            return checkpoint;
        });
        doAnswer(ignored -> {
            AgentRun previous = state.get();
            if (previous.status() == AgentRunStatus.RUNNING) state.set(copy(previous, previous.phase(),
                    AgentRunStatus.PAUSED, previous.orderNo(), previous.result(), previous.attempts(), previous.generation()));
            return null;
        }).when(store).pause(eq(key), anyLong(), any(AgentFailureCode.class));
        when(planner.plan(ToolName.LOGISTICS_LOOKUP, input.message(), null))
                .thenReturn(new PlannedToolCall(ToolName.LOGISTICS_LOOKUP, "SF10001"));
        when(orders.getOwnedOrder(identity, 100, "SF10001")).thenReturn(order);
        when(orderTool.lookup(identity, 100, "SF10001")).thenReturn(order);
        when(logistics.lookup(order)).thenReturn("订单 SF10001 的物流记录：包裹已出库。");
    }

    @Test
    void persistsIntentBeforeEachReadAndFinishesWithThreeBoundedPhases() {
        AgentRun completed = runtime.execute(buyer, input);
        assertThat(completed.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(completed.phase()).isEqualTo(AgentPhase.COMPLETE);
        assertThat(completed.attempts()).isEqualTo(3);
        assertThat(completed.result()).contains("SF10001", "包裹已出库");
        InOrder flow = inOrder(store, planner, orderTool, logistics);
        flow.verify(store).create(input, 8, 90);
        flow.verify(store).claim(key, false);
        flow.verify(store).beginStep(key, 1, AgentPhase.PLAN);
        flow.verify(planner).plan(ToolName.LOGISTICS_LOOKUP, input.message(), null);
        flow.verify(store).checkpoint(key, 1, AgentPhase.PLAN, AgentPhase.ORDER_LOOKUP, "SF10001", null);
        flow.verify(store).beginStep(key, 1, AgentPhase.ORDER_LOOKUP);
        flow.verify(orderTool).lookup(identity, 100, "SF10001");
        flow.verify(store).checkpoint(eq(key), eq(1L), eq(AgentPhase.ORDER_LOOKUP),
                eq(AgentPhase.LOGISTICS_LOOKUP), eq("SF10001"), anyString());
        flow.verify(store).beginStep(key, 1, AgentPhase.LOGISTICS_LOOKUP);
        flow.verify(logistics).lookup(order);
        flow.verify(store).checkpoint(key, 1, AgentPhase.LOGISTICS_LOOKUP, AgentPhase.COMPLETE,
                "SF10001", completed.result());
    }

    @Test
    void legacyRuntimePreservesQuotaRejectionAndNeverCallsBusinessTools() {
        var limited = new dev.supportflow.common.traffic.TrafficLimitExceededException(30);
        when(planner.plan(ToolName.LOGISTICS_LOOKUP, input.message(), null)).thenThrow(limited);
        assertThatThrownBy(() -> runtime.execute(buyer, input)).isSameAs(limited);
        verify(store).pause(eq(key), anyLong(), eq(AgentFailureCode.MODEL_UNAVAILABLE));
        verifyNoInteractions(orderTool, logistics);
    }

    @Test
    void persistedRunCorrelationReachesPlanningAndToolsOnTheCallerThread() {
        UUID persistedRunId = state.get().id();
        var trace = new AtomicReference<String>();
        when(planner.plan(ToolName.LOGISTICS_LOOKUP, input.message(), null)).thenAnswer(ignored -> {
            assertThat(dev.supportflow.common.observability.TraceContext.traceId()).isEqualTo(trace.get());
            assertThat(dev.supportflow.common.observability.TraceContext.agentRunId()).isEqualTo(persistedRunId.toString());
            return new PlannedToolCall(ToolName.LOGISTICS_LOOKUP, "SF10001");
        });
        when(logistics.lookup(order)).thenAnswer(ignored -> {
            assertThat(dev.supportflow.common.observability.TraceContext.traceId()).isEqualTo(trace.get());
            assertThat(dev.supportflow.common.observability.TraceContext.agentRunId()).isEqualTo(persistedRunId.toString());
            return "safe logistics result";
        });
        try (var scope = dev.supportflow.common.observability.TraceContext.beginRequest()) {
            trace.set(dev.supportflow.common.observability.TraceContext.traceId());
            runtime.execute(buyer, input);
            assertThat(dev.supportflow.common.observability.TraceContext.agentRunId()).isEqualTo("none");
        }
    }

    @Test
    void aNewRuntimeResumesAfterTheSavedOrderResultWithoutRepeatingCompletedTools() {
        state.set(run(AgentPhase.LOGISTICS_LOOKUP, AgentRunStatus.PAUSED, "SF10001", "safe order result", 2, 1));
        AgentRun resumed = runtime().resume(buyer, key);
        assertThat(resumed.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(resumed.attempts()).isEqualTo(3);
        assertThat(resumed.generation()).isEqualTo(2);
        verifyNoInteractions(planner, orderTool);
        verify(logistics).lookup(order);
        verify(orders, times(3)).getOwnedOrder(identity, 100, "SF10001");
        verify(store, never()).create(any(), anyInt(), anyInt());
    }

    @Test
    void completedRecoveryReusesTheResultButRechecksCurrentOwnership() {
        state.set(run(AgentPhase.COMPLETE, AgentRunStatus.SUCCEEDED, "SF10001", "saved final result", 3, 1));
        AgentRun cached = runtime.resume(buyer, key);
        assertThat(cached.result()).isEqualTo("saved final result");
        verifyNoInteractions(planner, orderTool, logistics);
        verify(orders, times(2)).getOwnedOrder(identity, 100, "SF10001");
        verify(store, never()).beginStep(any(), anyLong(), any());
    }

    @Test
    void claimingForRecoveryDoesNotExecuteUntilTheConversationTurnHasBeenReserved() {
        state.set(run(AgentPhase.LOGISTICS_LOOKUP, AgentRunStatus.PAUSED, "SF10001", "safe order result", 2, 1));
        AgentRun claimed = runtime.claimResume(buyer, key);
        assertThat(claimed.generation()).isEqualTo(2);
        verifyNoInteractions(planner, orderTool, logistics);
        verify(store, never()).beginStep(any(), anyLong(), any());

        AgentRun completed = runtime.resumeClaimed(buyer, claimed);
        assertThat(completed.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        verify(store, times(1)).claim(key, true);
        verify(logistics).lookup(order);
    }

    @Test
    void aClaimSupersededBeforeTurnReservationCannotExecuteOrPauseItsReplacement() {
        state.set(run(AgentPhase.LOGISTICS_LOOKUP, AgentRunStatus.PAUSED, "SF10001", "safe order result", 2, 1));
        AgentRun claimed = runtime.claimResume(buyer, key);
        state.set(copy(claimed, claimed.phase(), AgentRunStatus.RUNNING, claimed.orderNo(),
                claimed.result(), claimed.attempts(), claimed.generation() + 1));

        assertThatThrownBy(() -> runtime.resumeClaimed(buyer, claimed)).isInstanceOf(ConversationException.class);
        verify(store, never()).beginStep(any(), anyLong(), any());
        verify(store, never()).pause(any(), anyLong(), any(AgentFailureCode.class));
        verifyNoInteractions(planner, orderTool, logistics);
    }

    @Test
    void completedRecoveryNeverDisclosesAResultAfterOwnershipChanges() {
        state.set(run(AgentPhase.COMPLETE, AgentRunStatus.SUCCEEDED, "SF10001", "saved private result", 3, 1));
        when(orders.getOwnedOrder(identity, 100, "SF10001")).thenThrow(new OrderNotFoundException());
        assertThatThrownBy(() -> runtime.resume(buyer, key)).isInstanceOf(OrderNotFoundException.class);
        verify(store, never()).claim(any(), anyBoolean());
        verifyNoInteractions(planner, orderTool, logistics);
    }

    @Test
    void toolFailurePausesOnceAndNeverAutomaticallyRetriesOrPersistsTheRawFailure() {
        when(logistics.lookup(order)).thenThrow(new IllegalStateException("raw secret provider SQL detail"));
        assertThatThrownBy(() -> runtime.execute(buyer, input))
                .isInstanceOf(ConversationException.class)
                .hasMessageNotContaining("raw secret").hasMessageNotContaining("SQL");
        assertThat(state.get().status()).isEqualTo(AgentRunStatus.PAUSED);
        assertThat(state.get().phase()).isEqualTo(AgentPhase.LOGISTICS_LOOKUP);
        verify(logistics, times(1)).lookup(order);
        verify(store).pause(key, 1, AgentFailureCode.TOOL_UNAVAILABLE);
    }

    @Test
    void modelFailurePausesWithoutInvokingAnyBusinessTool() {
        when(planner.plan(ToolName.LOGISTICS_LOOKUP, input.message(), null)).thenThrow(new ModelUnavailableException());
        assertThatThrownBy(() -> runtime.execute(buyer, input)).isInstanceOf(ModelUnavailableException.class);
        verify(store).pause(key, 1, AgentFailureCode.MODEL_UNAVAILABLE);
        verifyNoInteractions(orderTool, logistics, orders);
    }

    @ParameterizedTest
    @EnumSource(value = ToolName.class, names = {"ORDER_LOOKUP", "AFTER_SALE_REQUEST"})
    void rejectsAnyPlannerToolOutsideTheSingleServerPermittedReadCapability(ToolName unexpected) {
        when(planner.plan(ToolName.LOGISTICS_LOOKUP, input.message(), null))
                .thenReturn(new PlannedToolCall(unexpected, "SF10001"));
        assertThatThrownBy(() -> runtime.execute(buyer, input)).isInstanceOf(ModelUnavailableException.class);
        verifyNoInteractions(orderTool, logistics, orders);
    }

    @Test
    void rejectsInventedIdentifiersInsteadOfReadingAnExistingUnmentionedOrder() {
        when(planner.plan(ToolName.LOGISTICS_LOOKUP, input.message(), null))
                .thenReturn(new PlannedToolCall(ToolName.LOGISTICS_LOOKUP, "SF99999"));
        assertThatThrownBy(() -> runtime.execute(buyer, input)).isInstanceOf(ModelUnavailableException.class);
        verifyNoInteractions(orderTool, logistics, orders);
    }

    @Test
    void explicitIdentifierCannotBeReplacedByAStaleSelection() {
        AgentRunInput selected = new AgentRunInput(key, request, "SF10002物流", "SF10001");
        state.set(new AgentRun(state.get().id(), key, request, selected.message(), selected.selectedOrderNo(),
                AgentPhase.PLAN, AgentRunStatus.PAUSED, null, null, 0, 8, NOW.plusSeconds(90), 0, null, null));
        when(planner.plan(ToolName.LOGISTICS_LOOKUP, selected.message(), "SF10001"))
                .thenReturn(new PlannedToolCall(ToolName.LOGISTICS_LOOKUP, "SF10001"));
        assertThatThrownBy(() -> runtime.execute(buyer, selected)).isInstanceOf(ModelUnavailableException.class);
        verifyNoInteractions(orderTool, logistics);
    }

    @Test
    void missingOrAmbiguousOrderEndsWithClarificationWithoutBusinessCalls() {
        when(planner.plan(ToolName.LOGISTICS_LOOKUP, input.message(), null))
                .thenReturn(new PlannedToolCall(ToolName.LOGISTICS_LOOKUP, null));
        AgentRun completed = runtime.execute(buyer, input);
        assertThat(completed.phase()).isEqualTo(AgentPhase.COMPLETE);
        assertThat(completed.result()).contains("选择一笔订单");
        assertThat(completed.attempts()).isEqualTo(1);
        verifyNoInteractions(orderTool, logistics, orders);
    }

    @Test
    void exhaustedOriginalBudgetCannotBeRefilledByRecovery() {
        state.set(run(AgentPhase.LOGISTICS_LOOKUP, AgentRunStatus.PAUSED, "SF10001", "safe result", 8, 1));
        // doAnswer avoids invoking the existing stateful default answer while replacing its stub.
        doAnswer(ignored -> {
            AgentRun previous = state.get();
            state.set(copy(previous, previous.phase(), AgentRunStatus.LIMIT_REACHED,
                    previous.orderNo(), previous.result(), previous.attempts(), previous.generation()));
            throw ConversationException.conflict("执行步骤已达到上限");
        }).when(store).beginStep(key, 2, AgentPhase.LOGISTICS_LOOKUP);
        assertThat(state.get().attempts()).isEqualTo(8);
        assertThatThrownBy(() -> runtime.resume(buyer, key)).isInstanceOf(ConversationException.class);
        assertThat(state.get().status()).isEqualTo(AgentRunStatus.LIMIT_REACHED);
        assertThat(state.get().attempts()).isEqualTo(8);
        verifyNoInteractions(planner, orderTool, logistics);
        verify(store, never()).create(any(), anyInt(), anyInt());
    }

    @Test
    void anExpiredOrSupersededLeaseConflictCannotPauseTheCurrentOwner() {
        state.set(run(AgentPhase.LOGISTICS_LOOKUP, AgentRunStatus.PAUSED, "SF10001", "safe result", 2, 1));
        doAnswer(ignored -> { throw ConversationException.conflict("执行租约已变化"); })
                .when(store).beginStep(key, 2, AgentPhase.LOGISTICS_LOOKUP);
        assertThatThrownBy(() -> runtime.resume(buyer, key)).isInstanceOf(ConversationException.class);
        verify(store, never()).pause(any(), anyLong(), any(AgentFailureCode.class));
        verifyNoInteractions(planner, orderTool, logistics);
    }

    @Test
    void anUnownedSelectionIsRejectedBeforeCreatingOrPlanningARun() {
        when(orders.getOwnedOrder(identity, 100, "SF10001")).thenThrow(new OrderNotFoundException());
        AgentRunInput selected = new AgentRunInput(key, request, "查物流", "SF10001");
        assertThatThrownBy(() -> runtime.execute(buyer, selected)).isInstanceOf(OrderNotFoundException.class);
        verifyNoInteractions(store, planner, orderTool, logistics);
    }

    @Test
    void missingPermissionWrongBuyerAndMerchantAccountsCannotInspectOrResumeRuns() {
        List<CustomerPrincipal> denied = List.of(principal(identity, AccountType.BUYER, List.of()),
                principal(new CustomerIdentity(201), AccountType.BUYER, List.of(SecurityPermission.ORDER_READ)),
                principal(identity, AccountType.MERCHANT_STAFF, List.of(SecurityPermission.ORDER_READ)));
        for (CustomerPrincipal account : denied) {
            assertThatThrownBy(() -> runtime.view(account, key)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> runtime.resume(account, key)).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(store, planner, orderTool, logistics, orders);
    }

    @Test
    void crossShopResultFromAnyToolIsRejectedBeforeReadingItsLogistics() {
        SupportOrder foreign = new SupportOrder(2, 101, 200, 200, "SF10001", OrderStatus.SHIPPED,
                new BigDecimal("100.00"), "CNY", NOW, NOW);
        when(orderTool.lookup(identity, 100, "SF10001")).thenReturn(foreign);
        assertThatThrownBy(() -> runtime.execute(buyer, input)).isInstanceOf(OrderNotFoundException.class);
        verify(store).pause(key, 1, AgentFailureCode.VALIDATION_REJECTED);
        verifyNoInteractions(logistics);
    }

    @Test
    void viewShowsOnlyProgressMetadataAndHidesPartialResultSnapshots() {
        state.set(run(AgentPhase.LOGISTICS_LOOKUP, AgentRunStatus.PAUSED, "SF10001", "partial private order result", 2, 1));
        when(store.steps(key)).thenReturn(List.of(new AgentStepView(UUID.randomUUID(), 1,
                AgentPhase.PLAN, AgentStepStatus.SUCCEEDED, NOW, NOW)));
        AgentRunView view = runtime.view(buyer, key);
        assertThat(view.result()).isNull();
        assertThat(view.resumable()).isTrue();
        assertThat(view.steps()).hasSize(1);
        assertThat(List.of(AgentRunView.class.getRecordComponents()).stream().map(component -> component.getName()))
                .doesNotContain("key", "buyerId", "shopId", "message", "request", "failureCode", "selectedOrderNo");
        verify(store, never()).claim(any(), anyBoolean());
        verifyNoInteractions(planner, orderTool, logistics);
    }

    @Test
    void expiredOrConsumedBudgetsAreNotAdvertisedAsResumable() {
        AgentRun previous = run(AgentPhase.LOGISTICS_LOOKUP, AgentRunStatus.PAUSED, "SF10001", null, 8, 1);
        state.set(previous);
        assertThat(runtime.view(buyer, key).resumable()).isFalse();
        state.set(new AgentRun(previous.id(), key, request, input.message(), null, previous.phase(), previous.status(),
                previous.orderNo(), null, 2, 8, NOW.minusSeconds(1), 1, null, null));
        assertThat(runtime.view(buyer, key).resumable()).isFalse();
    }

    private ReadOnlyAgentRuntime runtime() {
        return new ReadOnlyAgentRuntime(store, planner, orderTool, logistics, orders,
                new AgentRuntimeProperties(8, 90), CLOCK);
    }

    private AgentRun run(AgentPhase phase, AgentRunStatus status, String number, String result, int attempts, long generation) {
        return new AgentRun(UUID.randomUUID(), key, request, input.message(), input.selectedOrderNo(), phase,
                status, number, result, attempts, 8, NOW.plusSeconds(90), generation,
                status == AgentRunStatus.RUNNING ? NOW.plusSeconds(20) : null, null);
    }

    private AgentRun copy(AgentRun source, AgentPhase phase, AgentRunStatus status, String number,
                          String result, int attempts, long generation) {
        return new AgentRun(source.id(), source.key(), source.request(), source.message(), source.selectedOrderNo(),
                phase, status, number, result, attempts, source.maxSteps(), source.deadlineAt(), generation,
                status == AgentRunStatus.RUNNING ? NOW.plusSeconds(20) : null, source.failureCode());
    }

    private static CustomerPrincipal principal(CustomerIdentity identity, AccountType type,
                                                 List<SecurityPermission> permissions) {
        return new CustomerPrincipal("test-only", "not-a-real-hash", identity, permissions, type);
    }
}
