package dev.supportflow.chat.support;

import dev.supportflow.chat.conversation.ConversationException;
import dev.supportflow.common.security.model.CustomerPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

/** A short persisted lease covers legacy AI calls without holding a transaction during remote work. */
@Service
public class HumanSupportAiGate {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    public HumanSupportAiGate(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
    }
    public <T> T call(CustomerPrincipal principal, long shop, Supplier<T> operation) {
        long buyer = principal.getIdentity().buyerId();
        UUID token = UUID.randomUUID();
        transaction.executeWithoutResult(state -> {
            HumanSupportScope.lock(jdbc, buyer, shop);
            HumanSupportScope.requireAiAllowed(jdbc, buyer, shop);
            if (HumanSupportScope.legacyProcessing(jdbc, buyer, shop)) throw ConversationException.conflict("AI正在回复，请稍后再试");
            jdbc.sql("UPDATE support_chat_scope SET legacy_processing_token=:token,legacy_expires_at=TIMESTAMPADD(SECOND,300,UTC_TIMESTAMP(3)) WHERE buyer_id=:buyer AND tenant_id=:shop")
                    .param("token", token.toString()).param("buyer", buyer).param("shop", shop).update();
        });
        try {
            T result = operation.get();
            transaction.executeWithoutResult(state -> {
                HumanSupportScope.lock(jdbc, buyer, shop);
                HumanSupportScope.requireAiAllowed(jdbc, buyer, shop);
                long owned = jdbc.sql("SELECT COUNT(*) FROM support_chat_scope WHERE buyer_id=:buyer AND tenant_id=:shop AND legacy_processing_token=:token AND legacy_expires_at>UTC_TIMESTAMP(3)")
                        .param("buyer", buyer).param("shop", shop).param("token", token.toString()).query(Long.class).single();
                if (owned != 1) throw ConversationException.conflict("本次AI回复已过期，请刷新会话状态");
            });
            return result;
        } finally {
            transaction.executeWithoutResult(state -> jdbc.sql("UPDATE support_chat_scope SET legacy_processing_token=NULL,legacy_expires_at=NULL WHERE buyer_id=:buyer AND tenant_id=:shop AND legacy_processing_token=:token")
                    .param("buyer", buyer).param("shop", shop).param("token", token.toString()).update());
        }
    }
}
