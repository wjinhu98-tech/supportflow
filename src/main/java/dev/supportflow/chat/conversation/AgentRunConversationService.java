package dev.supportflow.chat.conversation;

import dev.supportflow.assistant.runtime.AgentRunView;
import dev.supportflow.assistant.runtime.ReadOnlyAgentRuntime;
import dev.supportflow.assistant.runtime.store.AgentFailureCode;
import dev.supportflow.assistant.runtime.store.AgentRun;
import dev.supportflow.assistant.runtime.store.AgentRunKey;
import dev.supportflow.assistant.runtime.store.AgentRunStatus;
import dev.supportflow.assistant.runtime.store.AgentRunStore;
import dev.supportflow.chat.api.response.ChatResponse;
import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.common.security.model.CustomerPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Explicit recovery of a persisted read-only run; never a replay of the original chat pipeline. */
@Service
public class AgentRunConversationService {
    private static final Logger LOG = LoggerFactory.getLogger(AgentRunConversationService.class);
    private final ReadOnlyAgentRuntime runtime;
    private final AgentRunStore runs;
    private final ConversationStore conversations;

    public AgentRunConversationService(ReadOnlyAgentRuntime runtime, AgentRunStore runs, ConversationStore conversations) {
        this.runtime = runtime;
        this.runs = runs;
        this.conversations = conversations;
    }

    public AgentRunView view(CustomerPrincipal principal, long shopId, UUID conversationId, UUID clientMessageId) {
        AgentRunKey key = key(principal, shopId, conversationId, clientMessageId);
        AgentRunView view = runtime.view(principal, key);
        boolean completed = conversations.completed(key.buyerId(), shopId, conversationId, clientMessageId);
        return view.withResumable(!completed && view.resumable()
                        && conversations.latest(key.buyerId(), shopId, conversationId, clientMessageId)
                        && conversations.recoverable(key.buyerId(), shopId, conversationId, clientMessageId,
                                view.status() != AgentRunStatus.SUCCEEDED));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ConversationTurnResponse resume(CustomerPrincipal principal, long shopId, UUID conversationId, UUID clientMessageId) {
        AgentRunKey key = key(principal, shopId, conversationId, clientMessageId);
        AgentRun original = runtime.findOwned(principal, key);
        // Claim the run before reopening its turn. Concurrent workers cannot reserve two turn generations.
        AgentRun claimed = runtime.claimResume(principal, key);
        ConversationStore.Reservation turn = null;
        try {
            boolean reclaimProcessing = claimed.status() == AgentRunStatus.RUNNING;
            turn = conversations.reserveResume(key.buyerId(), shopId, conversationId, original.request(), reclaimProcessing);
            if (turn.cached()) return turn.cachedResponse();
            AgentRun completed = runtime.resumeClaimed(principal, claimed);
            // This is the persisted result of the original run, not a claim that old observations are current facts.
            ChatResponse response = ChatResponse.from(ChatReply.text(completed.result()))
                    .withContext(ConversationContextRef.none());
            return conversations.succeed(key.buyerId(), shopId, conversationId, original.request(), response, turn.processingToken());
        } catch (RuntimeException failure) {
            if (turn != null && !turn.cached()) {
                try {
                    conversations.fail(key.buyerId(), shopId, conversationId, clientMessageId, turn.processingToken());
                } catch (RuntimeException ignored) {
                    LOG.warn("Recovered turn could not be finalized; its lease remains bounded");
                }
            }
            try {
                runs.pause(key, claimed.generation(), AgentFailureCode.EXECUTION_INTERRUPTED);
            } catch (RuntimeException ignored) {
                LOG.warn("Recovered run lease could not be released");
            }
            throw failure;
        }
    }

    private static AgentRunKey key(CustomerPrincipal principal, long shopId, UUID conversationId, UUID clientMessageId) {
        return new AgentRunKey(conversationId, clientMessageId, principal.getIdentity().buyerId(), shopId);
    }
}
