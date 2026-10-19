package dev.supportflow.chat.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderListCommandTest {

    @Test
    @DisplayName("省略游标或显式零游标都表示首批订单")
    void shouldStartFromZeroWhenCursorIsAbsent() {
        assertThat(OrderListCommand.parse("我的订单")).contains(new OrderListCommand(0L));
        assertThat(OrderListCommand.parse("我的订单 0")).contains(new OrderListCommand(0L));
    }

    @Test
    @DisplayName("指令允许首尾空白和Unicode空白分隔符")
    void shouldAllowWhitespaceSeparators() {
        assertThat(OrderListCommand.parse("  我的订单\t123\n"))
                .contains(new OrderListCommand(123L));
        assertThat(OrderListCommand.parse("我的订单\u3000123"))
                .contains(new OrderListCommand(123L));
    }

    @Test
    @DisplayName("普通消息和单笔查单不会被识别为列表指令")
    void shouldIgnoreUnrelatedMessages() {
        for (String message : new String[]{"你好", "查订单 100 SF10001", "查看我的订单", "下一页"}) {
            assertThat(OrderListCommand.parse(message)).isEmpty();
        }
    }

    @Test
    @DisplayName("非法游标与额外字段一律拒绝，不猜测或忽略用户输入")
    void shouldRejectMalformedCommands() {
        String[] commands = {
                "我的订单1", "我的订单 -1", "我的订单 +1", "我的订单 1.5",
                "我的订单 abc", "我的订单 9223372036854775808",
                "我的订单 0 buyerId=201", "我的订单 1 20", "我的订单 1 OR 1=1"
        };
        for (String command : commands) {
            assertThatThrownBy(() -> OrderListCommand.parse(command))
                    .as(command)
                    .isInstanceOf(InvalidChatCommandException.class)
                    .hasMessage("列表格式：我的订单，或：我的订单 <非负整数游标>；翻页请复制回复中的指令");
        }
    }

    @Test
    @DisplayName("long上界内的游标原样保留，不当成页码递增")
    void shouldPreserveMaximumCursor() {
        assertThat(OrderListCommand.parse("我的订单 " + Long.MAX_VALUE))
                .contains(new OrderListCommand(Long.MAX_VALUE));
    }

    @Test
    @DisplayName("直接构造指令也不能绕过非负游标约束")
    void shouldRejectNegativeCursorAtConstruction() {
        assertThatThrownBy(() -> new OrderListCommand(-1L))
                .isInstanceOf(InvalidChatCommandException.class);
    }
}
