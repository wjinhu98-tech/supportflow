package dev.supportflow.order.application;

import dev.supportflow.order.application.exception.OrderNotFoundException;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import dev.supportflow.order.domain.repository.SupportOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import dev.supportflow.common.security.model.CustomerIdentity;

class OrderQueryServiceTest {

    private SupportOrderRepository supportOrderRepository;

    private OrderQueryService orderQueryService;

    private static final CustomerIdentity CUSTOMER_IDENTITY =
            new CustomerIdentity(100L, 200L);

    @BeforeEach
    void setUp() {
        supportOrderRepository = mock(SupportOrderRepository.class);

        orderQueryService = new OrderQueryService(
                supportOrderRepository
        );
    }

    @Test
    @DisplayName("范围内存在订单时返回仓储查询结果")
    void shouldReturnOwnedOrder() {
        SupportOrder expectedOrder = new SupportOrder(
                1001L,
                100L,
                200L,
                "SF10001",
                OrderStatus.SHIPPED,
                new BigDecimal("199.90"),
                "CNY",
                Instant.parse("2026-10-01T02:03:04.123Z"),
                Instant.parse("2026-10-02T05:06:07.456Z")
        );

        when(supportOrderRepository.findByOrderNo(
                100L,
                200L,
                "SF10001"
        )).thenReturn(Optional.of(expectedOrder));

        SupportOrder actualOrder = orderQueryService.getOwnedOrder(
                CUSTOMER_IDENTITY,
                "SF10001"
        );

        assertThat(actualOrder).isSameAs(expectedOrder);

        verify(supportOrderRepository).findByOrderNo(
                100L,
                200L,
                "SF10001"
        );
    }

    @Test
    @DisplayName("范围内没有订单时抛出订单查询业务异常")
    void shouldThrowWhenOrderIsNotAccessible() {
        when(supportOrderRepository.findByOrderNo(
                100L,
                200L,
                "SF_NOT_EXISTS"
        )).thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> orderQueryService.getOwnedOrder(
                        CUSTOMER_IDENTITY,
                        "SF_NOT_EXISTS"
                )
        )
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessage("订单不存在或无权访问");

        verify(supportOrderRepository).findByOrderNo(
                100L,
                200L,
                "SF_NOT_EXISTS"
        );
    }
}