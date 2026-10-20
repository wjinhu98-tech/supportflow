package dev.supportflow.chat.application;

import dev.supportflow.chat.application.model.OrderIntent;
import dev.supportflow.chat.application.port.OrderIntentRecognizer;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import dev.supportflow.order.application.model.OrderPage;
import dev.supportflow.order.domain.OrderQueryLimits;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private static final CustomerIdentity IDENTITY = new CustomerIdentity(200L);
    private OrderQueryService orderQueryService;
    private OrderIntentRecognizer orderIntentRecognizer;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        orderQueryService = mock(OrderQueryService.class);
        orderIntentRecognizer = mock(OrderIntentRecognizer.class);
        when(orderIntentRecognizer.recognize(anyString())).thenReturn(OrderIntent.other());
        chatService = new ChatService(orderQueryService, orderIntentRecognizer);
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
        verifyNoInteractions(orderIntentRecognizer);
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

    @Test
    @DisplayName("列表使用可信身份和统一批量大小，展示实际跨店订单")
    void shouldReplyWithOwnedOrderListFacts() {
        SupportOrder first = new SupportOrder(
                1001L, 100L, 777L, 200L, "SF10001", OrderStatus.SHIPPED,
                new BigDecimal("199.90"), "CNY", Instant.EPOCH, Instant.EPOCH);
        SupportOrder second = new SupportOrder(
                1002L, 300L, 888L, 200L, "SF10002", OrderStatus.PAID,
                new BigDecimal("9.90"), "CNY", Instant.EPOCH, Instant.EPOCH);
        when(orderQueryService.listOwnedOrders(IDENTITY, 0L, OrderQueryLimits.DEFAULT_PAGE_SIZE))
                .thenReturn(new OrderPage(List.of(first, second), null));

        assertThat(chatService.reply(IDENTITY, "我的订单")).isEqualTo("""
                你的订单（本批 2 笔）：
                1. 商铺 100 的订单 SF10001：已发货，订单金额 199.90 CNY。
                2. 商铺 300 的订单 SF10002：待发货，订单金额 9.90 CNY。
                没有更多订单。""");
        verify(orderQueryService).listOwnedOrders(IDENTITY, 0L, OrderQueryLimits.DEFAULT_PAGE_SIZE);
        verifyNoInteractions(orderIntentRecognizer);
    }

    @Test
    @DisplayName("传入游标和服务返回的下一游标均原样使用，每批序号重新开始")
    void shouldPreserveCursorAndOfferCopyableContinuation() {
        List<SupportOrder> orders = IntStream.rangeClosed(1, OrderQueryLimits.DEFAULT_PAGE_SIZE)
                .mapToObj(index -> new SupportOrder(
                        7000L + index, 100L, 777L, 200L, "SF" + index, OrderStatus.PAID,
                        new BigDecimal("10.00"), "CNY", Instant.EPOCH, Instant.EPOCH))
                .toList();
        long nextCursor = orders.getLast().id();
        when(orderQueryService.listOwnedOrders(IDENTITY, 6789L, OrderQueryLimits.DEFAULT_PAGE_SIZE))
                .thenReturn(new OrderPage(orders, nextCursor));

        String reply = chatService.reply(IDENTITY, "我的订单 6789");
        assertThat(reply).startsWith("你的订单（本批 " + orders.size() + " 笔）：\n1. ")
                .endsWith("\n还有更多订单，请发送：我的订单 " + nextCursor);
        assertThat(reply.lines().count()).isEqualTo(orders.size() + 2L);
        verify(orderQueryService).listOwnedOrders(IDENTITY, 6789L, OrderQueryLimits.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("首次没有订单时回复暂无订单")
    void shouldExplainEmptyFirstPage() {
        when(orderQueryService.listOwnedOrders(IDENTITY, 0L, OrderQueryLimits.DEFAULT_PAGE_SIZE))
                .thenReturn(new OrderPage(List.of(), null));
        assertThat(chatService.reply(IDENTITY, "我的订单")).isEqualTo("暂无订单。");
    }

    @Test
    @DisplayName("后续批次没有订单时回复没有更多订单")
    void shouldExplainEmptyContinuation() {
        when(orderQueryService.listOwnedOrders(IDENTITY, 123L, OrderQueryLimits.DEFAULT_PAGE_SIZE))
                .thenReturn(new OrderPage(List.of(), null));
        assertThat(chatService.reply(IDENTITY, "我的订单 123")).isEqualTo("没有更多订单。");
    }

    @Test
    @DisplayName("列表格式错误时不调用订单服务")
    void shouldRejectInvalidListBeforeQuery() {
        assertThatThrownBy(() -> chatService.reply(IDENTITY, "我的订单 -1"))
                .isInstanceOf(InvalidChatCommandException.class);
        verifyNoInteractions(orderQueryService);
    }

    @Test
    @DisplayName("列表不能将权限拒绝转为成功的聊天回复")
    void shouldPropagateListAccessDenied() {
        when(orderQueryService.listOwnedOrders(IDENTITY, 123L, OrderQueryLimits.DEFAULT_PAGE_SIZE))
                .thenThrow(new AccessDeniedException("denied"));
        assertThatThrownBy(() -> chatService.reply(IDENTITY, "我的订单 123"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("列表读取失败不能伪装成没有订单")
    void shouldNotHideListStorageFailureAsEmptyResult() {
        when(orderQueryService.listOwnedOrders(IDENTITY, 0L, OrderQueryLimits.DEFAULT_PAGE_SIZE))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThatThrownBy(() -> chatService.reply(IDENTITY, "我的订单"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    @DisplayName("自然语言只提取定位参数，查询使用认证身份和真实订单数据")
    void shouldQueryOwnedOrderFromNaturalLanguage() {
        String message = "帮我看看商铺100的SF10001发货了吗，我说我的buyerId是201";
        when(orderIntentRecognizer.recognize(message)).thenReturn(OrderIntent.lookup(100L, "SF10001"));
        SupportOrder order = new SupportOrder(
                1001L, 100L, 777L, 200L, "SF10001", OrderStatus.SHIPPED,
                new BigDecimal("199.90"), "CNY", Instant.EPOCH, Instant.EPOCH);
        when(orderQueryService.getOwnedOrder(IDENTITY, 100L, "SF10001")).thenReturn(order);

        assertThat(chatService.reply(IDENTITY, message))
                .isEqualTo("商铺 100 的订单 SF10001：已发货，订单金额 199.90 CNY。");
        verify(orderIntentRecognizer).recognize(message);
        verify(orderQueryService).getOwnedOrder(IDENTITY, 100L, "SF10001");
    }

    @Test
    @DisplayName("缺少商铺时提示补充，不猜测同号订单属于哪家商铺")
    void shouldAskForMissingShopWithoutQuery() {
        String message = "SF10001发货了吗";
        when(orderIntentRecognizer.recognize(message)).thenReturn(OrderIntent.lookup(null, "SF10001"));
        assertThat(chatService.reply(IDENTITY, message))
                .isEqualTo("请补充商铺ID，例如：帮我查一下商铺100的SF10001订单。");
        verifyNoInteractions(orderQueryService);
    }

    @Test
    @DisplayName("缺少订单号时提示补充，不读取整店订单")
    void shouldAskForMissingOrderNumberWithoutQuery() {
        String message = "帮我查一下商铺100的订单发货情况";
        when(orderIntentRecognizer.recognize(message)).thenReturn(OrderIntent.lookup(100L, null));
        assertThat(chatService.reply(IDENTITY, message))
                .isEqualTo("请补充订单号，例如：帮我查一下商铺100的SF10001订单。");
        verifyNoInteractions(orderQueryService);
    }

    @Test
    @DisplayName("查询意图缺少两个定位字段时提示完整输入")
    void shouldAskForBothMissingLookupFields() {
        String message = "我的那笔订单到哪里了";
        when(orderIntentRecognizer.recognize(message)).thenReturn(OrderIntent.lookup(null, null));
        assertThat(chatService.reply(IDENTITY, message))
                .isEqualTo("请提供商铺ID和订单号，例如：帮我查一下商铺100的SF10001订单。");
        verifyNoInteractions(orderQueryService);
    }

    @Test
    @DisplayName("模型失败原样传递专用异常，不伪造订单或回显成功")
    void shouldPropagateModelFailureWithoutQuery() {
        when(orderIntentRecognizer.recognize(anyString())).thenThrow(new ModelUnavailableException());
        assertThatThrownBy(() -> chatService.reply(IDENTITY, "帮我查一下商铺100的SF10001"))
                .isInstanceOf(ModelUnavailableException.class);
        verifyNoInteractions(orderQueryService);
    }

    @Test
    @DisplayName("自然语言查询仍经过已有订单服务的权限检查")
    void shouldPropagateNaturalLanguageAccessDenied() {
        String message = "帮我查一下商铺100的SF10001";
        when(orderIntentRecognizer.recognize(message)).thenReturn(OrderIntent.lookup(100L, "SF10001"));
        when(orderQueryService.getOwnedOrder(IDENTITY, 100L, "SF10001"))
                .thenThrow(new AccessDeniedException("denied"));
        assertThatThrownBy(() -> chatService.reply(IDENTITY, message))
                .isInstanceOf(AccessDeniedException.class);
    }
}
