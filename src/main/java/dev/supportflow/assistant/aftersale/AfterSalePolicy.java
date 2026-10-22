package dev.supportflow.assistant.aftersale;

import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Receipt timestamps originate from trusted order data, never a browser or the language model. */
@Component
@EnableConfigurationProperties(AfterSalePolicyProperties.class)
public class AfterSalePolicy {
    private final AfterSalePolicyProperties properties;

    public AfterSalePolicy(AfterSalePolicyProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    public AfterSaleEligibility evaluate(SupportOrder order, Instant checkedAt) {
        Objects.requireNonNull(order);
        Objects.requireNonNull(checkedAt);
        Instant receivedAt = order.receivedAt();
        Instant deadline = receivedAt == null ? null : receivedAt.plus(Duration.ofDays(properties.windowDays()));
        if (order.status() == OrderStatus.CREATED || order.status() == OrderStatus.CANCELLED) {
            return result(order, AfterSaleEligibilityStatus.INELIGIBLE, false,
                    "该订单尚未付款或已取消，暂不能申请退款。", receivedAt, deadline, checkedAt);
        }
        if (receivedAt == null) {
            return result(order, AfterSaleEligibilityStatus.REQUIRES_REVIEW, true,
                    "订单缺少确认收货时间，请填写退款原因，提交后由客服核实售后期限。", null, null, checkedAt);
        }
        if (receivedAt.isAfter(checkedAt)) {
            return result(order, AfterSaleEligibilityStatus.REQUIRES_REVIEW, true,
                    "订单的确认收货时间需要核实，请填写退款原因，提交后由客服人工审核。",
                    receivedAt, deadline, checkedAt);
        }
        if (!checkedAt.isBefore(deadline)) {
            return result(order, AfterSaleEligibilityStatus.EXPIRED, false,
                    "该订单已超过确认收货后" + properties.windowDays() + "天的售后申请期限，如有疑问请联系客服。",
                    receivedAt, deadline, checkedAt);
        }
        return result(order, AfterSaleEligibilityStatus.ELIGIBLE, true,
                "该订单在确认收货后" + properties.windowDays() + "天的售后期限内，请填写退款原因后提交申请。",
                receivedAt, deadline, checkedAt);
    }

    private AfterSaleEligibility result(SupportOrder order, AfterSaleEligibilityStatus status, boolean canSubmit,
                                        String message, Instant receivedAt, Instant deadline, Instant checkedAt) {
        return new AfterSaleEligibility(order.tenantId(), order.orderNo(), status, canSubmit,
                message, receivedAt, deadline, checkedAt, properties.windowDays());
    }
}
