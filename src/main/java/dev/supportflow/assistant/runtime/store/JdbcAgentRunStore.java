package dev.supportflow.assistant.runtime.store;

import dev.supportflow.assistant.planning.PlannedToolCall;
import dev.supportflow.assistant.planning.ToolName;
import dev.supportflow.assistant.runtime.model.AgentBudget;
import dev.supportflow.assistant.runtime.model.AgentLoopState;
import dev.supportflow.assistant.runtime.model.AgentTokenUsage;
import dev.supportflow.assistant.runtime.model.AgentRetryPolicy;
import dev.supportflow.chat.conversation.ConversationException;
import dev.supportflow.chat.conversation.ConversationMessageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Owned short transactions, fencing, and durable read-only step results. No remote work occurs here. */
@Repository
public class JdbcAgentRunStore implements AgentRunStore {
    public static final int LEASE_SECONDS = 20;
    private static final String RUN_SELECT = """
            SELECT run.*, conversation.public_id AS conversation_public_id
            FROM support_agent_run run
            JOIN support_chat_conversation conversation ON conversation.id=run.conversation_id
            WHERE conversation.public_id=:conversation AND conversation.buyer_id=:buyer
              AND conversation.tenant_id=:shop AND conversation.deleted_at IS NULL AND run.buyer_id=:buyer AND run.tenant_id=:shop
              AND run.client_message_id=:client
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public JdbcAgentRunStore(JdbcClient jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
    }

    @Override
    public AgentRun create(AgentRunInput input, int maxSteps, int timeoutSeconds) {
        return createInternal(input, maxSteps, timeoutSeconds, null, AgentRetryPolicy.disabled());
    }

    @Override
    public AgentRun createLoop(AgentRunInput input, int maxSteps, int timeoutSeconds, AgentBudget budget) {
        return createLoop(input, maxSteps, timeoutSeconds, budget, AgentRetryPolicy.disabled());
    }

    @Override
    public AgentRun createLoop(AgentRunInput input, int maxSteps, int timeoutSeconds, AgentBudget budget, AgentRetryPolicy retryPolicy) {
        Objects.requireNonNull(budget);
        if (budget.chargedTokens() != 0 || budget.reportedTokens() != 0 || budget.chargedCostUsd().signum() != 0)
            throw new IllegalArgumentException("A new run requires an unused budget");
        return createInternal(input, maxSteps, timeoutSeconds, budget, Objects.requireNonNull(retryPolicy));
    }

    private AgentRun createInternal(AgentRunInput input, int maxSteps, int timeoutSeconds, AgentBudget budget, AgentRetryPolicy retryPolicy) {
        Objects.requireNonNull(input);
        if (maxSteps < 1 || maxSteps > 32 || timeoutSeconds < 1 || timeoutSeconds > 100) {
            throw new IllegalArgumentException("Run budgets exceed the supported bounds");
        }
        return transaction.execute(status -> {
            AgentRunKey key = input.key();
            OwnedTurn turn = ownedTurn(key);
            if (!turn.requestHash().equals(requestHash(input.request())) || !turn.status().equals("PROCESSING")) {
                throw conflict("本次消息的处理状态已变化，请刷新记录");
            }
            String inputHash = inputHash(input);
            List<String> hashes = scoped(key, "SELECT input_hash FROM support_agent_run WHERE "
                    + "conversation_id=:storage AND client_message_id=:client")
                    .param("storage", turn.conversationId()).query(String.class).list();
            if (!hashes.isEmpty()) {
                if (!hashes.getFirst().equals(inputHash)) throw conflict("运行标识与原始请求不一致");
                AgentRun existing = owned(key, true);
                if (existing.workflowVersion() != (budget == null ? 1 : 2)) throw conflict("运行协议版本不一致");
                return existing;
            }
            jdbc.sql("""
                    INSERT INTO support_agent_run
                      (public_id, conversation_id, client_message_id, buyer_id, tenant_id, input_hash,
                       request_json, planning_message, selected_order_no, phase, status, max_steps, deadline_at,
                       workflow_version, loop_state_json, budget_json, auto_retry_limit, retry_delay_ms)
                    VALUES (:id, :storage, :client, :buyer, :shop, :hash,
                            :request, :message, :selected, 'PLAN', 'PAUSED', :maximum,
                            TIMESTAMPADD(SECOND, :timeout, UTC_TIMESTAMP(3)), :version, :loop, :budget, :retries, :delay)
                    """).param("id", UUID.randomUUID().toString()).param("storage", turn.conversationId())
                    .param("client", key.clientMessageId().toString()).param("buyer", key.buyerId())
                    .param("shop", key.shopId()).param("hash", inputHash)
                    .param("request", json.writeValueAsString(input.request()))
                    .param("message", input.message()).param("selected", input.selectedOrderNo(), Types.VARCHAR)
                    .param("maximum", maxSteps).param("timeout", timeoutSeconds)
                    .param("version", budget == null ? 1 : 2)
                    .param("loop", budget == null ? null : json.writeValueAsString(AgentLoopState.empty()), Types.LONGVARCHAR)
                    .param("budget", budget == null ? null : json.writeValueAsString(budget), Types.LONGVARCHAR)
                    .param("retries", retryPolicy.maxRetries()).param("delay", retryPolicy.delayMillis()).update();
            return owned(key, true);
        });
    }

    @Override
    public AgentRun claim(AgentRunKey key, boolean resume) {
        return decide(transaction.execute(status -> {
            AgentRun run = owned(key, true);
            if (run.status() == AgentRunStatus.SUCCEEDED) return Decision.ready(run);
            if (run.status() == AgentRunStatus.FAILED || run.status() == AgentRunStatus.EXPIRED
                    || run.status() == AgentRunStatus.LIMIT_REACHED) {
                return Decision.conflict("本次运行已结束，请发起新的查询");
            }
            Instant now = now();
            if (run.status() != AgentRunStatus.PAUSED
                    && !(resume && run.status() == AgentRunStatus.RUNNING && !run.leaseUntil().isAfter(now))) {
                return Decision.conflict("运行仍在处理中或已经结束，请刷新进度");
            }
            // A competing request must not terminate an active final attempt merely because its
            // attempt has already been reserved. Budget is checked only after the run is claimable.
            Decision budget = rejectBudget(run, now);
            if (budget != null) return budget;
            jdbc.sql("""
                    UPDATE support_agent_step SET status='ABANDONED', completed_at=UTC_TIMESTAMP(3)
                    WHERE run_id=(SELECT id FROM support_agent_run WHERE public_id=:run) AND status='STARTED'
                    """).param("run", run.id().toString()).update();
            jdbc.sql("""
                    UPDATE support_agent_run
                    SET status='RUNNING', generation=generation+1,
                        lease_until=LEAST(deadline_at, TIMESTAMPADD(SECOND, :lease, UTC_TIMESTAMP(3))),
                        failure_code=NULL, updated_at=UTC_TIMESTAMP(3)
                    WHERE public_id=:run
                    """).param("lease", LEASE_SECONDS).param("run", run.id().toString()).update();
            return Decision.ready(owned(key, true));
        }));
    }

    @Override
    public AgentRun beginStep(AgentRunKey key, long generation, AgentPhase expected) {
        return startStep(key, generation, expected, false);
    }

    @Override
    public AgentRun beginDecision(AgentRunKey key, long generation) {
        return startStep(key, generation, AgentPhase.PLAN, true);
    }

    @Override
    public boolean retryStep(AgentRunKey key, long generation, AgentPhase phase, AgentStepFailure failure) {
        Objects.requireNonNull(failure);
        return Boolean.TRUE.equals(transaction.execute(status -> {
            AgentRun run = owned(key, true);
            Instant now = now();
            if (run.workflowVersion() != 2 || run.phase() != phase || !ownsToken(run, generation))
                throw conflict("步骤执行权已变化，请查看运行进度");
            if (!run.deadlineAt().isAfter(now)) {
                terminal(run, AgentRunStatus.EXPIRED, AgentFailureCode.DEADLINE_EXCEEDED,
                        "本次运行已超过总执行期限");
                return false;
            }
            if (!ownsLease(run, generation, now)) throw conflict("执行租约已过期，请显式恢复");
            int changed = jdbc.sql("""
                    UPDATE support_agent_step SET status='FAILED', failure_kind=:kind, completed_at=UTC_TIMESTAMP(3)
                    WHERE run_id=(SELECT id FROM support_agent_run WHERE public_id=:run)
                      AND attempt=:attempt AND phase=:phase AND status='STARTED'
                    """).param("run", run.id().toString()).param("attempt", run.attempts())
                    .param("phase", phase.name()).param("kind", failure.name()).update();
            if (changed != 1) throw conflict("失败步骤已保存，不会重复安排重试");
            // The retry marker is durable and scoped to operation_id, which includes each observation round.
            if (!failure.retryable() || run.retryPolicy().maxRetries() == 0) return false;
            String operation = jdbc.sql("""
                    SELECT operation_id FROM support_agent_step
                    WHERE run_id=(SELECT id FROM support_agent_run WHERE public_id=:run) AND attempt=:attempt
                    """).param("run", run.id().toString()).param("attempt", run.attempts()).query(String.class).single();
            long used = jdbc.sql("""
                    SELECT COUNT(*) FROM support_agent_step
                    WHERE run_id=(SELECT id FROM support_agent_run WHERE public_id=:run)
                      AND operation_id=:operation AND retry_scheduled=TRUE
                    """).param("run", run.id().toString()).param("operation", operation).query(Long.class).single();
            if (used >= run.retryPolicy().maxRetries()) return false;
            if (rejectBudget(run, now) != null) return false;
            if (phase == AgentPhase.PLAN && !run.budget().canReserve()) {
                terminal(run, AgentRunStatus.LIMIT_REACHED, AgentFailureCode.MODEL_BUDGET_EXCEEDED,
                        "本次查询已达到模型预算上限");
                return false;
            }
            if (!run.deadlineAt().isAfter(now.plusMillis(run.retryPolicy().delayMillis() + 100L))) return false;
            jdbc.sql("""
                    UPDATE support_agent_step SET retry_scheduled=TRUE
                    WHERE run_id=(SELECT id FROM support_agent_run WHERE public_id=:run) AND attempt=:attempt
                    """).param("run", run.id().toString()).param("attempt", run.attempts()).update();
            jdbc.sql("""
                    UPDATE support_agent_run SET lease_until=LEAST(deadline_at,
                      TIMESTAMPADD(SECOND,:lease,UTC_TIMESTAMP(3))), updated_at=UTC_TIMESTAMP(3) WHERE public_id=:run
                    """).param("run", run.id().toString()).param("lease", LEASE_SECONDS).update();
            return true;
        }));
    }

    private AgentRun startStep(AgentRunKey key, long generation, AgentPhase expected, boolean decision) {
        Objects.requireNonNull(expected);
        if (expected == AgentPhase.COMPLETE) throw new IllegalArgumentException("Completion is not an executable step");
        return decide(transaction.execute(status -> {
            AgentRun run = owned(key, true);
            Instant now = now();
            if (!ownsToken(run, generation) || run.phase() != expected) {
                return Decision.conflict("运行已由其他执行者接管，请刷新进度");
            }
            if (decision && run.workflowVersion() != 2 || !decision && run.workflowVersion() == 2 && expected == AgentPhase.PLAN)
                return Decision.conflict("模型步骤必须使用持久化预算预留");
            if (!run.deadlineAt().isAfter(now)) {
                return terminal(run, AgentRunStatus.EXPIRED, AgentFailureCode.DEADLINE_EXCEEDED,
                        "本次运行已超过总执行期限，请发起新的查询");
            }
            if (!ownsLease(run, generation, now)) return Decision.conflict("执行租约已过期，请显式恢复本次查询");
            long active = jdbc.sql("""
                    SELECT COUNT(*) FROM support_agent_step
                    WHERE run_id=(SELECT id FROM support_agent_run WHERE public_id=:run) AND status='STARTED'
                    """).param("run", run.id().toString()).query(Long.class).single();
            if (active != 0) return Decision.conflict("当前步骤仍在执行，请稍后刷新进度");
            Decision budget = rejectBudget(run, now);
            if (budget != null) return budget;
            AgentBudget nextBudget = run.budget();
            if (decision) {
                if (!nextBudget.canReserve()) return terminal(run, AgentRunStatus.LIMIT_REACHED,
                        AgentFailureCode.MODEL_BUDGET_EXCEEDED, "本次查询已达到模型预算上限，请发起新的查询");
                nextBudget = nextBudget.reserve();
            }
            int nextAttempt = run.attempts() + 1;
            String decisionIndex = decision ? ":" + run.loopState().observations().size() : "";
            UUID operationId = UUID.nameUUIDFromBytes((run.id() + ":" + expected.name() + decisionIndex).getBytes(StandardCharsets.UTF_8));
            jdbc.sql("""
                    INSERT INTO support_agent_step
                      (run_id, operation_id, attempt, phase, status, metering_status, reserved_tokens, charged_cost_usd)
                    SELECT id, :operation, :attempt, :phase, 'STARTED', :metering, :tokens, :cost
                    FROM support_agent_run WHERE public_id=:run
                    """).param("run", run.id().toString()).param("operation", operationId.toString())
                    .param("attempt", nextAttempt).param("phase", expected.name())
                    .param("metering", decision && run.budget().profile().metered() ? "RESERVED" : "NONE")
                    .param("tokens", decision ? run.budget().reservationTokens() : 0)
                    .param("cost", decision ? run.budget().reservationCost() : java.math.BigDecimal.ZERO).update();
            jdbc.sql("""
                    UPDATE support_agent_run SET attempts=:attempt, budget_json=:budget, updated_at=UTC_TIMESTAMP(3)
                    WHERE public_id=:run
                    """).param("attempt", nextAttempt).param("run", run.id().toString())
                    .param("budget", nextBudget == null ? null : json.writeValueAsString(nextBudget), Types.LONGVARCHAR).update();
            return Decision.ready(owned(key, true));
        }));
    }

    @Override
    public AgentRun checkpoint(AgentRunKey key, long generation, AgentPhase completed, AgentPhase next,
                               String orderNo, String result) {
        Objects.requireNonNull(completed);
        Objects.requireNonNull(next);
        boolean permitted = completed == AgentPhase.PLAN && (next == AgentPhase.ORDER_LOOKUP || next == AgentPhase.COMPLETE)
                || completed == AgentPhase.ORDER_LOOKUP && next == AgentPhase.LOGISTICS_LOOKUP
                || completed == AgentPhase.LOGISTICS_LOOKUP && next == AgentPhase.COMPLETE;
        if (!permitted) throw new IllegalArgumentException("Unsupported read-only workflow transition");
        new PlannedToolCall(ToolName.LOGISTICS_LOOKUP, orderNo);
        if (next != AgentPhase.COMPLETE && orderNo == null) throw new IllegalArgumentException("A tool step needs an order number");
        if (next == AgentPhase.COMPLETE && (result == null || result.isBlank())) {
            throw new IllegalArgumentException("Completion requires a persisted public result");
        }
        boundedResult(result);
        return decide(transaction.execute(status -> {
            AgentRun run = owned(key, true);
            Instant now = now();
            if (run.workflowVersion() != 1) return Decision.conflict("循环运行必须保存完整观察与预算");
            if (!ownsToken(run, generation) || run.phase() != completed) {
                return Decision.conflict("运行已由其他执行者接管，旧结果不会覆盖当前进度");
            }
            if (!run.deadlineAt().isAfter(now)) {
                return terminal(run, AgentRunStatus.EXPIRED, AgentFailureCode.DEADLINE_EXCEEDED,
                        "本次运行已超过总执行期限，请发起新的查询");
            }
            if (!ownsLease(run, generation, now)) return Decision.conflict("执行租约已过期，结果不会覆盖当前进度");
            if (completed != AgentPhase.PLAN && !Objects.equals(run.orderNo(), orderNo)) {
                return Decision.conflict("工具结果与已保存的订单不一致");
            }
            int changed = jdbc.sql("""
                    UPDATE support_agent_step SET status='SUCCEEDED', result_text=:result,
                        completed_at=UTC_TIMESTAMP(3)
                    WHERE run_id=(SELECT id FROM support_agent_run WHERE public_id=:run)
                      AND attempt=:attempt AND phase=:phase AND status='STARTED'
                    """).param("result", result, Types.LONGVARCHAR).param("run", run.id().toString())
                    .param("attempt", run.attempts()).param("phase", completed.name()).update();
            if (changed != 1) return Decision.conflict("步骤状态已变化，请刷新进度");
            jdbc.sql("""
                    UPDATE support_agent_run SET phase=:next, status=:status, order_no=:orderNo,
                        result_text=:result, failure_code=NULL,
                        lease_until=CASE WHEN :complete THEN NULL
                            ELSE LEAST(deadline_at, TIMESTAMPADD(SECOND, :lease, UTC_TIMESTAMP(3))) END,
                        updated_at=UTC_TIMESTAMP(3)
                    WHERE public_id=:run
                    """).param("next", next.name()).param("status", next == AgentPhase.COMPLETE ? "SUCCEEDED" : "RUNNING")
                    .param("orderNo", orderNo, Types.VARCHAR).param("result", result, Types.LONGVARCHAR)
                    .param("complete", next == AgentPhase.COMPLETE).param("lease", LEASE_SECONDS)
                    .param("run", run.id().toString()).update();
            return Decision.ready(owned(key, true));
        }));
    }

    @Override
    public AgentRun checkpointLoop(AgentRunKey key, long generation, AgentPhase completed, AgentPhase next,
                                   String orderNo, String result, AgentLoopState state, AgentTokenUsage usage) {
        Objects.requireNonNull(state);
        boolean allowed = completed == AgentPhase.PLAN && (next == AgentPhase.ORDER_LOOKUP
                || next == AgentPhase.LOGISTICS_LOOKUP || next == AgentPhase.COMPLETE)
                || (completed == AgentPhase.ORDER_LOOKUP || completed == AgentPhase.LOGISTICS_LOOKUP) && next == AgentPhase.PLAN;
        if (!allowed || (completed == AgentPhase.PLAN) != (usage != null))
            throw new IllegalArgumentException("Invalid loop checkpoint");
        if (next == AgentPhase.COMPLETE && (result == null || result.isBlank()))
            throw new IllegalArgumentException("Completion requires business facts or a clarification");
        if ((next == AgentPhase.ORDER_LOOKUP || next == AgentPhase.LOGISTICS_LOOKUP) != (state.pending() != null))
            throw new IllegalArgumentException("Pending tool must match the checkpoint phase");
        if (state.pending() != null && (!Objects.equals(state.pending().orderNo(), orderNo)
                || !state.pending().action().name().equals(next.name())))
            throw new IllegalArgumentException("Pending tool arguments cannot disagree with the saved phase");
        new PlannedToolCall(ToolName.ORDER_LOOKUP, orderNo);
        boundedResult(result);
        String stateJson = json.writeValueAsString(state);
        if (stateJson.length() > 32768 || stateJson.getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new IllegalArgumentException("Loop state exceeds checkpoint bounds");
        return decide(transaction.execute(status -> {
            AgentRun run = owned(key, true);
            if (run.workflowVersion() != 2 || !ownsToken(run, generation) || run.phase() != completed)
                return Decision.conflict("循环运行已由其他执行者接管");
            Instant now = now();
            if (!run.deadlineAt().isAfter(now)) return terminal(run, AgentRunStatus.EXPIRED,
                    AgentFailureCode.DEADLINE_EXCEEDED, "本次运行已超过总执行期限");
            if (!ownsLease(run, generation, now)) return Decision.conflict("执行租约已过期，请显式恢复");
            if (run.orderNo() != null && !run.orderNo().equals(orderNo)) return Decision.conflict("循环不能切换订单");
            if (completed == AgentPhase.PLAN && !run.loopState().observations().equals(state.observations()))
                throw new IllegalArgumentException("Model decisions cannot replace observed facts");
            if (completed != AgentPhase.PLAN && (state.observations().size() != run.loopState().observations().size() + 1
                    || !state.observations().subList(0, run.loopState().observations().size()).equals(run.loopState().observations())))
                throw new IllegalArgumentException("Tool checkpoints must append one observation");
            if (completed != AgentPhase.PLAN && (run.loopState().pending() == null
                    || !state.observations().getLast().action().name().equals(completed.name())
                    || !state.observations().getLast().callId().equals(run.loopState().pending().callId())
                    || !state.observations().getLast().orderNo().equals(orderNo)))
                throw new IllegalArgumentException("An observation must belong to the saved tool invocation");
            AgentBudget budget = usage == null ? run.budget() : run.budget().settle(usage);
            boolean metered = completed == AgentPhase.PLAN && run.budget().profile().metered();
            String stepResult = state.pending() == null ? result : json.writeValueAsString(state.pending());
            int changed = jdbc.sql("""
                    UPDATE support_agent_step SET status='SUCCEEDED', result_text=:result,
                      metering_status=:metering, reported_tokens=:reported, charged_cost_usd=:cost,
                      completed_at=UTC_TIMESTAMP(3)
                    WHERE run_id=(SELECT id FROM support_agent_run WHERE public_id=:run)
                      AND attempt=:attempt AND phase=:phase AND status='STARTED' AND metering_status=:expectedMetering
                    """).param("run", run.id().toString()).param("attempt", run.attempts()).param("phase", completed.name())
                    .param("result", stepResult, Types.LONGVARCHAR).param("metering", metered ? "SETTLED" : "NONE")
                    .param("expectedMetering", metered ? "RESERVED" : "NONE")
                    .param("reported", usage == null ? 0 : usage.total())
                    .param("cost", metered ? budget.estimate(usage.promptTokens(), usage.completionTokens()) : java.math.BigDecimal.ZERO).update();
            if (changed != 1) return Decision.conflict("循环步骤已提交，不会再次结算预算");
            jdbc.sql("""
                    UPDATE support_agent_run SET phase=:next, status=:status, order_no=:number,
                      result_text=:result, loop_state_json=:state, budget_json=:budget, failure_code=NULL,
                      lease_until=CASE WHEN :complete THEN NULL ELSE LEAST(deadline_at,
                        TIMESTAMPADD(SECOND, :lease, UTC_TIMESTAMP(3))) END, updated_at=UTC_TIMESTAMP(3)
                    WHERE public_id=:run
                    """).param("run", run.id().toString()).param("next", next.name())
                    .param("status", next == AgentPhase.COMPLETE ? "SUCCEEDED" : "RUNNING")
                    .param("number", orderNo, Types.VARCHAR).param("result", result, Types.LONGVARCHAR)
                    .param("state", stateJson).param("budget", json.writeValueAsString(budget))
                    .param("complete", next == AgentPhase.COMPLETE).param("lease", LEASE_SECONDS).update();
            return Decision.ready(owned(key, true));
        }));
    }

    @Override
    public void pause(AgentRunKey key, long generation, AgentFailureCode failureCode) {
        AgentFailureCode code = Objects.requireNonNull(failureCode, "failureCode must not be null");
        transaction.executeWithoutResult(status -> {
            AgentRun run = owned(key, true);
            if (run.generation() != generation || run.status() != AgentRunStatus.RUNNING) return;
            AgentRunStatus next = switch (code) {
                case VALIDATION_REJECTED -> AgentRunStatus.FAILED;
                case DEADLINE_EXCEEDED -> AgentRunStatus.EXPIRED;
                case STEP_LIMIT_REACHED, MODEL_BUDGET_EXCEEDED -> AgentRunStatus.LIMIT_REACHED;
                default -> AgentRunStatus.PAUSED;
            };
            updateStatus(run, next, code);
            jdbc.sql("""
                    UPDATE support_agent_step SET status='FAILED', completed_at=UTC_TIMESTAMP(3)
                    WHERE run_id=(SELECT id FROM support_agent_run WHERE public_id=:run)
                      AND attempt=:attempt AND status='STARTED'
                    """).param("run", run.id().toString()).param("attempt", run.attempts()).update();
        });
    }

    @Override
    public AgentRun find(AgentRunKey key) { return owned(key, false); }

    @Override
    public List<AgentStepView> steps(AgentRunKey key) {
        AgentRun run = owned(key, false);
        return jdbc.sql("""
                SELECT operation_id, attempt, phase, status, started_at, completed_at,
                       metering_status, reserved_tokens, reported_tokens, charged_cost_usd, failure_kind, retry_scheduled
                FROM support_agent_step WHERE run_id=(SELECT id FROM support_agent_run WHERE public_id=:run)
                ORDER BY attempt LIMIT 32
                """).param("run", run.id().toString()).query((row, ignored) -> new AgentStepView(
                        UUID.fromString(row.getString("operation_id")), row.getInt("attempt"),
                        AgentPhase.valueOf(row.getString("phase")), AgentStepStatus.valueOf(row.getString("status")),
                        instant(row, "started_at"), instant(row, "completed_at"),
                        AgentMeteringStatus.valueOf(row.getString("metering_status")), row.getLong("reserved_tokens"),
                        row.getObject("reported_tokens", Long.class), row.getBigDecimal("charged_cost_usd"),
                        row.getString("failure_kind") == null ? null : AgentStepFailure.valueOf(row.getString("failure_kind")),
                        row.getBoolean("retry_scheduled"))).list();
    }

    private OwnedTurn ownedTurn(AgentRunKey key) {
        // Lock conversation before run, matching the conversation reservation lock order.
        long conversation = jdbc.sql("""
                SELECT id FROM support_chat_conversation
                WHERE public_id=:conversation AND buyer_id=:buyer AND tenant_id=:shop AND deleted_at IS NULL FOR UPDATE
                """).param("conversation", key.conversationId().toString()).param("buyer", key.buyerId())
                .param("shop", key.shopId()).query(Long.class).optional().orElseThrow(ConversationException::notFound);
        return jdbc.sql("""
                SELECT request_hash, status FROM support_chat_turn
                WHERE conversation_id=:conversation AND client_message_id=:client
                """).param("conversation", conversation).param("client", key.clientMessageId().toString())
                .query((row, ignored) -> new OwnedTurn(conversation, row.getString("request_hash"), row.getString("status")))
                .optional().orElseThrow(ConversationException::notFound);
    }

    private AgentRun owned(AgentRunKey key, boolean lock) {
        return scoped(key, RUN_SELECT + (lock ? " FOR UPDATE" : ""))
                .query(this::run).optional().orElseThrow(ConversationException::notFound);
    }

    private JdbcClient.StatementSpec scoped(AgentRunKey key, String sql) {
        return jdbc.sql(sql).param("conversation", key.conversationId().toString())
                .param("client", key.clientMessageId().toString()).param("buyer", key.buyerId()).param("shop", key.shopId());
    }

    private AgentRun run(ResultSet row, int ignored) throws SQLException {
        AgentRunKey key = new AgentRunKey(UUID.fromString(row.getString("conversation_public_id")),
                UUID.fromString(row.getString("client_message_id")), row.getLong("buyer_id"), row.getLong("tenant_id"));
        return new AgentRun(UUID.fromString(row.getString("public_id")), key,
                json.readValue(row.getString("request_json"), ConversationMessageRequest.class),
                row.getString("planning_message"), row.getString("selected_order_no"),
                AgentPhase.valueOf(row.getString("phase")), AgentRunStatus.valueOf(row.getString("status")),
                row.getString("order_no"), row.getString("result_text"), row.getInt("attempts"), row.getInt("max_steps"),
                instant(row, "deadline_at"), row.getLong("generation"), instant(row, "lease_until"),
                row.getString("failure_code") == null ? null : AgentFailureCode.valueOf(row.getString("failure_code")),
                row.getInt("workflow_version"), row.getString("loop_state_json") == null ? null
                        : json.readValue(row.getString("loop_state_json"), AgentLoopState.class),
                row.getString("budget_json") == null ? null : json.readValue(row.getString("budget_json"), AgentBudget.class),
                new AgentRetryPolicy(row.getInt("auto_retry_limit"), row.getInt("retry_delay_ms")));
    }

    private Decision rejectBudget(AgentRun run, Instant now) {
        if (!run.deadlineAt().isAfter(now)) return terminal(run, AgentRunStatus.EXPIRED,
                AgentFailureCode.DEADLINE_EXCEEDED, "本次运行已超过总执行期限，请发起新的查询");
        if (run.attempts() >= run.maxSteps()) return terminal(run, AgentRunStatus.LIMIT_REACHED,
                AgentFailureCode.STEP_LIMIT_REACHED, "本次运行已达到步骤尝试上限，请发起新的查询");
        return null;
    }

    private Decision terminal(AgentRun run, AgentRunStatus status, AgentFailureCode code, String message) {
        updateStatus(run, status, code);
        jdbc.sql("""
                UPDATE support_agent_step SET status='FAILED', completed_at=UTC_TIMESTAMP(3)
                WHERE run_id=(SELECT id FROM support_agent_run WHERE public_id=:run) AND status='STARTED'
                """).param("run", run.id().toString()).update();
        return Decision.conflict(message);
    }

    private void updateStatus(AgentRun run, AgentRunStatus status, AgentFailureCode code) {
        jdbc.sql("""
                UPDATE support_agent_run SET status=:status, failure_code=:code, lease_until=NULL,
                    updated_at=UTC_TIMESTAMP(3) WHERE public_id=:run
                """).param("status", status.name()).param("code", code.name()).param("run", run.id().toString()).update();
    }

    private boolean ownsLease(AgentRun run, long generation, Instant now) {
        return ownsToken(run, generation)
                && run.leaseUntil() != null && run.leaseUntil().isAfter(now);
    }

    private boolean ownsToken(AgentRun run, long generation) {
        return run.status() == AgentRunStatus.RUNNING && run.generation() == generation;
    }

    private Instant now() {
        return jdbc.sql("SELECT UTC_TIMESTAMP(3)").query(LocalDateTime.class).single().toInstant(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String field) throws SQLException {
        LocalDateTime value = row.getObject(field, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static void boundedResult(String result) {
        if (result != null && (result.length() > 20000 || result.getBytes(StandardCharsets.UTF_8).length > 65536)) {
            throw new IllegalArgumentException("Tool result exceeds checkpoint bounds");
        }
    }

    /** Compatible with the immutable conversation-turn request hash, including the legacy false-reset form. */
    private static String requestHash(ConversationMessageRequest request) {
        String order = request.orderNo() == null ? "-" : request.orderNo().length() + ":" + request.orderNo();
        return hash(request.message().length() + ":" + request.message() + "|" + order
                + (request.resetContext() ? "|reset-context:true" : ""));
    }

    private static String inputHash(AgentRunInput input) {
        String order = input.selectedOrderNo() == null ? "-" : input.selectedOrderNo().length() + ":" + input.selectedOrderNo();
        return hash(requestHash(input.request()) + "|" + input.message().length() + ":" + input.message() + "|" + order);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    private static ConversationException conflict(String message) { return ConversationException.conflict(message); }

    private static AgentRun decide(Decision decision) {
        if (decision.conflict() != null) throw conflict(decision.conflict());
        return decision.run();
    }

    private record OwnedTurn(long conversationId, String requestHash, String status) { }
    private record Decision(AgentRun run, String conflict) {
        static Decision ready(AgentRun run) { return new Decision(run, null); }
        static Decision conflict(String message) { return new Decision(null, message); }
    }
}
