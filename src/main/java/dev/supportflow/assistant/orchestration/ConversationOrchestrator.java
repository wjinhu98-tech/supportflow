package dev.supportflow.assistant.orchestration;

import dev.supportflow.assistant.knowledge.KnowledgeService;
import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.common.security.model.CustomerPrincipal;
import org.springframework.stereotype.Service;

/** API -> router -> retrieval OR bounded agent -> Java-owned business result. */
@Service
public class ConversationOrchestrator {
    private final ConversationRouter router;
    private final KnowledgeService knowledge;
    private final AssistantAgent agent;

    public ConversationOrchestrator(ConversationRouter router, KnowledgeService knowledge, AssistantAgent agent) {
        this.router = router;
        this.knowledge = knowledge;
        this.agent = agent;
    }

    public ChatReply reply(CustomerPrincipal principal, long shopId, String message, String orderNo) {
        AssistantRoute route = router.route(message);
        if (route == AssistantRoute.KNOWLEDGE) return ChatReply.text(knowledge.answer(message));
        return agent.execute(route, principal, shopId, message, orderNo);
    }
}
