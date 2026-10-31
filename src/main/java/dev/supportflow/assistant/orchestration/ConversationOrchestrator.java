package dev.supportflow.assistant.orchestration;

import dev.supportflow.assistant.knowledge.KnowledgeService;
import dev.supportflow.assistant.runtime.store.AgentRunInput;
import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.chat.application.CustomerQuestionScope;
import dev.supportflow.common.security.model.CustomerPrincipal;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/** API -> router -> retrieval OR bounded agent -> Java-owned business result. */
@Service
public class ConversationOrchestrator {
    private static final Pattern BUSINESS_QUERY = Pattern.compile(
            "订单|查单|确认收货|取消|支付|付款|申请|审批|退款|退货|售后|重试|执行|进度", Pattern.CASE_INSENSITIVE);
    private final ConversationRouter router;
    private final KnowledgeService knowledge;
    private final AssistantAgent agent;

    public ConversationOrchestrator(ConversationRouter router, KnowledgeService knowledge, AssistantAgent agent) {
        this.router = router;
        this.knowledge = knowledge;
        this.agent = agent;
    }

    public ChatReply reply(CustomerPrincipal principal, long shopId, String message, String orderNo) {
        if (CustomerQuestionScope.isClearlyUnrelated(message)) return ChatReply.outOfScope();
        AssistantRoute route = router.route(message);
        if (route == AssistantRoute.KNOWLEDGE) {
            var answer = knowledge.answerDetailed(shopId, message);
            return ChatReply.knowledge(answer.reply(), answer.evidence());
        }
        if (route == AssistantRoute.ORDER && allowsFaqLookup(message, orderNo)) {
            var answer = knowledge.tryAnswerDetailed(shopId, message);
            if (answer.isPresent()) return ChatReply.knowledge(answer.get().reply(), answer.get().evidence());
        }
        return agent.execute(route, principal, shopId, message, orderNo);
    }

    public ChatReply reply(CustomerPrincipal principal, long shopId, String message, String orderNo, AgentRunInput input) {
        if (CustomerQuestionScope.isClearlyUnrelated(message)) return ChatReply.outOfScope();
        AssistantRoute route = router.route(message);
        if (route != AssistantRoute.LOGISTICS) return reply(principal, shopId, message, orderNo);
        return agent.execute(route, principal, shopId, message, orderNo, input);
    }

    private static boolean allowsFaqLookup(String message, String orderNo) {
        // Only the non-business subset of ORDER's existing fallback may consult shop FAQs.
        // Selected-order context keeps the existing business path authoritative, even for short follow-ups.
        return (orderNo == null || orderNo.isBlank())
                && !KnowledgeService.isOrderFactQuestion(message)
                && dev.supportflow.assistant.planning.ExplicitOrderNumbers.extract(message).isEmpty()
                && !BUSINESS_QUERY.matcher(message).find();
    }
}
