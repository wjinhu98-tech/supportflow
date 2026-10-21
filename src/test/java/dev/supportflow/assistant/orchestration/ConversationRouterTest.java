package dev.supportflow.assistant.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationRouterTest {
    private final ConversationRouter router = new ConversationRouter();

    @Test
    void shouldTreatPolicyDiscussionAsKnowledgeRatherThanBusinessWrite() {
        for (String text : new String[]{"退款政策是什么", "介绍售后规则", "使用帮助", "订单状态有哪些", "介绍审批流程", "企业知识库"}) {
            assertThat(router.route(text)).as(text).isEqualTo(AssistantRoute.KNOWLEDGE);
        }
    }

    @Test
    void shouldRequireExplicitAfterSaleCommandPrefixBeforeRoutingWrite() {
        for (String text : new String[]{"申请售后 SF10001 商品破损", "  申请售后 商品破损  ", "申请售后"}) {
            assertThat(router.route(text)).isEqualTo(AssistantRoute.AFTER_SALE);
        }
        for (String text : new String[]{"我想知道怎么申请售后", "不要申请售后 SF10001", "申请售后说明", "退款", "咨询退款"}) {
            assertThat(router.route(text)).isNotEqualTo(AssistantRoute.AFTER_SALE);
        }
    }

    @Test
    void shouldKeepExplicitAfterSaleCommandWhenItsReasonMentionsKnowledgeKeywords() {
        for (String reason : new String[]{"商品不符合退货政策", "商品不符合规则", "使用帮助有误", "知识库描述不符", "审批流程存在问题"}) {
            assertThat(router.route("申请售后 SF10001 " + reason))
                    .as(reason).isEqualTo(AssistantRoute.AFTER_SALE);
        }
        assertThat(router.route("申请售后规则")).isEqualTo(AssistantRoute.KNOWLEDGE);
        assertThat(router.route("售后审批流程")).isEqualTo(AssistantRoute.KNOWLEDGE);
    }

    @Test
    void shouldKeepRefundAndReturnSuggestionsReadOnlyIncludingNegations() {
        for (String text : new String[]{"申请退款", "我不要申请退款", "不要申请退货", "如何申请退款", "如何申请退货"}) {
            assertThat(router.route(text)).isEqualTo(AssistantRoute.AFTER_SALE_GUIDANCE);
        }
    }

    @Test
    void shouldRouteOnlyExactHistoryCommandToHistory() {
        assertThat(router.route("  我的售后  ")).isEqualTo(AssistantRoute.AFTER_SALE_HISTORY);
        assertThat(router.route("我的售后政策")).isEqualTo(AssistantRoute.KNOWLEDGE);
    }

    @Test
    void shouldRouteAfterSaleProgressToOwnedHistoryInsteadOfLogistics() {
        for (String text : new String[]{"我的售后处理到哪了", "查看售后进度", "我的退款进度", "查询售后处理结果"}) {
            assertThat(router.route(text)).as(text).isEqualTo(AssistantRoute.AFTER_SALE_HISTORY);
        }
        assertThat(router.route("介绍售后处理规则")).isEqualTo(AssistantRoute.KNOWLEDGE);
        assertThat(router.route("申请售后 查询处理进度不准确")).isEqualTo(AssistantRoute.AFTER_SALE);
    }

    @Test
    void shouldRouteShipmentQuestionsToLogisticsAndOtherMessagesToOrders() {
        for (String text : new String[]{"SF10001物流到哪里了", "快递到了吗", "运单号是什么", "SF10001到哪了", "SF10001到哪里了", "配送进度"}) {
            assertThat(router.route(text)).as(text).isEqualTo(AssistantRoute.LOGISTICS);
        }
        for (String text : new String[]{"你好", "我的订单", "查订单SF10001", "这笔订单多少钱"}) {
            assertThat(router.route(text)).isEqualTo(AssistantRoute.ORDER);
        }
    }
}
