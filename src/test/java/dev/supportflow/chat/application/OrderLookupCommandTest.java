package dev.supportflow.chat.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderLookupCommandTest {

    @Test
    @DisplayName("查单指令只提取商铺和订单号，不产生买家身份")
    void shouldParseExplicitCommand() {
        assertThat(OrderLookupCommand.parse("查订单 100 SF10001"))
                .contains(new OrderLookupCommand(100L, "SF10001"));
    }

    @Test
    @DisplayName("容忍指令首尾空白及字段间多个空白")
    void shouldAllowWhitespaceSeparators() {
        assertThat(OrderLookupCommand.parse("  查订单\t100  SF10001\n"))
                .contains(new OrderLookupCommand(100L, "SF10001"));
        assertThat(OrderLookupCommand.parse("查订单\u3000100\u3000SF10001"))
                .contains(new OrderLookupCommand(100L, "SF10001"));
    }

    @Test
    @DisplayName("普通消息不猜测为查单指令")
    void shouldLeaveOrdinaryMessagesUnchanged() {
        for (String message : new String[]{"你好", "查询订单", "帮我查订单 SF10001", "SF10001"}) {
            assertThat(OrderLookupCommand.parse(message)).isEmpty();
        }
    }

    @Test
    @DisplayName("缺字段、额外字段、非法商铺及超长订单号明确拒绝")
    void shouldRejectMalformedCommands() {
        String[] invalidCommands = {
                "查订单", "查订单 100", "查订单100 SF10001", "查订单 abc SF10001",
                "查订单 0 SF10001", "查订单 -1 SF10001", "查订单 1.5 SF10001",
                "查订单 9223372036854775808 SF10001", "查订单 100 SF10001 buyerId=201",
                "查订单 100 " + "A".repeat(65)
        };
        for (String command : invalidCommands) {
            assertThatThrownBy(() -> OrderLookupCommand.parse(command))
                    .isInstanceOf(InvalidChatCommandException.class)
                    .hasMessage("查单格式：查订单 <商铺ID> <订单号>，例如：查订单 100 SF10001");
        }
    }

    @Test
    @DisplayName("边界内的商铺ID和64字符订单号保持原值")
    void shouldAcceptBoundaryValuesWithoutChangingOrderNumber() {
        String orderNo = "a".repeat(64);
        assertThat(OrderLookupCommand.parse("查订单 " + Long.MAX_VALUE + " " + orderNo))
                .contains(new OrderLookupCommand(Long.MAX_VALUE, orderNo));
    }
}
