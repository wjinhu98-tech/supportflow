package dev.supportflow.chat.conversation;

import dev.supportflow.assistant.orchestration.ConversationOrchestrator;
import dev.supportflow.assistant.aftersale.AssistantBusinessException;
import dev.supportflow.chat.api.response.ChatResponse;
import dev.supportflow.chat.application.InvalidChatCommandException;
import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class ConversationService {
    private static final Logger LOG = LoggerFactory.getLogger(ConversationService.class);
    private final ConversationStore store;
    private final ConversationOrchestrator orchestrator;

    public ConversationService(ConversationStore store, ConversationOrchestrator orchestrator) {
        this.store = store;
        this.orchestrator = orchestrator;
    }

    public Conversation create(CustomerPrincipal principal, long shopId) {
        return store.create(principal.getIdentity().buyerId(), shopId);
    }

    public ConversationPage list(CustomerPrincipal principal, long shopId, UUID beforeId) {
        return store.list(principal.getIdentity().buyerId(), shopId, beforeId);
    }

    public ConversationMessages messages(CustomerPrincipal principal, long shopId, UUID id, UUID beforeId) {
        return store.messages(principal.getIdentity().buyerId(), shopId, id, beforeId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ConversationTurnResponse send(CustomerPrincipal principal, long shopId, UUID id,
                                         ConversationMessageRequest request) {
        long buyerId = principal.getIdentity().buyerId();
        ConversationStore.Reservation reservation = store.reserve(buyerId, shopId, id, request);
        if (reservation.cached()) return reservation.cachedResponse();
        try {
            // NOT_SUPPORTED suspends any caller transaction while the HTTP/model operation runs.
            ChatResponse response = ChatResponse.from(orchestrator.reply(
                    principal, shopId, request.message(), request.orderNo()));
            return store.succeed(buyerId, shopId, id, request, response);
        } catch (RuntimeException failure) {
            try {
                store.fail(buyerId, shopId, id, request.clientMessageId());
            } catch (RuntimeException markingFailure) {
                // The processing lease remains bounded even when the failure marker cannot be saved.
                LOG.error("Could not finalize a conversation turn failure: {}", markingFailure.getClass().getSimpleName());
            }
            LOG.warn("Conversation turn did not complete and will not be automatically replayed: {}",
                    failure.getClass().getSimpleName());
            if (failure instanceof AccessDeniedException || failure instanceof OrderNotFoundException
                    || failure instanceof InvalidChatCommandException || failure instanceof AssistantBusinessException
                    || failure instanceof ModelUnavailableException || failure instanceof ConversationException) {
                throw failure;
            }
            throw ConversationException.unavailable();
        }
    }
}
