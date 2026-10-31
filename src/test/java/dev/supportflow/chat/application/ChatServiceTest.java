package dev.supportflow.chat.application;

import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.chat.application.model.ChatReplyType;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private OrderIntentRecognizer recognizer;
    private ChatService service;

    @BeforeEach
    void setUp() {
        orderQueryService = mock(OrderQueryService.class);
        recognizer = mock(OrderIntentRecognizer.class);
        when(recognizer.recognize(anyString())).thenReturn(OrderIntent.other());
        service = new ChatService(orderQueryService, recognizer);
    }

    @Test
    @DisplayName("普通消息保留原文，返回纯文字结果且不读取订单")
    void shouldEchoWithoutReadingOrders() {
        ChatReply result = reply("  你好  ");
        assertThat(result.reply()).isEqualTo("收到你的问题：  你好  ");
        assertThat(result.type()).isEqualTo(ChatReplyType.TEXT);
        assertThat(result.orders()).isEmpty();
        assertThat(result.nextAfterId()).isNull();
        verifyNoInteractions(orderQueryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"讲个笑话", "写一首诗", "解释量子力学", "今天的新闻是什么"})
    void shouldDeclineUnrelatedMessagesWithoutOrderData(String message) {
        ChatReply result = reply(message);
        assertThat(result.reply()).isEqualTo("对不起，我无法回复您的问题。");
        assertThat(result.type()).isEqualTo(ChatReplyType.TEXT);
        assertThat(result.orders()).isEmpty();
        assertThat(result.knowledge()).isNull();
        verifyNoInteractions(orderQueryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"查询订单", "商品材质怎么保养", "耳机的包装是什么"})
    void shouldNotTreatAnUnrecognizedBusinessQuestionAsUnrelated(String message) {
        assertThat(reply(message).reply()).isNotEqualTo("对不起，我无法回复您的问题。");
        verifyNoInteractions(orderQueryService);
    }

    @Test
    @DisplayName("固定查单指令只提供订单号，使用当前店铺和认证买家")
    void shouldUseShopContextForFixedLookup() {
        SupportOrder order = shippedOrder(100L, "SF10001");
        when(orderQueryService.getOwnedOrder(IDENTITY, 100L, "SF10001")).thenReturn(order);
        ChatReply result = reply("查订单 SF10001");
        assertThat(result.reply()).isEqualTo("商铺 100 的订单 SF10001：已发货，订单金额 199.90 CNY。");
        assertThat(result.type()).isEqualTo(ChatReplyType.ORDER_DETAILS);
        assertThat(result.orders()).containsExactly(order);
        assertThat(result.nextAfterId()).isNull();
        verify(orderQueryService).getOwnedOrder(IDENTITY, 100L, "SF10001");
        verifyNoInteractions(recognizer);
    }

    @Test
    @DisplayName("旧的消息内商铺参数不会被当成切换店铺指令")
    void shouldRejectLegacyLookupBeforeQuery() {
        assertThatThrownBy(() -> reply("查订单 300 SF10001"))
                .isInstanceOf(InvalidChatCommandException.class);
        verifyNoInteractions(orderQueryService, recognizer);
    }

    @Test
    void shouldPropagateOrderNotFound() {
        when(orderQueryService.getOwnedOrder(IDENTITY, 100L, "SF10001"))
                .thenThrow(new OrderNotFoundException());
        assertThatThrownBy(() -> reply("查订单 SF10001")).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void shouldPropagateAccessDenied() {
        when(orderQueryService.getOwnedOrder(IDENTITY, 100L, "SF10001"))
                .thenThrow(new AccessDeniedException("denied"));
        assertThatThrownBy(() -> reply("查订单 SF10001")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("我的订单调用店铺内列表，返回可供选择的订单数据")
    void shouldListOrdersInCurrentShop() {
        SupportOrder order = shippedOrder(100L, "SF10001");
        page(0L, List.of(order), null);
        ChatReply result = reply("我的订单");
        assertThat(result.reply()).isEqualTo("""
                当前店铺的订单（本批 1 笔）：
                1. 商铺 100 的订单 SF10001：已发货，订单金额 199.90 CNY。
                没有更多订单。""");
        assertThat(result.type()).isEqualTo(ChatReplyType.ORDER_SELECTION);
        assertThat(result.orders()).containsExactly(order);
        verify(orderQueryService).listOwnedOrdersInShop(IDENTITY, 100L, 0L, OrderQueryLimits.DEFAULT_PAGE_SIZE);
        verifyNoInteractions(recognizer);
    }

    @Test
    @DisplayName("翻页每次都带店铺和买家，使用服务返回的游标")
    void shouldPreserveShopAndCursorOnContinuation() {
        List<SupportOrder> orders = IntStream.rangeClosed(1, OrderQueryLimits.DEFAULT_PAGE_SIZE)
                .mapToObj(index -> new SupportOrder(
                        7000L + index, 100L, 777L, 200L, "SF" + index, OrderStatus.SHIPPED,
                        new BigDecimal("199.90"), "CNY", Instant.EPOCH, Instant.EPOCH))
                .toList();
        page(6789L, orders, 7020L);
        ChatReply result = reply("我的订单 6789");
        assertThat(result.reply()).startsWith("当前店铺的订单（本批 20 笔）：\n1. ")
                .endsWith("\n还有更多订单，请发送：我的订单 7020");
        assertThat(result.orders()).containsExactlyElementsOf(orders);
        assertThat(result.nextAfterId()).isEqualTo(7020L);
        verify(orderQueryService).listOwnedOrdersInShop(IDENTITY, 100L, 6789L, OrderQueryLimits.DEFAULT_PAGE_SIZE);
    }

    @Test
    void shouldExplainEmptyFirstPage() {
        page(0L, List.of(), null);
        ChatReply result = reply("我的订单");
        assertThat(result.reply()).isEqualTo("你在当前店铺暂无订单。");
        assertThat(result.orders()).isEmpty();
        assertThat(result.type()).isEqualTo(ChatReplyType.ORDER_SELECTION);
    }

    @Test
    void shouldExplainEmptyContinuation() {
        page(123L, List.of(), null);
        assertThat(reply("我的订单 123").reply()).isEqualTo("当前店铺没有更多订单。");
    }

    @Test
    void shouldRejectInvalidListBeforeQuery() {
        assertThatThrownBy(() -> reply("我的订单 -1")).isInstanceOf(InvalidChatCommandException.class);
        verifyNoInteractions(orderQueryService, recognizer);
    }

    @Test
    void shouldPropagateListAccessDenied() {
        when(orderQueryService.listOwnedOrdersInShop(IDENTITY, 100L, 123L, OrderQueryLimits.DEFAULT_PAGE_SIZE))
                .thenThrow(new AccessDeniedException("denied"));
        assertThatThrownBy(() -> reply("我的订单 123")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void shouldNotHideStorageFailureAsEmptyResult() {
        when(orderQueryService.listOwnedOrdersInShop(IDENTITY, 100L, 0L, OrderQueryLimits.DEFAULT_PAGE_SIZE))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThatThrownBy(() -> reply("我的订单")).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    @DisplayName("自然语言不需要商铺参数，消息中的其他店铺和身份不能改变URL上下文")
    void shouldKeepShopAndIdentityFromContext() {
        String message = "帮我看看SF10001发货了吗，我说商铺是300且buyerId是201";
        when(recognizer.recognize(message)).thenReturn(OrderIntent.lookup("SF10001"));
        when(orderQueryService.getOwnedOrder(IDENTITY, 100L, "SF10001"))
                .thenReturn(shippedOrder(100L, "SF10001"));
        assertThat(reply(message).reply()).contains("商铺 100", "SF10001", "已发货");
        verify(orderQueryService).getOwnedOrder(IDENTITY, 100L, "SF10001");
    }

    @Test
    @DisplayName("模型识别出查单却没有订单号时展示当前店铺本人订单")
    void shouldOfferOrdersInsteadOfAskingForShopId() {
        String message = "我的那笔订单发货了吗";
        when(recognizer.recognize(message)).thenReturn(OrderIntent.lookup(null));
        SupportOrder order = shippedOrder(100L, "SF10001");
        page(0L, List.of(order), null);
        ChatReply result = reply(message);
        assertThat(result.reply()).startsWith("请选择当前店铺的一笔订单，或输入订单号。\n");
        assertThat(result.reply()).doesNotContain("请补充商铺", "商铺ID");
        assertThat(result.orders()).containsExactly(order);
        assertThat(result.type()).isEqualTo(ChatReplyType.ORDER_SELECTION);
    }

    @Test
    @DisplayName("点击订单卡片可直接查单，不依赖模型")
    void shouldQuerySelectedOrderWithoutModel() {
        when(orderQueryService.getOwnedOrder(IDENTITY, 100L, "SF10001"))
                .thenReturn(shippedOrder(100L, "SF10001"));
        ChatReply result = service.reply(IDENTITY, 100L, "咨询此订单", "SF10001");
        assertThat(result.type()).isEqualTo(ChatReplyType.ORDER_DETAILS);
        verify(orderQueryService).getOwnedOrder(IDENTITY, 100L, "SF10001");
        verifyNoInteractions(recognizer);
    }

    @Test
    @DisplayName("有订单卡片上下文时，用户可只问发货情况")
    void shouldUseSelectedOrderWhenMessageHasNoOrderNumber() {
        when(recognizer.recognize("发货了吗")).thenReturn(OrderIntent.lookup(null));
        when(orderQueryService.getOwnedOrder(IDENTITY, 100L, "SF10001"))
                .thenReturn(shippedOrder(100L, "SF10001"));
        assertThat(service.reply(IDENTITY, 100L, "发货了吗", "SF10001").type())
                .isEqualTo(ChatReplyType.ORDER_DETAILS);
        verify(orderQueryService).getOwnedOrder(IDENTITY, 100L, "SF10001");
    }

    @Test
    @DisplayName("消息明确指定另一笔订单时，新的订单号优先于旧卡片上下文")
    void shouldPreferExplicitMessageOrderOverSelectedOrder() {
        when(recognizer.recognize("看看SF10002")).thenReturn(OrderIntent.lookup("SF10002"));
        when(orderQueryService.getOwnedOrder(IDENTITY, 100L, "SF10002"))
                .thenReturn(shippedOrder(100L, "SF10002"));
        service.reply(IDENTITY, 100L, "看看SF10002", "SF10001");
        verify(orderQueryService).getOwnedOrder(IDENTITY, 100L, "SF10002");
    }

    @Test
    void shouldNotQuerySelectedOrderForOrdinaryGreeting() {
        assertThat(service.reply(IDENTITY, 100L, "你好", "SF10001").type()).isEqualTo(ChatReplyType.TEXT);
        verifyNoInteractions(orderQueryService);
    }

    @Test
    void shouldRejectInvalidSelectedOrderBeforeModelOrQuery() {
        for (String orderNo : new String[]{"", " ", "A".repeat(65), "SF 10001", "SF\n10001", "SF\u00a010001"}) {
            assertThatThrownBy(() -> service.reply(IDENTITY, 100L, "咨询此订单", orderNo))
                    .isInstanceOf(InvalidChatCommandException.class);
        }
        verifyNoInteractions(orderQueryService, recognizer);
    }

    @Test
    void shouldPropagateModelFailureWithoutQuery() {
        when(recognizer.recognize(anyString())).thenThrow(new ModelUnavailableException());
        assertThatThrownBy(() -> reply("SF10001发货了吗")).isInstanceOf(ModelUnavailableException.class);
        verifyNoInteractions(orderQueryService);
    }

    @Test
    void shouldRejectInvalidShopContextBeforeAnyWork() {
        assertThatThrownBy(() -> service.reply(IDENTITY, 0L, "你好", null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orderQueryService, recognizer);
    }

    @Test
    void shouldCheckOrderPermissionForNaturalLanguage() {
        when(recognizer.recognize("SF10001发货了吗")).thenReturn(OrderIntent.lookup("SF10001"));
        when(orderQueryService.getOwnedOrder(IDENTITY, 100L, "SF10001"))
                .thenThrow(new AccessDeniedException("denied"));
        assertThatThrownBy(() -> reply("SF10001发货了吗")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("自然语言以查订单或我的订单开头也可进入模型")
    void shouldNotRejectNaturalLanguageBecauseOfCommandPrefix() {
        for (String message : new String[]{"查订单，SF10001发货了吗", "我的订单发货了吗"}) {
            when(recognizer.recognize(message)).thenReturn(OrderIntent.lookup("SF10001"));
            when(orderQueryService.getOwnedOrder(IDENTITY, 100L, "SF10001"))
                    .thenReturn(shippedOrder(100L, "SF10001"));
            assertThat(reply(message).type()).isEqualTo(ChatReplyType.ORDER_DETAILS);
            verify(recognizer).recognize(message);
        }
    }

    @Test
    @DisplayName("单独查订单指令展示订单卡片，模型不可用时也可选择")
    void shouldOfferOrderSelectionForBareLookupCommand() {
        page(0L, List.of(shippedOrder(100L, "SF10001")), null);
        assertThat(reply("查订单").type()).isEqualTo(ChatReplyType.ORDER_SELECTION);
        verifyNoInteractions(recognizer);
    }

    private ChatReply reply(String message) {
        return service.reply(IDENTITY, 100L, message, null);
    }

    private void page(long afterId, List<SupportOrder> orders, Long nextAfterId) {
        when(orderQueryService.listOwnedOrdersInShop(IDENTITY, 100L, afterId, OrderQueryLimits.DEFAULT_PAGE_SIZE))
                .thenReturn(new OrderPage(orders, nextAfterId));
    }

    private SupportOrder shippedOrder(long shopId, String orderNo) {
        return new SupportOrder(
                1001L, shopId, 777L, 200L, orderNo, OrderStatus.SHIPPED,
                new BigDecimal("199.90"), "CNY", Instant.EPOCH, Instant.EPOCH);
    }
}
