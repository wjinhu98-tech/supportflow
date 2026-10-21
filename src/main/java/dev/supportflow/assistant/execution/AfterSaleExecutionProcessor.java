package dev.supportflow.assistant.execution;

import dev.supportflow.assistant.aftersale.AssistantBusinessException;
import dev.supportflow.common.api.error.ErrorCode;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import dev.supportflow.order.domain.model.SupportOrder;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Transaction boundaries are invoked through this bean by the worker, never via self-invocation. */
@Service
public class AfterSaleExecutionProcessor {
    private static final String WORKER = "system:after-sale-worker";
    private static final int LEASE_SECONDS = 30;
    private final AfterSaleExecutionRepository repository;
    private final RefundGateway refunds;

    public AfterSaleExecutionProcessor(AfterSaleExecutionRepository repository, RefundGateway refunds) {
        this.repository = repository;
        this.refunds = refunds;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String requestId, SupportOrder approvedOrder) {
        repository.enqueue(requestId, approvedOrder);
        repository.audit(requestId, WORKER, "EXECUTION_QUEUED", "已批准，等待学习版模拟退款处理");
    }

    @Transactional
    public Optional<ExecutionClaim> claimNext() {
        LocalDateTime now = repository.now();
        var candidate = repository.lockNext(now);
        if (candidate.isEmpty()) return Optional.empty();
        StoredExecution task = candidate.get();
        if (task.attemptCount() >= task.maxAttempts()) {
            repository.fail(task, ExecutionFailureCode.ATTEMPTS_EXHAUSTED, "处理超时且已达到最大尝试次数，请人工核查。", now);
            repository.audit(task.requestId(), WORKER, "EXECUTION_FAILED", "处理超时且尝试次数已用尽");
            return Optional.empty();
        }
        String token = UUID.randomUUID().toString();
        repository.claim(task.requestId(), token, now.plusSeconds(LEASE_SECONDS));
        repository.audit(task.requestId(), WORKER, "EXECUTION_STARTED",
                "开始第" + (task.attemptCount() + 1) + "次学习版模拟处理");
        return Optional.of(task.claim(token, task.attemptCount() + 1));
    }

    @Transactional
    public void complete(ExecutionClaim claim) {
        var found = repository.lock(claim.requestId());
        if (found.isEmpty() || !owns(found.get(), claim, repository.now())) return;
        StoredExecution task = found.get();
        if (!repository.isApproved(task) || !repository.eligibleOrder(task)) {
            throw new ExecutionRejectedException("审批或订单归属、金额、状态发生变化，请人工核查。");
        }
        // Rebuild arguments from locked database state instead of trusting the caller's copy.
        RefundReceipt receipt = refunds.refund(task.claim(task.claimToken(), task.attemptCount()));
        if (receipt == null || !task.currency().equals(receipt.currency())
                || task.amount().compareTo(receipt.amount()) != 0
                || receipt.reference() == null || !receipt.reference().startsWith("SIM-")
                || receipt.completedAt() == null) {
            throw new IllegalStateException("Simulated refund returned an invalid receipt");
        }
        repository.succeed(task, receipt);
        repository.audit(task.requestId(), WORKER, "EXECUTION_SUCCEEDED",
                "学习版模拟退款完成，凭证" + receipt.reference() + "；未连接真实支付渠道");
    }

    @Transactional
    public void recordFailure(ExecutionClaim claim, ExecutionFailureCode failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        var found = repository.lock(claim.requestId());
        if (found.isEmpty() || !owns(found.get(), claim, repository.now())) return;
        StoredExecution task = found.get();
        ExecutionFailureCode code = failure == ExecutionFailureCode.TRANSIENT_FAILURE
                && task.attemptCount() >= task.maxAttempts() ? ExecutionFailureCode.ATTEMPTS_EXHAUSTED : failure;
        String message = switch (code) {
            case TRANSIENT_FAILURE -> "暂时处理失败，将按退避计划重试，也可由管理员提前重试。";
            case BUSINESS_RULE_VIOLATION -> "审批或订单信息不再满足处理条件，请人工核查。";
            case ATTEMPTS_EXHAUSTED -> "已达到最大尝试次数，请人工核查。";
        };
        long delaySeconds = Math.min(60, 2L << Math.max(0, task.attemptCount() - 1));
        repository.fail(task, code, message, repository.now().plusSeconds(delaySeconds));
        repository.audit(task.requestId(), WORKER, "EXECUTION_FAILED", code.name() + "：" + message);
    }

    @Transactional
    public void retry(CustomerPrincipal principal, UUID requestId) {
        boolean permitted = principal.getAuthorities().stream().anyMatch(authority ->
                SecurityPermission.AFTER_SALE_REVIEW.getAuthority().equals(authority.getAuthority()));
        if (!permitted) throw new AccessDeniedException("Execution retry requires review permission");
        // Check requester before locking the task to keep the worker's task -> case lock order.
        if (repository.isRequester(requestId.toString(), principal.getIdentity().buyerId())) {
            throw new AccessDeniedException("Requester cannot retry their own case");
        }
        StoredExecution task = repository.lock(requestId.toString()).orElseThrow(() ->
                new AssistantBusinessException(ErrorCode.REVIEW_NOT_FOUND, HttpStatus.NOT_FOUND, "未找到该售后处理任务"));
        if (task.status() != ExecutionStatus.FAILED || task.attemptCount() >= task.maxAttempts()
                || !ExecutionFailureCode.TRANSIENT_FAILURE.name().equals(task.lastErrorCode())) {
            throw new AssistantBusinessException(ErrorCode.REVIEW_CONFLICT, HttpStatus.CONFLICT,
                    "此任务当前不能重试，请刷新查看处理状态。");
        }
        if (!repository.isApproved(task)) {
            throw new AssistantBusinessException(ErrorCode.REVIEW_CONFLICT, HttpStatus.CONFLICT, "只有已批准的申请可以重试");
        }
        repository.retry(task, repository.now());
        repository.audit(task.requestId(), principal.getUsername(), "EXECUTION_RETRY", "管理员请求提前重试，保留累计尝试次数");
    }

    private static boolean owns(StoredExecution task, ExecutionClaim claim, LocalDateTime now) {
        return task.status() == ExecutionStatus.PROCESSING && claim.claimToken().equals(task.claimToken())
                && task.leaseUntil() != null && task.leaseUntil().isAfter(now);
    }
}
