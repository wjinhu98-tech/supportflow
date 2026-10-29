package dev.supportflow.assistant.aftersale;

import dev.supportflow.common.api.error.ErrorCode;
import dev.supportflow.assistant.execution.AfterSaleExecutionProcessor;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.merchant.MerchantAccessService;
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
    private final AfterSalePolicy policy;
    private final MerchantAccessService merchantAccess;

    public AfterSaleService(AfterSaleRepository repository, OrderQueryService orders,
                            AfterSaleExecutionProcessor executions, AfterSalePolicy policy,
                            MerchantAccessService merchantAccess) {
        this.repository = repository;
        this.orders = orders;
        this.executions = executions;
        this.policy = policy;
        this.merchantAccess = merchantAccess;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('order:read')")
    public AfterSaleEligibility eligibility(CustomerIdentity identity, long shopId, String orderNo) {
        SupportOrder order = orders.getOwnedOrder(identity, shopId, orderNo);
        return policy.evaluate(order, repository.now());
    }

    @Transactional
    @PreAuthorize("hasAuthority('order:read')")
    public AfterSaleTicket submit(CustomerPrincipal principal, long shopId, String orderNo, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new AssistantBusinessException(ErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST,
                    "请填写1至500字的售后原因。");
        }
        SupportOrder order = orders.getOwnedOrder(principal.getIdentity(), shopId, orderNo);
        // The learning module supports one case per owned order, not repeated refund execution.
        String key = fingerprint(order);
        var existing = repository.findExistingByKey(key);
        if (existing.isPresent()) return existing.get().ticket();
        // Evaluate the current locked receipt data, not an earlier REPEATABLE READ snapshot.
        SupportOrder current = repository.lockVisibleOwnedOrder(order).orElseThrow(
                dev.supportflow.order.application.exception.OrderNotFoundException::new);
        AfterSaleEligibility eligibility = policy.evaluate(current, repository.now());
        if (!eligibility.canSubmit()) {
            // A competing submission may have committed after our first RR snapshot.
            // Only do this missing-key locking read on the rejection path, which never inserts.
            var concurrentlyCreated = repository.findByKey(key);
            if (concurrentlyCreated.isPresent()) return concurrentlyCreated.get().ticket();
            throw new AssistantBusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, HttpStatus.CONFLICT,
                    eligibility.message());
        }
        String id = UUID.randomUUID().toString();
        // UPSERT holds an exclusive key lock; unlike duplicate-INSERT shared-lock upgrades,
        // three or more simultaneous attempts serialize without creating a lock-upgrade cycle.
        repository.insert(id, key, current, reason.strip(), eligibility);
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

    /** Old global entry points remain fail-closed for callers upgrading from earlier versions. */
    public List<AfterSaleTicket> pending() { throw scopeRequired(); }

    public List<AfterSaleTicket> reviewedHistory() { throw scopeRequired(); }

    public AfterSaleTicket retryExecution(CustomerPrincipal reviewer, UUID id) { throw scopeRequired(); }

    public AfterSaleTicket decide(CustomerPrincipal reviewer, UUID id, ReviewDecision decision, String note) {
        throw scopeRequired();
    }

    public List<AfterSaleTicket> pending(CustomerPrincipal reviewer, long shopId) {
        merchantAccess.requireShop(reviewer, shopId);
        return repository.pending(shopId);
    }

    public List<AfterSaleTicket> reviewedHistory(CustomerPrincipal reviewer, long shopId) {
        merchantAccess.requireShop(reviewer, shopId);
        return repository.reviewedHistory(shopId);
    }

    @Transactional
    @PreAuthorize("hasAuthority('after-sale:review')")
    public AfterSaleTicket retryExecution(CustomerPrincipal reviewer, long shopId, UUID id) {
        merchantAccess.lockShop(reviewer, shopId);
        repository.findById(id.toString(), shopId).orElseThrow(AfterSaleService::notFound);
        executions.retry(reviewer, shopId, id);
        return repository.findById(id.toString(), shopId).orElseThrow(AfterSaleService::notFound).ticket();
    }

    @Transactional
    @PreAuthorize("hasAuthority('after-sale:review')")
    public AfterSaleTicket decide(CustomerPrincipal reviewer, long shopId, UUID id,
                                 ReviewDecision decision, String note) {
        var access = merchantAccess.lockShop(reviewer, shopId);
        if (decision == null || note == null || note.isBlank() || note.length() > 500) {
            throw new AssistantBusinessException(ErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST,
                    "必须提供审批决定和1至500字的审核意见。");
        }
        StoredAfterSale stored = repository.findById(id.toString(), shopId).orElseThrow(AfterSaleService::notFound);
        if (reviewer.getIdentity().buyerId() == stored.buyerId()) {
            throw new AccessDeniedException("Requester cannot review their own case");
        }
        if (stored.ticket().status() != AfterSaleStatus.PENDING_REVIEW) throw conflict();
        SupportOrder approvedOrder = null;
        if (decision == ReviewDecision.APPROVE) {
            SupportOrder current = orders.getOwnedOrder(new CustomerIdentity(stored.buyerId()),
                    stored.ticket().shopId(), stored.ticket().orderNo());
            if (current.id() != stored.orderId()) throw conflict();
            SupportOrder locked = repository.lockOwnedOrder(current).orElseThrow(AfterSaleService::conflict);
            if (locked.status() == OrderStatus.CREATED || locked.status() == OrderStatus.CANCELLED) {
                throw conflict();
            }
            // A timely application does not expire while waiting for a human decision.
            // Missing historical/receipt data requires the reviewer's explicit, mandatory review note.
            AfterSaleEligibility snapshot = stored.ticket().eligibility();
            if (snapshot != null && !snapshot.canSubmit()) throw conflict();
            approvedOrder = locked;
        }
        AfterSaleStatus status = decision == ReviewDecision.APPROVE
                ? AfterSaleStatus.APPROVED : AfterSaleStatus.REJECTED;
        if (!repository.decide(stored, status, reviewer.getUsername(), note.strip())) throw conflict();
        repository.audit(id.toString(), reviewer.getUsername(), status.name(), note.strip(), access.staffId());
        if (approvedOrder != null) executions.enqueue(id.toString(), approvedOrder);
        return repository.findById(id.toString(), shopId).orElseThrow(AfterSaleService::notFound).ticket();
    }

    private static AccessDeniedException scopeRequired() {
        return new AccessDeniedException("Explicit merchant shop authorization is required");
    }

    private static AssistantBusinessException notFound() {
        return new AssistantBusinessException(ErrorCode.REVIEW_NOT_FOUND, HttpStatus.NOT_FOUND, "售后申请不存在");
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
