package dev.supportflow.assistant.aftersale;

import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class AfterSalePolicyTest {
    private final AfterSalePolicy policy = new AfterSalePolicy(new AfterSalePolicyProperties(15));
    private final Instant receipt = Instant.parse("2026-10-01T12:00:00Z");

    @Test void exactDeadlineIsExpiredButOneMillisecondBeforeIsEligible() {
        var order = order(OrderStatus.COMPLETED, receipt);
        var deadline = receipt.plus(Duration.ofDays(15));
        assertThat(policy.evaluate(order, deadline.minusMillis(1)).status()).isEqualTo(AfterSaleEligibilityStatus.ELIGIBLE);
        assertThat(policy.evaluate(order, deadline).canSubmit()).isFalse();
        assertThat(policy.evaluate(order, deadline).status()).isEqualTo(AfterSaleEligibilityStatus.EXPIRED);
    }

    @Test void missingAndFutureReceiptRequireHumanVerification() {
        for (Instant date : new Instant[]{null, receipt.plusSeconds(1)}) {
            var result = policy.evaluate(order(OrderStatus.COMPLETED, date), receipt);
            assertThat(result.status()).isEqualTo(AfterSaleEligibilityStatus.REQUIRES_REVIEW);
            assertThat(result.canSubmit()).isTrue();
            assertThat(result.receivedAt()).isEqualTo(date);
        }
    }

    @Test void unpaidOrCancelledCannotApplyEvenWithMissingReceipt() {
        for (var status : new OrderStatus[]{OrderStatus.CREATED, OrderStatus.CANCELLED}) {
            assertThat(policy.evaluate(order(status, null), receipt).canSubmit()).isFalse();
        }
    }

    private SupportOrder order(OrderStatus status, Instant receivedAt) {
        return new SupportOrder(1, 100, 200, 200, "SF10001", status, new BigDecimal("26.80"),
                "CNY", receipt.minusSeconds(100), receipt, receivedAt);
    }
}
