package dev.supportflow.assistant.aftersale;

import dev.supportflow.common.api.error.ErrorCode;
import dev.supportflow.assistant.execution.AfterSaleExecutionProcessor;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Java owns eligibility, idempotency and approval transitions; the model has no approve tool. */
@Service
public class AfterSaleService {
    private final AfterSaleRepository repository;
    private final OrderQueryService orders;
    private final AfterSaleExecutionProcessor executions;

    public AfterSaleService(AfterSaleRepository repository, OrderQueryService orders,
                            AfterSaleExecutionProcessor executions) {
        this.repository = repository;
        this.orders = orders;
        this.executions = executions;
    }

    @Transactional
    @PreAuthorize("hasAuthority('order:read')")
    public AfterSaleTicket submit(CustomerPrincipal principal, long shopId, String orderNo, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new AssistantBusinessException(ErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST,
                    "请填写1至500字的售后原因。");
        }
        SupportOrder order = orders.getOwnedOrder(principal.getIdentity(), shopId, orderNo);
        requireEligible(order);
        if (!repository.lockEligibleOrder(order)) throw conflict();
        // The learning module supports one case per owned order, not repeated refund execution.
        String key = fingerprint(order);
        String id = UUID.randomUUID().toString();
        // UPSERT holds an exclusive key lock; unlike duplicate-INSERT shared-lock upgrades,
        // three or more simultaneous attempts serialize without creating a lock-upgrade cycle.
        repository.insert(id, key, order, reason.strip());
        StoredAfterSale stored = repository.findByKey(key).orElseThrow();
        if (id.equals(stored.ticket().id())) {
            repository.audit(id, principal.getUsername(), "SUBMITTED", "等待客服或管理员审核");
        }
        return stored.ticket();
    }

    @PreAuthorize("hasAuthority('order:read')")
    public List<AfterSaleTicket> listOwned(CustomerIdentity identity, long shopId) {
        return repository.listOwned(shopId, identity.buyerId());
    }

    /** This is an explicitly global platform-review permission in the learning module. */
    @PreAuthorize("hasAuthority('after-sale:review')")
    public List<AfterSaleTicket> pending() { return repository.pending(); }

    @PreAuthorize("hasAuthority('after-sale:review')")
    public List<AfterSaleTicket> reviewedHistory() { return repository.reviewedHistory(); }

    @Transactional
    @PreAuthorize("hasAuthority('after-sale:review')")
    public AfterSaleTicket retryExecution(CustomerPrincipal reviewer, UUID id) {
        executions.retry(reviewer, id);
        return repository.findById(id.toString()).orElseThrow().ticket();
    }

    @Transactional
    @PreAuthorize("hasAuthority('after-sale:review')")
    public AfterSaleTicket decide(CustomerPrincipal reviewer, UUID id, ReviewDecision decision, String note) {
        if (decision == null || note == null || note.isBlank() || note.length() > 500) {
            throw new AssistantBusinessException(ErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST,
                    "必须提供审批决定和1至500字的审核意见。");
        }
        StoredAfterSale stored = repository.findById(id.toString()).orElseThrow(() ->
                new AssistantBusinessException(ErrorCode.REVIEW_NOT_FOUND, HttpStatus.NOT_FOUND, "售后申请不存在"));
        if (reviewer.getIdentity().buyerId() == stored.buyerId()) {
            throw new AccessDeniedException("Requester cannot review their own case");
        }
        if (stored.ticket().status() != AfterSaleStatus.PENDING_REVIEW) throw conflict();
        SupportOrder approvedOrder = null;
        if (decision == ReviewDecision.APPROVE) {
            SupportOrder current = orders.getOwnedOrder(new CustomerIdentity(stored.buyerId()),
                    stored.ticket().shopId(), stored.ticket().orderNo());
            if (current.id() != stored.orderId()) throw conflict();
            requireEligible(current);
            if (!repository.lockEligibleOrder(current)) throw conflict();
            approvedOrder = current;
        }
        AfterSaleStatus status = decision == ReviewDecision.APPROVE
                ? AfterSaleStatus.APPROVED : AfterSaleStatus.REJECTED;
        if (!repository.decide(stored, status, reviewer.getUsername(), note.strip())) throw conflict();
        repository.audit(id.toString(), reviewer.getUsername(), status.name(), note.strip());
        if (approvedOrder != null) executions.enqueue(id.toString(), approvedOrder);
        return repository.findById(id.toString()).orElseThrow().ticket();
    }

    private static void requireEligible(SupportOrder order) {
        if (order.status() == OrderStatus.CREATED || order.status() == OrderStatus.CANCELLED) {
            throw new AssistantBusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, HttpStatus.CONFLICT,
                    "当前订单尚未付款或已取消，不能在本学习模块申请售后。");
        }
    }

    private static AssistantBusinessException conflict() {
        return new AssistantBusinessException(ErrorCode.REVIEW_CONFLICT, HttpStatus.CONFLICT,
                "申请已处理或订单状态已变化，请刷新后检查，不能重复审批。");
    }

    private static String fingerprint(SupportOrder order) {
        try {
            byte[] data = (order.buyerId() + ":" + order.tenantId() + ":" + order.id())
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
