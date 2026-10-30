package dev.supportflow.assistant.runtime;

import dev.supportflow.assistant.runtime.model.AgentAction;
import dev.supportflow.assistant.runtime.model.AgentBudget;
import dev.supportflow.assistant.runtime.model.AgentDecision;
import dev.supportflow.assistant.runtime.model.AgentModelProfile;
import dev.supportflow.assistant.runtime.model.AgentLoopState;
import dev.supportflow.assistant.runtime.model.AgentTokenUsage;
import dev.supportflow.assistant.runtime.planning.AgentDecisionInput;
import dev.supportflow.assistant.runtime.planning.AgentDecisionPlanner;
import dev.supportflow.assistant.runtime.planning.AgentDecisionResult;
import dev.supportflow.assistant.runtime.planning.RuleBasedAgentDecisionPlanner;
import dev.supportflow.assistant.runtime.store.*;
import dev.supportflow.assistant.tool.LogisticsTool;
import dev.supportflow.assistant.tool.OrderTool;
import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.chat.application.ModelFailureKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.QueryTimeoutException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import dev.supportflow.chat.conversation.ConversationException;
import dev.supportflow.chat.conversation.ConversationMessageRequest;
import dev.supportflow.chat.conversation.JdbcConversationStore;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Actual MySQL run/step accounting with a deterministic fake model; never a paid provider or daily database. */
@Testcontainers
class AgentLoopPersistenceIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName("agent_loop_test").withUsername("root").withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%").withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true").withUrlParam("sslMode", "DISABLED")
            .withUrlParam("allowPublicKeyRetrieval", "true");
    private static final AgentModelProfile PROFILE = new AgentModelProfile("deepseek-flash", true);
    private final CustomerPrincipal buyer = new CustomerPrincipal("fake-buyer", "unused-test-only", new CustomerIdentity(200),
            List.of(SecurityPermission.ORDER_READ));
    private final AgentRuntimeProperties defaults = new AgentRuntimeProperties(8, 90);
    private AgentDecisionPlanner model;
    private OrderTool orderTool;
    private LogisticsTool logistics;
    private OrderQueryService authorization;
    private JdbcAgentRunStore store;
    private JdbcConversationStore conversations;
    private JdbcClient jdbc;
    private AgentLoopExecutor loop;
    private SupportOrder order;

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true).load().migrate();
    }
    @BeforeEach void setUp() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var transaction = new DataSourceTransactionManager(source);
        jdbc = JdbcClient.create(source);
        var json = new ObjectMapper();
        store = new JdbcAgentRunStore(jdbc, json, transaction);
        conversations = new JdbcConversationStore(jdbc, json, transaction);
        model = mock(AgentDecisionPlanner.class);
        orderTool = mock(OrderTool.class);
        logistics = mock(LogisticsTool.class);
        authorization = mock(OrderQueryService.class);
        when(model.profile()).thenReturn(PROFILE);
        when(model.decide(any())).thenAnswer(call -> response(call.getArgument(0)));
        order = order(OrderStatus.SHIPPED);
        when(authorization.getOwnedOrder(any(), anyLong(), anyString())).thenAnswer(call -> order);
        when(orderTool.lookup(any(), anyLong(), anyString())).thenAnswer(call -> order);
        when(logistics.lookup(any())).thenReturn("SF10001：快递已揽收，信息来自已录入事件。");
        loop = executor(defaults);
    }

    @Test void feedsActualObservationsBackToTheModelAndSettlesEachCallExactlyOnce() {
        AgentRun done = start();
        assertThat(done.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(done.workflowVersion()).isEqualTo(2);
        assertThat(done.attempts()).isEqualTo(5);
        assertThat(done.result()).contains("已发货", "快递已揽收", "100.00 CNY");
        assertThat(done.budget().chargedTokens()).isEqualTo(660);
        assertThat(done.budget().reportedTokens()).isEqualTo(660);
        assertThat(done.budget().chargedCostUsd()).isEqualByComparingTo("0.000252");
        ArgumentCaptor<AgentDecisionInput> inputs = ArgumentCaptor.forClass(AgentDecisionInput.class);
        verify(model, times(3)).decide(inputs.capture());
        assertThat(inputs.getAllValues().get(0).state().observations()).isEmpty();
        assertThat(inputs.getAllValues().get(1).state().order().orderStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(inputs.getAllValues().get(2).state().logistics().content()).contains("快递已揽收");
        assertThat(store.steps(done.key())).extracting(AgentStepView::phase)
                .containsExactly(AgentPhase.PLAN, AgentPhase.ORDER_LOOKUP, AgentPhase.PLAN, AgentPhase.LOGISTICS_LOOKUP, AgentPhase.PLAN);
        assertThat(store.steps(done.key()).stream().filter(step -> step.meteringStatus() == AgentMeteringStatus.SETTLED).count()).isEqualTo(3);
        verify(orderTool, times(1)).lookup(any(), anyLong(), anyString());
        verify(logistics, times(1)).lookup(any());
    }

    @Test void anUnshippedOrderFinishesAfterObservingOrderFactsWithoutCallingLogistics() {
        order = order(OrderStatus.PAID);
        AgentRun done = start();
        assertThat(done.attempts()).isEqualTo(3);
        assertThat(done.result()).contains("待发货").doesNotContain("已揽收");
        verify(model, times(2)).decide(any());
        verifyNoInteractions(logistics);
    }

    @Test void userModelQuotaRejectionPausesOnceWithoutRetryOrToolExecution() {
        var limited = new dev.supportflow.common.traffic.TrafficLimitExceededException(20);
        doThrow(limited).when(model).decide(any());
        AgentRun claimed = claim();
        assertThatThrownBy(() -> loop.advance(buyer, claimed)).isSameAs(limited);
        AgentRun paused = store.find(claimed.key());
        assertThat(paused.status()).isEqualTo(AgentRunStatus.PAUSED);
        assertThat(paused.failureCode()).isEqualTo(AgentFailureCode.MODEL_UNAVAILABLE);
        assertThat(paused.attempts()).isEqualTo(1);
        verify(model, times(1)).decide(any());
        verifyNoInteractions(orderTool, logistics);
    }

    @Test void aTransientModelFailureRetriesOnceWithAnotherDurableReservationAndTheSameOperationId() {
        var registry = new SimpleMeterRegistry();
        loop = new AgentLoopExecutor(store, model, orderTool, logistics, authorization, defaults,
                new AgentRetryProperties(1, java.time.Duration.ZERO), registry);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 1) throw new ModelUnavailableException(ModelFailureKind.TIMEOUT);
            return response(call.getArgument(0));
        }).when(model).decide(any());
        AgentRun claimed = claim();
        AgentRun done = loop.advance(buyer, claimed);
        var steps = store.steps(done.key());
        assertThat(done.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(done.attempts()).isEqualTo(6);
        assertThat(done.deadlineAt()).isEqualTo(claimed.deadlineAt());
        assertThat(done.budget().chargedTokens()).isEqualTo(660 + done.budget().reservationTokens());
        assertThat(done.budget().reportedTokens()).isEqualTo(660);
        assertThat(steps.getFirst().failureKind()).isEqualTo(AgentStepFailure.MODEL_TIMEOUT);
        assertThat(steps.getFirst().retryScheduled()).isTrue();
        assertThat(steps.getFirst().status()).isEqualTo(AgentStepStatus.FAILED);
        assertThat(steps.getFirst().meteringStatus()).isEqualTo(AgentMeteringStatus.RESERVED);
        assertThat(steps.get(1).operationId()).isEqualTo(steps.getFirst().operationId());
        verify(model, times(4)).decide(any());
        verify(orderTool, times(1)).lookup(any(), anyLong(), anyString());
        verify(logistics, times(1)).lookup(any());
        assertThat(registry.get("supportflow.agent.retry.decisions")
                .tags("phase", "PLAN", "failure", "MODEL_TIMEOUT", "outcome", "SCHEDULED").counter().count()).isEqualTo(1);
        assertThat(registry.get("supportflow.agent.step.duration")
                .tags("phase", "PLAN", "outcome", "FAILURE").timer().count()).isEqualTo(1);
    }

    @Test void repeatedTransientFailurePausesAndRestartCannotObtainAnotherAutomaticRetry() {
        doThrow(new ModelUnavailableException(ModelFailureKind.PROVIDER_TEMPORARY)).when(model).decide(any());
        AgentRun run = claim();
        assertThatThrownBy(() -> loop.advance(buyer, run)).isInstanceOf(ModelUnavailableException.class);
        AgentRun paused = store.find(run.key());
        assertThat(paused.status()).isEqualTo(AgentRunStatus.PAUSED);
        assertThat(paused.attempts()).isEqualTo(2);
        assertThat(store.steps(run.key())).extracting(AgentStepView::retryScheduled).containsExactly(true, false);
        var restarted = executor(new AgentRuntimeProperties(32, 100));
        AgentRun resumed = store.claim(run.key(), true);
        assertThatThrownBy(() -> restarted.advance(buyer, resumed)).isInstanceOf(ModelUnavailableException.class);
        assertThat(store.find(run.key()).attempts()).isEqualTo(3);
        assertThat(store.find(run.key()).maxSteps()).isEqualTo(8);
        assertThat(store.find(run.key()).budget().chargedTokens()).isEqualTo(3 * paused.budget().reservationTokens());
        assertThat(store.steps(run.key())).filteredOn(AgentStepView::retryScheduled).hasSize(1);
        verify(model, times(3)).decide(any());
    }

    @Test void aTransientLogisticsFailureRetriesTheSavedReadWithoutRepeatingOrderLookupOrPlanning() {
        when(logistics.lookup(any())).thenThrow(new QueryTimeoutException("private-database-detail"))
                .thenReturn("SF10001：快递已揽收。");
        AgentRun done = start();
        assertThat(done.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(done.attempts()).isEqualTo(6);
        assertThat(done.budget().chargedTokens()).isEqualTo(660);
        var failed = store.steps(done.key()).stream().filter(AgentStepView::retryScheduled).findFirst().orElseThrow();
        assertThat(failed.phase()).isEqualTo(AgentPhase.LOGISTICS_LOOKUP);
        assertThat(failed.failureKind()).isEqualTo(AgentStepFailure.TOOL_TRANSIENT);
        verify(orderTool, times(1)).lookup(any(), anyLong(), anyString());
        verify(model, times(3)).decide(any());
        verify(logistics, times(2)).lookup(any());
    }

    @ParameterizedTest
    @EnumSource(value = ModelFailureKind.class, names = {"INVALID_RESPONSE", "PROVIDER_REJECTED", "RATE_LIMITED",
            "BUSY", "CIRCUIT_OPEN", "CANCELLED", "UNCLASSIFIED"})
    void nonRetryableModelFailuresNeverCauseASecondRequest(ModelFailureKind kind) {
        doThrow(new ModelUnavailableException(kind)).when(model).decide(any());
        AgentRun run = claim();
        assertThatThrownBy(() -> loop.advance(buyer, run)).isInstanceOf(ModelUnavailableException.class);
        assertThat(store.find(run.key()).attempts()).isEqualTo(1);
        assertThat(store.steps(run.key())).allMatch(step -> !step.retryScheduled() && step.failureKind() != null);
        verify(model, times(1)).decide(any());
        verifyNoInteractions(orderTool, logistics);
    }

    @Test void exhaustedStepAndTokenLimitsStopBeforeTheAutomaticRetryCanStart() {
        doThrow(new ModelUnavailableException(ModelFailureKind.TIMEOUT)).when(model).decide(any());
        for (var limits : List.of(new AgentRuntimeProperties(1, 90),
                new AgentRuntimeProperties(8, 90, 2500, new BigDecimal("0.05"), 2048, 64,
                        new BigDecimal("0.30"), new BigDecimal("1.20"), "deepseek-flash"),
                new AgentRuntimeProperties(8, 90, 60000, new BigDecimal("0.006"), 16384, 512,
                        new BigDecimal("0.30"), new BigDecimal("1.20"), "deepseek-flash"))) {
            loop = executor(limits);
            AgentRun run = claim();
            assertThatThrownBy(() -> loop.advance(buyer, run)).isInstanceOf(ModelUnavailableException.class);
            assertThat(store.find(run.key()).status()).isEqualTo(AgentRunStatus.LIMIT_REACHED);
            assertThat(store.find(run.key()).attempts()).isEqualTo(1);
            assertThat(store.steps(run.key())).allMatch(step -> !step.retryScheduled());
        }
        verify(model, times(3)).decide(any());
    }

    @Test void checkpointStorageFailureDoesNotReplayAnAlreadyReturnedModelResponse() {
        store = spy(store);
        doThrow(new QueryTimeoutException("private-checkpoint-error")).when(store)
                .checkpointLoop(any(), anyLong(), any(), any(), any(), any(), any(), any());
        loop = executor(defaults);
        AgentRun run = claim();
        assertThatThrownBy(() -> loop.advance(buyer, run)).isInstanceOf(ModelUnavailableException.class);
        assertThat(store.find(run.key()).attempts()).isEqualTo(1);
        assertThat(store.steps(run.key()).getFirst().retryScheduled()).isFalse();
        verify(model, times(1)).decide(any());
        verifyNoInteractions(orderTool, logistics);
    }

    @Test void theOriginalDeadlinePreventsASecondProviderRequestAfterTimeout() {
        var currentRun = new java.util.concurrent.atomic.AtomicReference<UUID>();
        doAnswer(call -> {
            jdbc.sql("UPDATE support_agent_run SET deadline_at=TIMESTAMPADD(MICROSECOND,-1000,UTC_TIMESTAMP(3)) WHERE public_id=:run")
                    .param("run", currentRun.get().toString()).update();
            throw new ModelUnavailableException(ModelFailureKind.TIMEOUT);
        }).when(model).decide(any());
        AgentRun run = claim();
        currentRun.set(run.id());
        assertThatThrownBy(() -> loop.advance(buyer, run)).isInstanceOf(ModelUnavailableException.class);
        assertThat(store.find(run.key()).status()).isEqualTo(AgentRunStatus.EXPIRED);
        assertThat(store.find(run.key()).failureCode()).isEqualTo(AgentFailureCode.DEADLINE_EXCEEDED);
        verify(model, times(1)).decide(any());
    }

    @Test void aDisabledRetryPolicyStaysDisabledWhenTheRunIsResumedByANewConfiguration() {
        loop = new AgentLoopExecutor(store, model, orderTool, logistics, authorization, defaults,
                new AgentRetryProperties(0, java.time.Duration.ZERO), new SimpleMeterRegistry());
        doThrow(new ModelUnavailableException(ModelFailureKind.TIMEOUT)).when(model).decide(any());
        AgentRun run = claim();
        assertThatThrownBy(() -> loop.advance(buyer, run)).isInstanceOf(ModelUnavailableException.class);
        AgentRun resumed = store.claim(run.key(), true);
        assertThatThrownBy(() -> executor(defaults).advance(buyer, resumed)).isInstanceOf(ModelUnavailableException.class);
        assertThat(store.find(run.key()).retryPolicy().maxRetries()).isZero();
        assertThat(store.find(run.key()).attempts()).isEqualTo(2);
        verify(model, times(2)).decide(any());
    }


    @Test void theRealProviderBoundaryAndCircuitStopARetryBeforeAnotherHttpRequest() {
        var client = mock(java.net.http.HttpClient.class);
        @SuppressWarnings("unchecked") java.net.http.HttpResponse<byte[]> unavailable = mock(java.net.http.HttpResponse.class);
        when(unavailable.statusCode()).thenReturn(503);
        when(unavailable.body()).thenReturn("private-provider-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(client.sendAsync(any(java.net.http.HttpRequest.class),
                org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(unavailable));
        var properties = new dev.supportflow.chat.infrastructure.deepseek.DeepSeekProperties(true, "fake-integration-key", "deepseek-flash",
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), 2,
                new dev.supportflow.chat.infrastructure.deepseek.DeepSeekCircuitProperties(1, java.time.Duration.ofSeconds(30)));
        var registry = new SimpleMeterRegistry();
        var calls = new dev.supportflow.chat.infrastructure.deepseek.DeepSeekCallExecutor(client, properties, registry);
        model = new dev.supportflow.chat.infrastructure.deepseek.DeepSeekAgentDecisionPlanner(calls, new ObjectMapper(), properties);
        loop = new AgentLoopExecutor(store, model, orderTool, logistics, authorization, defaults,
                new AgentRetryProperties(1, java.time.Duration.ZERO), registry);
        AgentRun run = claim();
        assertThatThrownBy(() -> loop.advance(buyer, run)).isInstanceOf(ModelUnavailableException.class)
                .hasNoCause().hasMessageNotContaining("private-provider-body");
        assertThat(store.find(run.key()).attempts()).isEqualTo(2);
        assertThat(store.find(run.key()).status()).isEqualTo(AgentRunStatus.PAUSED);
        assertThat(store.steps(run.key())).extracting(AgentStepView::failureKind)
                .containsExactly(AgentStepFailure.MODEL_TEMPORARY, AgentStepFailure.MODEL_CIRCUIT_OPEN);
        verify(client, times(1)).sendAsync(any(java.net.http.HttpRequest.class),
                org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<byte[]>>any());
        verifyNoInteractions(orderTool, logistics);
    }

    @Test void aWorkerWhoseGenerationWasReplacedCannotScheduleARetry() {
        AgentRun claimed = claim();
        store.beginDecision(claimed.key(), claimed.generation());
        jdbc.sql("UPDATE support_agent_run SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE public_id=:run")
                .param("run", claimed.id().toString()).update();
        AgentRun recovered = store.claim(claimed.key(), true);
        assertThatThrownBy(() -> store.retryStep(claimed.key(), claimed.generation(), AgentPhase.PLAN, AgentStepFailure.MODEL_TIMEOUT))
                .isInstanceOf(ConversationException.class);
        assertThat(store.find(claimed.key()).generation()).isEqualTo(recovered.generation());
        assertThat(store.steps(claimed.key())).allMatch(step -> !step.retryScheduled());
        verify(model, never()).decide(any());
    }

    @Test void restartResumesAfterSavedToolFactsWithoutRepeatingTheOrderToolOrRefillingBudget() {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 2) throw new ModelUnavailableException();
            return response(call.getArgument(0));
        }).when(model).decide(any());
        AgentRun claimed = claim();
        assertThatThrownBy(() -> loop.advance(buyer, claimed)).isInstanceOf(ModelUnavailableException.class);
        AgentRun paused = store.find(claimed.key());
        assertThat(paused.phase()).isEqualTo(AgentPhase.PLAN);
        assertThat(paused.loopState().order()).isNotNull();
        assertThat(paused.budget().chargedTokens()).isEqualTo(220 + paused.budget().reservationTokens());
        AgentRun recovered = executor(new AgentRuntimeProperties(32, 100))
                .advance(buyer, store.claim(claimed.key(), true));
        assertThat(recovered.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(recovered.maxSteps()).isEqualTo(8);
        assertThat(recovered.deadlineAt()).isEqualTo(paused.deadlineAt());
        assertThat(recovered.budget().chargedTokens()).isEqualTo(660 + paused.budget().reservationTokens());
        assertThat(recovered.budget().reportedTokens()).isEqualTo(660);
        verify(orderTool, times(1)).lookup(any(), anyLong(), anyString());
        verify(model, times(4)).decide(any());
    }

    @Test void uncertainProviderCallsKeepReservationsAndEventuallyStopBeforeAnotherModelRequest() {
        doThrow(new ModelUnavailableException()).when(model).decide(any());
        AgentRun run = claim();
        for (int attempt = 0; attempt < 3; attempt++) {
            AgentRun current = run;
            assertThatThrownBy(() -> loop.advance(buyer, current)).isInstanceOf(ModelUnavailableException.class);
            run = store.claim(run.key(), true);
        }
        AgentRun last = run;
        assertThatThrownBy(() -> loop.advance(buyer, last)).isInstanceOf(ConversationException.class);
        AgentRun stopped = store.find(run.key());
        assertThat(stopped.status()).isEqualTo(AgentRunStatus.LIMIT_REACHED);
        assertThat(stopped.failureCode()).isEqualTo(AgentFailureCode.MODEL_BUDGET_EXCEEDED);
        assertThat(stopped.budget().chargedTokens()).isEqualTo(3 * stopped.budget().reservationTokens());
        assertThat(stopped.budget().reportedTokens()).isZero();
        verify(model, times(3)).decide(any());
        verifyNoInteractions(orderTool, logistics);
    }

    @Test void aCostCeilingRejectsBeforeAnyProviderCallOrStepIsStarted() {
        var low = new AgentRuntimeProperties(8, 90, 60000, new BigDecimal("0.000001"),
                16384, 512, new BigDecimal("0.30"), new BigDecimal("1.20"), "deepseek-flash");
        loop = executor(low);
        AgentRun run = claim();
        assertThatThrownBy(() -> loop.advance(buyer, run)).isInstanceOf(ConversationException.class);
        assertThat(store.find(run.key()).failureCode()).isEqualTo(AgentFailureCode.MODEL_BUDGET_EXCEEDED);
        assertThat(store.steps(run.key())).isEmpty();
        verify(model, never()).decide(any());
    }

    @Test void repeatedCompletedToolIsRejectedWithoutExecutingItAgain() {
        doAnswer(call -> new AgentDecisionResult(decision(AgentAction.ORDER_LOOKUP, "SF10001"), usage()))
                .when(model).decide(any());
        AgentRun run = claim();
        assertThatThrownBy(() -> loop.advance(buyer, run)).isInstanceOf(ModelUnavailableException.class);
        assertThat(store.find(run.key()).status()).isEqualTo(AgentRunStatus.FAILED);
        verify(orderTool, times(1)).lookup(any(), anyLong(), anyString());
        verifyNoInteractions(logistics);
    }

    @Test void fabricatedOrderAndPrematureFinishCannotExecuteToolsOrInventAnAnswer() {
        for (AgentDecision decision : List.of(decision(AgentAction.ORDER_LOOKUP, "SF99999"), decision(AgentAction.ANSWER, "SF10001"))) {
            doReturn(new AgentDecisionResult(decision, usage())).when(model).decide(any());
            AgentRun run = claim();
            assertThatThrownBy(() -> loop.advance(buyer, run)).isInstanceOf(ModelUnavailableException.class);
            assertThat(store.find(run.key()).status()).isEqualTo(AgentRunStatus.FAILED);
        }
        verifyNoInteractions(orderTool, logistics);
    }

    @Test void maxStepsStopsTheLoopEvenIfModelBudgetRemains() {
        loop = executor(new AgentRuntimeProperties(4, 90));
        AgentRun run = claim();
        assertThatThrownBy(() -> loop.advance(buyer, run)).isInstanceOf(ConversationException.class);
        assertThat(store.find(run.key()).failureCode()).isEqualTo(AgentFailureCode.STEP_LIMIT_REACHED);
        assertThat(store.steps(run.key())).hasSize(4);
        verify(model, times(2)).decide(any());
    }

    @Test void staleWorkerCannotSettleAReservationOrOverwriteRecoveredFacts() {
        AgentRun old = claim();
        AgentRun started = store.beginDecision(old.key(), old.generation());
        jdbc.sql("UPDATE support_agent_run SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE public_id=:id")
                .param("id", old.id().toString()).update();
        AgentRun next = store.claim(old.key(), true);
        AgentLoopState state = new AgentLoopState(List.of(), decision(AgentAction.ORDER_LOOKUP, "SF10001"));
        assertThatThrownBy(() -> store.checkpointLoop(old.key(), old.generation(), AgentPhase.PLAN,
                AgentPhase.ORDER_LOOKUP, "SF10001", null, state, usage())).isInstanceOf(ConversationException.class);
        assertThat(store.find(old.key()).budget()).isEqualTo(started.budget());
        AgentRun done = loop.advance(buyer, next);
        assertThat(done.budget().chargedTokens()).isEqualTo(started.budget().reservationTokens() + 660);
        assertThat(store.steps(old.key()).getFirst().status()).isEqualTo(AgentStepStatus.ABANDONED);
    }

    @Test void completedRunCannotRefundTheSameReservationTwice() {
        AgentRun done = start();
        AgentBudget original = done.budget();
        assertThatThrownBy(() -> store.checkpointLoop(done.key(), done.generation(), AgentPhase.PLAN,
                AgentPhase.COMPLETE, done.orderNo(), done.result(), done.loopState(), usage()))
                .isInstanceOf(ConversationException.class);
        assertThat(loop.advance(buyer, store.claim(done.key(), true)).budget()).isEqualTo(original);
        verify(model, times(3)).decide(any());
    }

    @Test void currentOwnershipIsCheckedAgainBeforeExecutingAPersistedPendingTool() {
        AgentRun run = claim();
        run = store.beginDecision(run.key(), run.generation());
        run = store.checkpointLoop(run.key(), run.generation(), AgentPhase.PLAN, AgentPhase.ORDER_LOOKUP, "SF10001", null,
                new AgentLoopState(List.of(), decision(AgentAction.ORDER_LOOKUP, "SF10001")), usage());
        doThrow(new OrderNotFoundException()).when(authorization).getOwnedOrder(any(), anyLong(), anyString());
        AgentRun pending = run;
        assertThatThrownBy(() -> loop.advance(buyer, pending)).isInstanceOf(OrderNotFoundException.class);
        assertThat(store.find(run.key()).status()).isEqualTo(AgentRunStatus.FAILED);
        verifyNoInteractions(orderTool, logistics);
    }

    @Test void providerUsageOutsideTheReservedCeilingCannotReleaseBudget() {
        doReturn(new AgentDecisionResult(decision(AgentAction.ORDER_LOOKUP, "SF10001"), new AgentTokenUsage(50000, 1)))
                .when(model).decide(any());
        AgentRun run = claim();
        assertThatThrownBy(() -> loop.advance(buyer, run)).isInstanceOf(ModelUnavailableException.class);
        AgentRun paused = store.find(run.key());
        assertThat(paused.budget().chargedTokens()).isEqualTo(paused.budget().reservationTokens());
        assertThat(paused.budget().reportedTokens()).isZero();
        verifyNoInteractions(orderTool, logistics);
    }

    @Test void revokedOwnershipStopsBeforeSavedObservationsAreSentToTheModelAgain() {
        AtomicInteger ownershipReads = new AtomicInteger();
        doAnswer(call -> {
            if (ownershipReads.incrementAndGet() >= 2) throw new OrderNotFoundException();
            return order;
        }).when(authorization).getOwnedOrder(any(), anyLong(), anyString());
        AgentRun claimed = claim();
        assertThatThrownBy(() -> loop.advance(buyer, claimed)).isInstanceOf(OrderNotFoundException.class);
        assertThat(store.find(claimed.key()).status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(store.find(claimed.key()).budget().chargedTokens()).isEqualTo(220);
        verify(model, times(1)).decide(any());
        verify(orderTool, times(1)).lookup(any(), anyLong(), anyString());
        verifyNoInteractions(logistics);
    }

    @Test void upgradingV12PreservesAndResumesAnExistingLegacyCheckpointWithoutModelReplay() throws Exception {
        String schema = "agent_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "`");
        }
        String url = MYSQL.getJdbcUrl().replace("/agent_loop_test?", "/" + schema + "?");
        Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword()).locations("classpath:db/migration")
                .target("12").cleanDisabled(true).load().migrate();
        var source = new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
        var manager = new DataSourceTransactionManager(source);
        var upgradeJdbc = JdbcClient.create(source);
        var json = new ObjectMapper();
        var request = new ConversationMessageRequest(UUID.randomUUID(), "查物流 SF10001", "SF10001");
        var conversation = legacyConversation(upgradeJdbc, request);
        var key = new AgentRunKey(conversation.id(), request.clientMessageId(), 200, 100);
        UUID runId = UUID.randomUUID();
        upgradeJdbc.sql("""
                INSERT INTO support_agent_run
                (public_id,conversation_id,client_message_id,buyer_id,tenant_id,input_hash,request_json,planning_message,
                 selected_order_no,phase,status,order_no,result_text,attempts,max_steps,deadline_at,generation)
                SELECT :run,id,:client,200,100,:hash,:request,'查物流','SF10001','LOGISTICS_LOOKUP','PAUSED',
                  'SF10001','旧版已验证订单',2,8,TIMESTAMPADD(SECOND,90,UTC_TIMESTAMP(3)),1
                FROM support_chat_conversation WHERE public_id=:conversation
                """).param("run", runId.toString()).param("client", request.clientMessageId().toString())
                .param("hash", "0".repeat(64)).param("request", json.writeValueAsString(request))
                .param("conversation", conversation.id().toString()).update();
        int index = 0;
        for (String phase : List.of("PLAN", "ORDER_LOOKUP")) {
            upgradeJdbc.sql("""
                    INSERT INTO support_agent_step(run_id,operation_id,attempt,phase,status,completed_at)
                    SELECT id,:operation,:attempt,:phase,'SUCCEEDED',UTC_TIMESTAMP(3)
                    FROM support_agent_run WHERE public_id=:run
                    """).param("operation", UUID.randomUUID().toString()).param("attempt", ++index)
                    .param("phase", phase).param("run", runId.toString()).update();
        }
        var migration = Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        assertThat(migration.migrationsExecuted).isEqualTo(10);
        var upgraded = new JdbcAgentRunStore(upgradeJdbc, json, manager);
        AgentRun old = upgraded.find(key);
        assertThat(old.workflowVersion()).isEqualTo(1);
        assertThat(old.budget()).isNull();
        assertThat(old.attempts()).isEqualTo(2);
        var oldPlanner = mock(dev.supportflow.assistant.planning.ToolPlanner.class);
        var runtime = new ReadOnlyAgentRuntime(upgraded, oldPlanner, orderTool, logistics, authorization, defaults,
                new AgentLoopExecutor(upgraded, model, orderTool, logistics, authorization, defaults));
        AgentRun completed = runtime.resume(buyer, key);
        assertThat(completed.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(completed.workflowVersion()).isEqualTo(1);
        assertThat(completed.attempts()).isEqualTo(3);
        assertThat(completed.deadlineAt()).isEqualTo(old.deadlineAt());
        verify(model, never()).decide(any());
        verifyNoInteractions(oldPlanner, orderTool);
        verify(logistics, times(1)).lookup(any());
    }

    @Test void aV13LoopUpgradeKeepsAutomaticRetryDisabledForTheExistingCheckpoint() throws Exception {
        String schema = "v13_retry_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "`");
        }
        String url = MYSQL.getJdbcUrl().replace("/agent_loop_test?", "/" + schema + "?");
        Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword()).locations("classpath:db/migration")
                .target("13").cleanDisabled(true).load().migrate();
        var source = new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
        var manager = new DataSourceTransactionManager(source);
        var upgradeJdbc = JdbcClient.create(source);
        var json = new ObjectMapper();
        var request = new ConversationMessageRequest(UUID.randomUUID(), "查物流 SF10001", "SF10001");
        var conversation = legacyConversation(upgradeJdbc, request);
        var key = new AgentRunKey(conversation.id(), request.clientMessageId(), 200, 100);
        UUID id = UUID.randomUUID();
        upgradeJdbc.sql("""
                INSERT INTO support_agent_run
                (public_id,conversation_id,client_message_id,buyer_id,tenant_id,input_hash,request_json,planning_message,
                 selected_order_no,phase,status,max_steps,deadline_at,workflow_version,loop_state_json,budget_json)
                SELECT :run,id,:client,200,100,:hash,:request,'查物流','SF10001','PLAN','PAUSED',8,
                  TIMESTAMPADD(SECOND,90,UTC_TIMESTAMP(3)),2,:state,:budget
                FROM support_chat_conversation WHERE public_id=:conversation
                """).param("run", id.toString()).param("client", request.clientMessageId().toString())
                .param("hash", "0".repeat(64)).param("request", json.writeValueAsString(request))
                .param("state", json.writeValueAsString(AgentLoopState.empty()))
                .param("budget", json.writeValueAsString(defaults.budget(PROFILE)))
                .param("conversation", conversation.id().toString()).update();
        var migrated = Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        assertThat(migrated.migrationsExecuted).isEqualTo(9);
        var upgraded = new JdbcAgentRunStore(upgradeJdbc, json, manager);
        assertThat(upgraded.find(key).retryPolicy().maxRetries()).isZero();
        assertThat(upgraded.find(key).workflowVersion()).isEqualTo(2);
        var afterUpgrade = new AgentLoopExecutor(upgraded, model, orderTool, logistics, authorization, defaults);
        doThrow(new ModelUnavailableException(ModelFailureKind.TIMEOUT)).when(model).decide(any());
        AgentRun resumed = upgraded.claim(key, true);
        assertThatThrownBy(() -> afterUpgrade.advance(buyer, resumed)).isInstanceOf(ModelUnavailableException.class);
        assertThat(upgraded.find(key).attempts()).isEqualTo(1);
        assertThat(upgraded.steps(key).getFirst().retryScheduled()).isFalse();
        verify(model, times(1)).decide(any());
    }

    private static dev.supportflow.chat.conversation.Conversation legacyConversation(JdbcClient jdbc, ConversationMessageRequest request) throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO support_chat_conversation(public_id,buyer_id,tenant_id) VALUES (:id,200,100)")
                .param("id", id.toString()).update();
        String payload = request.message().length() + ":" + request.message() + "|" + request.orderNo().length() + ":" + request.orderNo();
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.sql("""
                INSERT INTO support_chat_turn(conversation_id,client_message_id,request_hash,status,expires_at,processing_token)
                SELECT id,:client,:hash,'PROCESSING',TIMESTAMPADD(SECOND,120,UTC_TIMESTAMP(3)),:token
                FROM support_chat_conversation WHERE public_id=:id
                """).param("id", id.toString()).param("client", request.clientMessageId().toString())
                .param("hash", hash).param("token", UUID.randomUUID().toString()).update();
        return new dev.supportflow.chat.conversation.Conversation(id, 100, Instant.now(), Instant.now());
    }

    private AgentRun claim() {
        AgentRunInput input = input();
        loop.create(input);
        return store.claim(input.key(), false);
    }
    private AgentRun start() { return loop.advance(buyer, claim()); }
    private AgentLoopExecutor executor(AgentRuntimeProperties properties) {
        return new AgentLoopExecutor(store, model, orderTool, logistics, authorization, properties);
    }
    private AgentRunInput input() {
        var conversation = conversations.create(200, 100);
        var request = new ConversationMessageRequest(UUID.randomUUID(), "查物流 SF10001", "SF10001");
        conversations.reserve(200, 100, conversation.id(), request);
        return new AgentRunInput(new AgentRunKey(conversation.id(), request.clientMessageId(), 200, 100), request, request.message(), request.orderNo());
    }
    private static AgentDecisionResult response(AgentDecisionInput input) {
        return new AgentDecisionResult(new RuleBasedAgentDecisionPlanner().decide(input).decision(), usage());
    }
    private static AgentDecision decision(AgentAction action, String number) { return new AgentDecision(action, number, "call_" + UUID.randomUUID()); }
    private static AgentTokenUsage usage() { return new AgentTokenUsage(200, 20); }
    private static SupportOrder order(OrderStatus status) {
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        return new SupportOrder(123, 100, 999, 200, "SF10001", status, new BigDecimal("100.00"), "CNY", now, now);
    }
}
