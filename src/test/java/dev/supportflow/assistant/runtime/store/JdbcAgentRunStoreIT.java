package dev.supportflow.assistant.runtime.store;

import dev.supportflow.chat.conversation.ConversationException;
import dev.supportflow.chat.conversation.ConversationMessageRequest;
import dev.supportflow.chat.conversation.JdbcConversationStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real MySQL persistence and fencing. No application/business database or model provider is used. */
@Testcontainers
class JdbcAgentRunStoreIT {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName("supportflow_agent_store_test")
            .withUsername("root").withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withUrlParam("sslMode", "DISABLED")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    private DriverManagerDataSource source;
    private DataSourceTransactionManager transactions;
    private ObjectMapper json;
    private JdbcClient jdbc;
    private JdbcConversationStore conversations;
    private JdbcAgentRunStore store;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false)
                .createSchemas(false).load().migrate();
    }

    @BeforeEach
    void setUp() {
        source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        transactions = new DataSourceTransactionManager(source);
        json = new ObjectMapper();
        jdbc = JdbcClient.create(source);
        conversations = new JdbcConversationStore(jdbc, json, transactions);
        store = new JdbcAgentRunStore(jdbc, json, transactions);
    }

    @Test
    void createsExactlyOneRunForAnOwnedOriginalTurnAndRejectsChangedInput() {
        AgentRunInput input = input();
        AgentRun created = store.create(input, 8, 90);
        AgentRun same = store.create(input, 2, 10);
        assertThat(same.id()).isEqualTo(created.id());
        assertThat(same.maxSteps()).isEqualTo(8);
        assertThat(same.deadlineAt()).isEqualTo(created.deadlineAt());
        assertThat(same.request()).isEqualTo(input.request());
        assertThat(same.phase()).isEqualTo(AgentPhase.PLAN);
        assertThat(same.status()).isEqualTo(AgentRunStatus.PAUSED);

        AgentRunInput changed = new AgentRunInput(input.key(), input.request(), "查物流 SF10002", "SF10002");
        assertThatThrownBy(() -> store.create(changed, 8, 90)).isInstanceOf(ConversationException.class);
        ConversationMessageRequest different = new ConversationMessageRequest(input.key().clientMessageId(), "其他内容", null);
        assertThatThrownBy(() -> store.create(new AgentRunInput(input.key(), different, "查物流", null), 8, 90))
                .isInstanceOf(ConversationException.class);
        assertThat(store.find(input.key()).attempts()).isZero();
    }

    @Test
    void onlyTheAuthenticatedConversationOwnerAndShopCanReadOrResumeTheRun() {
        AgentRunInput input = input();
        store.create(input, 8, 90);
        List<AgentRunKey> foreign = List.of(
                new AgentRunKey(input.key().conversationId(), input.key().clientMessageId(), 201, 100),
                new AgentRunKey(input.key().conversationId(), input.key().clientMessageId(), 200, 101),
                new AgentRunKey(UUID.randomUUID(), input.key().clientMessageId(), 200, 100),
                new AgentRunKey(input.key().conversationId(), UUID.randomUUID(), 200, 100));
        for (AgentRunKey key : foreign) {
            assertThatThrownBy(() -> store.find(key)).isInstanceOf(ConversationException.class);
            assertThatThrownBy(() -> store.claim(key, true)).isInstanceOf(ConversationException.class);
            assertThatThrownBy(() -> store.steps(key)).isInstanceOf(ConversationException.class);
        }
    }

    @Test
    void resumesAtThePersistedPhaseWithTheOriginalDeadlineAndWithoutRepeatingCompletedSteps() {
        AgentRunInput input = input();
        AgentRun created = store.create(input, 8, 90);
        AgentRun claimed = store.claim(input.key(), false);
        store.beginStep(input.key(), claimed.generation(), AgentPhase.PLAN);
        store.checkpoint(input.key(), claimed.generation(), AgentPhase.PLAN, AgentPhase.ORDER_LOOKUP, "SF10001", null);
        store.pause(input.key(), claimed.generation(), AgentFailureCode.EXECUTION_INTERRUPTED);

        JdbcAgentRunStore restarted = new JdbcAgentRunStore(JdbcClient.create(source), json, transactions);
        AgentRun resumed = restarted.claim(input.key(), true);
        assertThat(resumed.phase()).isEqualTo(AgentPhase.ORDER_LOOKUP);
        assertThat(resumed.attempts()).isEqualTo(1);
        assertThat(resumed.generation()).isGreaterThan(claimed.generation());
        assertThat(resumed.deadlineAt()).isEqualTo(created.deadlineAt());
        assertThat(resumed.maxSteps()).isEqualTo(created.maxSteps());
        restarted.beginStep(input.key(), resumed.generation(), AgentPhase.ORDER_LOOKUP);
        restarted.checkpoint(input.key(), resumed.generation(), AgentPhase.ORDER_LOOKUP,
                AgentPhase.LOGISTICS_LOOKUP, "SF10001", "{\"orderNo\":\"SF10001\"}");
        assertThat(restarted.steps(input.key())).extracting(AgentStepView::phase)
                .containsExactly(AgentPhase.PLAN, AgentPhase.ORDER_LOOKUP);
    }

    @Test
    void abandonedReadAttemptsReuseTheStableOperationIdAndConsumeAnAdditionalAttempt() {
        AgentRunInput input = input();
        store.create(input, 8, 90);
        AgentRun claimed = store.claim(input.key(), false);
        store.beginStep(input.key(), claimed.generation(), AgentPhase.PLAN);
        jdbc.sql("UPDATE support_agent_run SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE public_id=:id")
                .param("id", claimed.id().toString()).update();

        AgentRun resumed = store.claim(input.key(), true);
        store.beginStep(input.key(), resumed.generation(), AgentPhase.PLAN);
        List<AgentStepView> steps = store.steps(input.key());
        assertThat(steps).hasSize(2);
        assertThat(steps.getFirst().status()).isEqualTo(AgentStepStatus.ABANDONED);
        assertThat(steps.getLast().status()).isEqualTo(AgentStepStatus.STARTED);
        assertThat(steps.getLast().operationId()).isEqualTo(steps.getFirst().operationId());
        assertThat(store.find(input.key()).attempts()).isEqualTo(2);
    }

    @Test
    void staleGenerationCannotOverwriteAResumedStepOrPauseTheNewWorker() {
        AgentRunInput input = input();
        store.create(input, 8, 90);
        AgentRun old = store.claim(input.key(), false);
        store.beginStep(input.key(), old.generation(), AgentPhase.PLAN);
        store.pause(input.key(), old.generation(), AgentFailureCode.EXECUTION_INTERRUPTED);
        AgentRun current = store.claim(input.key(), true);
        store.beginStep(input.key(), current.generation(), AgentPhase.PLAN);

        assertThatThrownBy(() -> store.checkpoint(input.key(), old.generation(), AgentPhase.PLAN,
                AgentPhase.ORDER_LOOKUP, "SF10001", null)).isInstanceOf(ConversationException.class);
        store.pause(input.key(), old.generation(), AgentFailureCode.TOOL_UNAVAILABLE);
        assertThat(store.find(input.key()).status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(store.find(input.key()).generation()).isEqualTo(current.generation());
        store.checkpoint(input.key(), current.generation(), AgentPhase.PLAN, AgentPhase.ORDER_LOOKUP, "SF10001", null);
        assertThat(store.find(input.key()).phase()).isEqualTo(AgentPhase.ORDER_LOOKUP);
    }

    @Test
    void concurrentClaimsAllowOnlyOneActiveWorker() throws Exception {
        AgentRunInput input = input();
        store.create(input, 8, 90);
        CountDownLatch gate = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> claimAfter(gate, input.key()));
            var second = workers.submit(() -> claimAfter(gate, input.key()));
            gate.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(store.find(input.key()).generation()).isEqualTo(1);
        }
    }

    @Test
    void theStepLimitIsPersistedBeforeTheConflictAndCannotBeResetByResume() {
        AgentRunInput input = input();
        AgentRun created = store.create(input, 1, 90);
        AgentRun claimed = store.claim(input.key(), false);
        store.beginStep(input.key(), claimed.generation(), AgentPhase.PLAN);
        store.checkpoint(input.key(), claimed.generation(), AgentPhase.PLAN, AgentPhase.ORDER_LOOKUP, "SF10001", null);
        assertThatThrownBy(() -> store.beginStep(input.key(), claimed.generation(), AgentPhase.ORDER_LOOKUP))
                .isInstanceOf(ConversationException.class);
        assertThat(store.find(input.key()).status()).isEqualTo(AgentRunStatus.LIMIT_REACHED);
        assertThat(store.find(input.key()).failureCode()).isEqualTo(AgentFailureCode.STEP_LIMIT_REACHED);
        assertThatThrownBy(() -> store.claim(input.key(), true)).isInstanceOf(ConversationException.class);
        assertThat(store.find(input.key()).attempts()).isEqualTo(1);
        assertThat(store.find(input.key()).deadlineAt()).isEqualTo(created.deadlineAt());
    }

    @Test
    void competingRequestsCannotTerminateTheActiveFinalAttempt() {
        AgentRunInput input = input();
        store.create(input, 1, 90);
        AgentRun run = store.claim(input.key(), false);
        store.beginStep(input.key(), run.generation(), AgentPhase.PLAN);
        assertThatThrownBy(() -> store.claim(input.key(), true)).isInstanceOf(ConversationException.class);
        assertThatThrownBy(() -> store.beginStep(input.key(), run.generation(), AgentPhase.PLAN))
                .isInstanceOf(ConversationException.class);
        assertThat(store.find(input.key()).status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(store.find(input.key()).attempts()).isEqualTo(1);
        store.checkpoint(input.key(), run.generation(), AgentPhase.PLAN, AgentPhase.COMPLETE,
                null, "请先选择需要查询的订单");
        assertThat(store.find(input.key()).status()).isEqualTo(AgentRunStatus.SUCCEEDED);
    }

    @Test
    void expiredRunsBecomeTerminalAndNeverReceiveANewDeadline() {
        AgentRunInput input = input();
        store.create(input, 8, 90);
        jdbc.sql("UPDATE support_agent_run SET deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE client_message_id=:id")
                .param("id", input.key().clientMessageId().toString()).update();
        var fixedDeadline = store.find(input.key()).deadlineAt();
        assertThatThrownBy(() -> store.claim(input.key(), true)).isInstanceOf(ConversationException.class);
        assertThat(store.find(input.key()).status()).isEqualTo(AgentRunStatus.EXPIRED);
        assertThat(store.find(input.key()).failureCode()).isEqualTo(AgentFailureCode.DEADLINE_EXCEEDED);
        assertThat(store.find(input.key()).deadlineAt()).isEqualTo(fixedDeadline);
    }

    @Test
    void completionIsDurableAndCachedWithoutCreatingAnotherStep() {
        AgentRunInput input = input();
        store.create(input, 3, 90);
        AgentRun run = store.claim(input.key(), false);
        store.beginStep(input.key(), run.generation(), AgentPhase.PLAN);
        store.checkpoint(input.key(), run.generation(), AgentPhase.PLAN, AgentPhase.ORDER_LOOKUP, "SF10001", null);
        store.beginStep(input.key(), run.generation(), AgentPhase.ORDER_LOOKUP);
        store.checkpoint(input.key(), run.generation(), AgentPhase.ORDER_LOOKUP,
                AgentPhase.LOGISTICS_LOOKUP, "SF10001", "{\"orderNo\":\"SF10001\"}");
        store.beginStep(input.key(), run.generation(), AgentPhase.LOGISTICS_LOOKUP);
        store.checkpoint(input.key(), run.generation(), AgentPhase.LOGISTICS_LOOKUP,
                AgentPhase.COMPLETE, "SF10001", "物流正在配送");
        AgentRun cached = store.claim(input.key(), true);
        assertThat(cached.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(cached.phase()).isEqualTo(AgentPhase.COMPLETE);
        assertThat(cached.result()).isEqualTo("物流正在配送");
        assertThat(cached.leaseUntil()).isNull();
        assertThat(store.steps(input.key())).hasSize(3)
                .allSatisfy(step -> assertThat(step.status()).isEqualTo(AgentStepStatus.SUCCEEDED));
    }

    @Test
    void checkpointRejectsOversizedResultsIllegalTransitionsAndMissingFailureCode() {
        AgentRunInput input = input();
        store.create(input, 8, 90);
        AgentRun run = store.claim(input.key(), false);
        store.beginStep(input.key(), run.generation(), AgentPhase.PLAN);
        assertThatThrownBy(() -> store.checkpoint(input.key(), run.generation(), AgentPhase.PLAN,
                AgentPhase.COMPLETE, null, "x".repeat(20001))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.checkpoint(input.key(), run.generation(), AgentPhase.PLAN,
                AgentPhase.LOGISTICS_LOOKUP, "SF10001", "result")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.pause(input.key(), run.generation(), null))
                .isInstanceOf(NullPointerException.class);
        assertThat(store.find(input.key()).status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(store.steps(input.key()).getFirst().status()).isEqualTo(AgentStepStatus.STARTED);
    }

    private boolean claimAfter(CountDownLatch gate, AgentRunKey key) throws InterruptedException {
        gate.await();
        try {
            store.claim(key, true);
            return true;
        } catch (ConversationException expected) {
            return false;
        }
    }

    private AgentRunInput input() {
        var conversation = conversations.create(200, 100);
        var request = new ConversationMessageRequest(UUID.randomUUID(), "查物流 SF10001", "SF10001");
        conversations.reserve(200, 100, conversation.id(), request);
        return new AgentRunInput(new AgentRunKey(conversation.id(), request.clientMessageId(), 200, 100),
                request, "查物流 SF10001", "SF10001");
    }
}
