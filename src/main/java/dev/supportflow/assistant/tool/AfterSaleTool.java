package dev.supportflow.assistant.tool;

import dev.supportflow.assistant.aftersale.AfterSaleService;
import dev.supportflow.assistant.aftersale.AfterSaleTicket;
import dev.supportflow.assistant.aftersale.AfterSaleStatus;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.assistant.execution.AfterSaleExecution;
import dev.supportflow.assistant.execution.ExecutionStatus;
import org.springframework.stereotype.Service;

@Service
public class AfterSaleTool {
    private final AfterSaleService cases;
    public AfterSaleTool(AfterSaleService cases) { this.cases = cases; }

    public String request(CustomerPrincipal principal, long shopId, String orderNo, String reason) {
        AfterSaleTicket ticket = cases.submit(principal, shopId, orderNo, reason);
        return "退款申请已保存。\n售后申请单：" + ticket.id() + "\n订单：" + ticket.orderNo()
                + "\n状态：" + reviewStatus(ticket.status())
                + "\n请在“我的售后”查看审核结果与处理进度。当前为演示环境，不会实际返款。";
    }

    public String history(CustomerPrincipal principal, long shopId) {
        var tickets = cases.listOwned(principal.getIdentity(), shopId);
        if (tickets.isEmpty()) return "你在当前店铺暂无售后申请。";
        StringBuilder result = new StringBuilder("当前店铺的售后单（最多显示最近50笔）：");
        for (AfterSaleTicket ticket : tickets) {
            result.append('\n').append(ticket.orderNo()).append(" · ").append(reviewStatus(ticket.status()))
                    .append(" · 单号 ").append(ticket.id());
            if (ticket.reviewNote() != null) result.append(" · 审核意见：").append(ticket.reviewNote());
            AfterSaleExecution progress = ticket.execution();
            if (progress != null) {
                result.append(" · 退款处理：").append(executionStatus(progress.status()))
                        .append(" · 金额 ").append(progress.amount().toPlainString()).append(' ').append(progress.currency());
                if (progress.reference() != null) result.append(" · 演示凭证 ").append(progress.reference());
                if (progress.completedAt() != null) result.append(" · 完成时间 ").append(progress.completedAt());
            } else if (ticket.status() == AfterSaleStatus.APPROVED) {
                result.append(" · 后续处理情况将由客服确认");
            }
        }
        return result.append("\n当前为演示环境，不会实际返款。").toString();
    }

    private static String reviewStatus(AfterSaleStatus status) {
        return switch (status) {
            case PENDING_REVIEW -> "等待客服审核";
            case APPROVED -> "客服审核通过";
            case REJECTED -> "客服审核未通过";
        };
    }

    private static String executionStatus(ExecutionStatus status) {
        return switch (status) {
            case PENDING -> "等待处理";
            case PROCESSING -> "正在处理";
            case SUCCEEDED -> "演示处理完成";
            case FAILED -> "暂未完成，客服将继续跟进";
        };
    }
}
