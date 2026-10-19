package dev.supportflow.chat.application;

import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private static final CustomerIdentity IDENTITY = new CustomerIdentity(200L);
    private OrderQueryService orderQueryService;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        orderQueryService = mock(OrderQueryService.class);
        chatService = new ChatService(orderQueryService);
    }

    @Test
    @DisplayName("普通聊天不触发订单查询，保留原始消息")
    void shouldEchoWithoutReadingOrders() {
        assertThat(chatService.reply(IDENTITY, "  你好  "))
                .isEqualTo("收到你的问题：  你好  ");
        verifyNoInteractions(orderQueryService);
    }

    @Test
    @DisplayName("使用可信身份查单，回复只来自实际订单快照")
    void shouldReplyWithActualOrderFacts() {
        SupportOrder order = new SupportOrder(
                1001L, 100L, 777L, 200L, "SF10001", OrderStatus.SHIPPED,
                new BigDecimal("199.90"), "CNY", Instant.EPOCH, Instant.EPOCH);
        when(orderQueryService.getOwnedOrder(IDENTITY, 100L, "SF10001")).thenReturn(order);

        assertThat(chatService.reply(IDENTITY, "查订单 100 SF10001"))
                .isEqualTo("商铺 100 的订单 SF10001：已发货，订单金额 199.90 CNY。");
        verify(orderQueryService).getOwnedOrder(IDENTITY, 100L, "SF10001");
    }

    @Test
    @DisplayName("格式不合法时不查询数据库")
    void shouldRejectInvalidCommandBeforeQuery() {
        assertThatThrownBy(() -> chatService.reply(IDENTITY, "查订单 SF10001"))
                .isInstanceOf(InvalidChatCommandException.class);
        verifyNoInteractions(orderQueryService);
    }

    @Test
    @DisplayName("订单不可访问沿用统一业务异常，不编造正常答复")
    void shouldPropagateOrderNotFound() {
        when(orderQueryService.getOwnedOrder(IDENTITY, 100L, "SF10001"))
                .thenThrow(new OrderNotFoundException());
        assertThatThrownBy(() -> chatService.reply(IDENTITY, "查订单 100 SF10001"))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("权限拒绝不能被聊天回复掩盖")
    void shouldPropagateAccessDenied() {
        when(orderQueryService.getOwnedOrder(IDENTITY, 100L, "SF10001"))
                .thenThrow(new AccessDeniedException("denied"));
        assertThatThrownBy(() -> chatService.reply(IDENTITY, "查订单 100 SF10001"))
                .isInstanceOf(AccessDeniedException.class);
    }
}
