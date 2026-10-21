package dev.supportflow.assistant.planning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleBasedToolPlannerTest {
    private final ToolPlanner planner = new RuleBasedToolPlanner();

    @Test
    void shouldExtractExactExplicitOrderButNeverChangePermittedTool() {
        for (ToolName tool : ToolName.values()) {
            assertThat(planner.plan(tool, "查询【SF10001-2】物流", "SF99999"))
                    .isEqualTo(new PlannedToolCall(tool, "SF10001-2"));
        }
    }

    @Test
    void shouldUseSelectedOrderOnlyAsFallback() {
        assertThat(planner.plan(ToolName.LOGISTICS_LOOKUP, "物流到了吗", "ORDER-123").orderNo())
                .isEqualTo("ORDER-123");
        assertThat(planner.plan(ToolName.ORDER_LOOKUP, "查一下订单", null).orderNo()).isNull();
    }

    @Test
    void shouldLeaveAmbiguousOrTruncatedNumbersUnknown() {
        for (String message : new String[]{"SF10001 和 SF10002", "XSF10001", "sf10001", "SF10001.2",
                "SF" + "1".repeat(63)}) {
            assertThat(planner.plan(ToolName.ORDER_LOOKUP, message, null).orderNo()).isNull();
        }
        assertThat(planner.plan(ToolName.ORDER_LOOKUP, "SF10001 和 SF10001", null).orderNo())
                .isEqualTo("SF10001");
    }

    @Test
    void shouldValidatePlannedParametersWithoutLeakingInput() {
        assertThat(new PlannedToolCall(ToolName.ORDER_LOOKUP, null).orderNo()).isNull();
        assertThat(new PlannedToolCall(ToolName.ORDER_LOOKUP, "S".repeat(64)).orderNo()).hasSize(64);
        for (String invalid : new String[]{"", " ", "SF 1", "SF\n1", "SF\u00001", "SF\u00a01", "S".repeat(65)}) {
            assertThatThrownBy(() -> new PlannedToolCall(ToolName.ORDER_LOOKUP, invalid))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("SF");
        }
        assertThatThrownBy(() -> new PlannedToolCall(null, null)).isInstanceOf(NullPointerException.class);
    }
}
