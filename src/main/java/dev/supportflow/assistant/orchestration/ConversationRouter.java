package dev.supportflow.assistant.orchestration;

import org.springframework.stereotype.Component;

/** Route authority is Java-owned. A model cannot turn a read question into a write tool. */
@Component
public class ConversationRouter {
    public AssistantRoute route(String message) {
        String text = message.strip();
        // Explicit commands own their arguments: policy words inside a reason are not a knowledge query.
        if (text.equals("申请售后") || text.startsWith("申请售后 ")) return AssistantRoute.AFTER_SALE;
        if (text.contains("政策") || text.contains("规则") || text.contains("使用帮助") || text.contains("知识库")
                || text.contains("状态有哪些") || text.contains("审批流程")) return AssistantRoute.KNOWLEDGE;
        if (text.equals("我的售后") || text.contains("退款进度") || text.contains("退款处理到")
                || (text.contains("售后") && (text.contains("进度") || text.contains("处理到")
                    || text.contains("处理状态") || text.contains("处理结果")))) {
            return AssistantRoute.AFTER_SALE_HISTORY;
        }
        // Mere discussion of refunds is read-only.
        if (text.contains("申请退款") || text.contains("申请退货")) return AssistantRoute.AFTER_SALE_GUIDANCE;
        if (text.contains("物流") || text.contains("快递") || text.contains("运单")
                || text.contains("配送进度") || text.contains("到哪了") || text.contains("到哪里了")) return AssistantRoute.LOGISTICS;
        return AssistantRoute.ORDER;
    }
}
