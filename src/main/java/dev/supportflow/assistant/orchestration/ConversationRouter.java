package dev.supportflow.assistant.orchestration;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** Route authority is Java-owned. A model cannot turn a read question into a write tool. */
@Component
public class ConversationRouter {
    private static final Pattern NEGATED_APPLICATION = Pattern.compile(
            "(?:不退款|不退货|不申请售后)"
                    + "|(?:不想|不打算|不需要|不考虑|不要|不用|别|暂不|不再|不愿|不必|没打算|不申请|撤销|取消).{0,12}(?:退款|退货|售后)"
                    + "|(?:退款|退货|售后).{0,8}(?:取消|撤销|不申请了|不办了)");

    public AssistantRoute route(String message) {
        String text = message.strip();
        // Explicit commands own their arguments: policy words inside a reason are not a knowledge query.
        if (text.startsWith("申请售后 ")) return AssistantRoute.AFTER_SALE;
        if (isPolicyQuestion(text)) return AssistantRoute.KNOWLEDGE;
        if (isHistoryQuestion(text)) return AssistantRoute.AFTER_SALE_HISTORY;
        if (isKnowledgeQuestion(text)) return AssistantRoute.KNOWLEDGE;
        if (NEGATED_APPLICATION.matcher(text).find() || isRefundApplicationRequest(text)) {
            return AssistantRoute.AFTER_SALE_GUIDANCE;
        }
        if (text.contains("物流") || text.contains("快递") || text.contains("运单")
                || text.contains("配送进度") || text.contains("到哪了") || text.contains("到哪里了")) return AssistantRoute.LOGISTICS;
        return AssistantRoute.ORDER;
    }

    /** A request opens a read-only form; only its separate, explicit submission may create a case. */
    static boolean isRefundApplicationRequest(String message) {
        String text = message.strip();
        if (NEGATED_APPLICATION.matcher(text).find() || isKnowledgeQuestion(text) || isHistoryQuestion(text)) {
            return false;
        }
        if (text.equals("退款") || text.equals("退货") || text.equals("退货退款") || text.equals("申请售后")) return true;
        boolean refundSubject = text.contains("退款") || text.contains("退货") || text.contains("申请售后");
        return refundSubject && (text.contains("申请") || text.contains("我要") || text.contains("我想")
                || text.contains("想要") || text.contains("希望") || text.contains("需要")
                || text.contains("帮我") || text.contains("给我") || text.contains("请") || text.contains("麻烦")
                || text.contains("将") || text.contains("把") || text.contains("办理") || text.contains("发起")
                || text.contains("提交") || text.contains("这笔") || text.contains("这单") || text.contains("该订单")
                || !dev.supportflow.assistant.planning.ExplicitOrderNumbers.extract(text).isEmpty());
    }

    private static boolean isPolicyQuestion(String text) {
        if (text.contains("政策") || text.contains("规则") || text.contains("使用帮助") || text.contains("知识库")
                || text.contains("状态有哪些") || text.contains("审批流程")) return true;
        boolean afterSale = text.contains("退款") || text.contains("退货") || text.contains("售后");
        return afterSale && (text.contains("期限") || text.contains("售后期") || text.contains("多少天")
                || text.contains("几天"));
    }

    private static boolean isKnowledgeQuestion(String text) {
        if (isPolicyQuestion(text)) return true;
        boolean afterSale = text.contains("退款") || text.contains("退货") || text.contains("售后");
        return afterSale && (text.contains("如何") || text.contains("怎么") || text.contains("怎样")
                || text.contains("能否") || text.contains("能不能") || text.contains("可以") || text.contains("是否")
                || text.contains("咨询") || text.contains("介绍") || text.contains("了解") || text.contains("说明")
                || text.contains("想知道"));
    }

    private static boolean isHistoryQuestion(String text) {
        return text.equals("我的售后") || text.equals("我的退款") || text.contains("退款进度")
                || text.contains("退款处理到") || text.contains("退款状态") || text.contains("退款到账")
                || text.contains("退款成功") || text.contains("申请退款了吗")
                || text.contains("退款申请记录") || text.contains("退款被拒绝") || text.contains("退款审核")
                || ((text.contains("退款") || text.contains("售后")) && (text.contains("进度")
                    || text.contains("处理到") || text.contains("处理状态") || text.contains("处理结果")
                    || text.contains("到账") || text.contains("退到")));
    }
}
