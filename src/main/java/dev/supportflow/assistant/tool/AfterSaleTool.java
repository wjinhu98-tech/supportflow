package dev.supportflow.assistant.tool;

import dev.supportflow.assistant.aftersale.AfterSaleService;
import dev.supportflow.assistant.aftersale.AfterSaleTicket;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.assistant.execution.AfterSaleExecution;
import org.springframework.stereotype.Service;

@Service
public class AfterSaleTool {
    private final AfterSaleService cases;
    public AfterSaleTool(AfterSaleService cases) { this.cases = cases; }

    public String request(CustomerPrincipal principal, long shopId, String orderNo, String reason) {
        AfterSaleTicket ticket = cases.submit(principal, shopId, orderNo, reason);
        return "售后申请单：" + ticket.id() + "\n订单：" + ticket.orderNo()
                + "\n状态：" + ticket.status() + "\n"
                + "申请已经保存；只有客服或管理员可以审核，AI和申请人不能自行审批。"
                + "这是本项目学习版售后受理，不代表已退款或支付渠道到账。";
    }

    public String history(CustomerPrincipal principal, long shopId) {
        var tickets = cases.listOwned(principal.getIdentity(), shopId);
        if (tickets.isEmpty()) return "你在当前店铺暂无售后申请。";
        StringBuilder result = new StringBuilder("当前店铺的售后单（最多显示最近50笔）：");
        for (AfterSaleTicket ticket : tickets) {
            result.append('\n').append(ticket.orderNo()).append(" · ").append(ticket.status())
                    .append(" · 单号 ").append(ticket.id());
            if (ticket.reviewNote() != null) result.append(" · 审核意见：").append(ticket.reviewNote());
            AfterSaleExecution progress = ticket.execution();
            if (progress != null) {
                result.append(" · 模拟处理：").append(progress.status())
                        .append(" · 金额 ").append(progress.amount().toPlainString()).append(' ').append(progress.currency())
                        .append(" · 已尝试 ").append(progress.attemptCount()).append('/').append(progress.maxAttempts());
                if (progress.reference() != null) result.append(" · 模拟凭证 ").append(progress.reference());
                if (progress.completedAt() != null) result.append(" · 完成时间 ").append(progress.completedAt());
                if (progress.nextAttemptAt() != null) result.append(" · 下次处理时间 ").append(progress.nextAttemptAt());
                if (progress.lastError() != null) result.append(" · 处理说明：").append(progress.lastError());
            } else if (ticket.status() == dev.supportflow.assistant.aftersale.AfterSaleStatus.APPROVED) {
                result.append(" · 历史审批，尚未创建处理任务");
            }
        }
        return result.append("\n审批结果与处理进度分别记录；模拟处理成功不代表真实支付渠道退款到账。").toString();
    }
}
