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
        for (String text : new String[]{"申请售后 SF10001 商品破损", "  申请售后 商品破损  "}) {
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
    void shouldOpenReasonFormOnlyForAffirmativeRefundRequests() {
        for (String text : new String[]{"申请退款", "将该订单退款", "我要退款", "我要退货", "申请售后",
                "退款", "退货退款", "请给我办理退款", "SF10001退款"}) {
            assertThat(router.route(text)).as(text).isEqualTo(AssistantRoute.AFTER_SALE_GUIDANCE);
            assertThat(ConversationRouter.isRefundApplicationRequest(text)).as(text).isTrue();
        }
    }

    @Test
    void shouldKeepNegationsReadOnlyAndOutOfTheOrderModel() {
        for (String text : new String[]{"我不要申请退款", "不要申请退货", "我不退款", "这笔退款取消", "取消退款",
                "别帮我申请售后 SF10001", "暂不申请退款"}) {
            assertThat(router.route(text)).as(text).isEqualTo(AssistantRoute.AFTER_SALE_GUIDANCE);
            assertThat(ConversationRouter.isRefundApplicationRequest(text)).as(text).isFalse();
        }
    }

    @Test
    void shouldKeepHowToAndEligibilityQuestionsInKnowledge() {
        for (String text : new String[]{"如何申请退款", "如何申请退货", "我想知道怎么申请售后",
                "SF10001是否可以退款", "退款几天到账", "确认收货多少天内可以退款"}) {
            assertThat(router.route(text)).as(text).isEqualTo(AssistantRoute.KNOWLEDGE);
            assertThat(ConversationRouter.isRefundApplicationRequest(text)).as(text).isFalse();
        }
    }

    @Test
    void shouldRouteOnlyExactHistoryCommandToHistory() {
        assertThat(router.route("  我的售后  ")).isEqualTo(AssistantRoute.AFTER_SALE_HISTORY);
        assertThat(router.route("我的售后政策")).isEqualTo(AssistantRoute.KNOWLEDGE);
    }

    @Test
    void shouldRouteAfterSaleProgressToOwnedHistoryInsteadOfLogistics() {
        for (String text : new String[]{"我的售后处理到哪了", "查看售后进度", "我的退款进度", "查询售后处理结果",
                "退款是否到账", "如何查看我的退款进度", "SF10001申请退款了吗", "退款被拒绝了"}) {
            assertThat(router.route(text)).as(text).isEqualTo(AssistantRoute.AFTER_SALE_HISTORY);
            assertThat(ConversationRouter.isRefundApplicationRequest(text)).as(text).isFalse();
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
