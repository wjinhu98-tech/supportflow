package dev.supportflow.chat.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderLookupCommandTest {

    @Test
    @DisplayName("查单指令只提取订单号，不携带店铺和买家")
    void shouldParseExplicitCommand() {
        assertThat(OrderLookupCommand.parse("查订单 SF10001")).contains(new OrderLookupCommand("SF10001"));
    }

    @Test
    void shouldAllowWhitespaceSeparators() {
        assertThat(OrderLookupCommand.parse("  查订单\t SF10001\n")).contains(new OrderLookupCommand("SF10001"));
        assertThat(OrderLookupCommand.parse("查订单\u3000SF10001")).contains(new OrderLookupCommand("SF10001"));
    }

    @Test
    @DisplayName("自然语言前缀不会被固定命令误拒绝，单独查订单由应用层展示列表")
    void shouldLeaveNaturalLanguageAndBareLookupToService() {
        for (String message : new String[]{"你好", "查订单", "查订单，SF10001发货了吗", "查订单SF10001", "SF10001"}) {
            assertThat(OrderLookupCommand.parse(message)).isEmpty();
        }
    }

    @Test
    void shouldRejectMalformedCommandsAndShopArguments() {
        for (String command : new String[]{"查订单 100 SF10001", "查订单 SF10001 buyerId=201", "查订单 " + "A".repeat(65)}) {
            assertThatThrownBy(() -> OrderLookupCommand.parse(command))
                    .isInstanceOf(InvalidChatCommandException.class)
                    .hasMessage("查单格式：查订单 <订单号>，商铺由当前咨询页面确定");
        }
        assertThatThrownBy(() -> new OrderLookupCommand("")).isInstanceOf(InvalidChatCommandException.class);
        assertThatThrownBy(() -> new OrderLookupCommand("SF\n10001")).isInstanceOf(InvalidChatCommandException.class);
    }

    @Test
    void shouldPreserveCaseAndMaximumOrderNumberLength() {
        String orderNo = "a".repeat(64);
        assertThat(OrderLookupCommand.parse("查订单 " + orderNo)).contains(new OrderLookupCommand(orderNo));
    }
}
