package dev.supportflow.assistant.execution;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * A local payment simulator backed by an immutable receipt ledger.
 * The caller locks the execution task, so its receipt, status and audit commit atomically.
 */
@Service
public class SimulatedRefundGateway implements RefundGateway {
    private final JdbcClient jdbc;

    public SimulatedRefundGateway(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RefundReceipt refund(ExecutionClaim claim) {
        if (claim == null || claim.amount() == null || claim.amount().signum() < 0
                || !"CNY".equals(claim.currency())) {
            throw new ExecutionRejectedException("模拟退款金额或币种不符合要求");
        }
        Optional<RefundReceipt> existing = find(claim.requestId());
        if (existing.isPresent()) return matchingReceipt(claim, existing.get());

        int inserted = jdbc.sql("""
                INSERT INTO support_simulated_refund (request_id, reference, amount, currency)
                VALUES (:requestId, :reference, :amount, :currency)
                """)
                .param("requestId", claim.requestId()).param("reference", "SIM-" + UUID.randomUUID())
                .param("amount", claim.amount()).param("currency", claim.currency()).update();
        if (inserted != 1) throw new IllegalStateException("Simulated refund receipt was not persisted");
        RefundReceipt receipt = find(claim.requestId())
                .orElseThrow(() -> new IllegalStateException("Simulated refund receipt is missing"));
        return matchingReceipt(claim, receipt);
    }

    private Optional<RefundReceipt> find(String requestId) {
        return jdbc.sql("""
                SELECT reference, amount, currency, completed_at
                FROM support_simulated_refund WHERE request_id=:requestId
                """).param("requestId", requestId).query(SimulatedRefundGateway::map).optional();
    }

    private static RefundReceipt matchingReceipt(ExecutionClaim claim, RefundReceipt receipt) {
        if (claim.amount().compareTo(receipt.amount()) != 0 || !claim.currency().equals(receipt.currency())) {
            throw new ExecutionRejectedException("模拟退款回执与审批金额或币种不一致");
        }
        return receipt;
    }

    private static RefundReceipt map(ResultSet row, int ignored) throws SQLException {
        return new RefundReceipt(row.getString("reference"), row.getBigDecimal("amount"),
                row.getString("currency"),
                row.getObject("completed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
}
