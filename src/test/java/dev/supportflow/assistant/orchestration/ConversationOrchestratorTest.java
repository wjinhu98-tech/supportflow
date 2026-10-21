package dev.supportflow.assistant.orchestration;

import dev.supportflow.assistant.knowledge.KnowledgeService;
import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationOrchestratorTest {
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final AssistantAgent agent = mock(AssistantAgent.class);
    private final CustomerPrincipal principal = new CustomerPrincipal("buyer", "not-a-real-hash",
            new CustomerIdentity(200), List.of());
    private final ConversationOrchestrator orchestrator = new ConversationOrchestrator(new ConversationRouter(), knowledge, agent);

    @Test
    void shouldAnswerKnowledgeWithoutInvokingBusinessAgent() {
        when(knowledge.answer("退款政策是什么")).thenReturn("source grounded policy");
        assertThat(orchestrator.reply(principal, 100, "退款政策是什么", "SF10001").reply())
                .isEqualTo("source grounded policy");
        verifyNoInteractions(agent);
    }

    @Test
    void shouldPassTrustedIdentityShopAndSelectedOrderToAgent() {
        ChatReply expected = ChatReply.text("shipment result");
        when(agent.execute(AssistantRoute.LOGISTICS, principal, 100, "物流到了吗", "SF10001")).thenReturn(expected);
        assertThat(orchestrator.reply(principal, 100, "物流到了吗", "SF10001")).isSameAs(expected);
        verify(agent).execute(AssistantRoute.LOGISTICS, principal, 100, "物流到了吗", "SF10001");
        verifyNoInteractions(knowledge);
    }
}
