package dev.supportflow.order.application;

import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import dev.supportflow.order.domain.model.SupportOrder;
import dev.supportflow.order.domain.repository.SupportOrderRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 当前身份范围内的订单查询用例。
 */
@Service
public class OrderQueryService {

    private final SupportOrderRepository supportOrderRepository;

    public OrderQueryService(
            SupportOrderRepository supportOrderRepository
    ) {
        this.supportOrderRepository = Objects.requireNonNull(
                supportOrderRepository,
                "supportOrderRepository must not be null"
        );
    }

    /**
     * 获取当前身份有权访问的订单。
     *
     * @param identity 来自可信认证结果的客户身份
     * @param orderNo 业务订单号
     * @return 查询范围内的订单
     * @throws OrderNotFoundException 范围内没有可访问的订单
     * @throws IllegalArgumentException 订单号为 null 或空白
     */
    public SupportOrder getOwnedOrder(
            CustomerIdentity identity,
            String orderNo
    ) {
        Objects.requireNonNull(
                identity,
                "identity must not be null"
        );

        return supportOrderRepository.findByOrderNo(
                identity.tenantId(),
                identity.customerId(),
                orderNo
        ).orElseThrow(OrderNotFoundException::new);
    }
}