package dev.supportflow.chat.support;

import dev.supportflow.chat.conversation.ConversationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Shared lock order: scope, conversation, authorization, human session. Caller owns a transaction. */
public final class HumanSupportScope {
    private HumanSupportScope() { }
    public static void lock(JdbcClient jdbc, long buyer, long shop) {
        jdbc.sql("INSERT INTO support_chat_scope(buyer_id,tenant_id) VALUES(:buyer,:shop) ON DUPLICATE KEY UPDATE buyer_id=buyer_id")
                .param("buyer", buyer).param("shop", shop).update();
        jdbc.sql("SELECT buyer_id FROM support_chat_scope WHERE buyer_id=:buyer AND tenant_id=:shop FOR UPDATE")
                .param("buyer", buyer).param("shop", shop).query(Long.class).single();
    }
    public static boolean active(JdbcClient jdbc, long buyer, long shop) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM support_human_session s
                JOIN support_chat_conversation c ON c.id=s.conversation_id
                WHERE s.buyer_id=:buyer AND s.tenant_id=:shop AND s.active_slot=1 AND c.deleted_at IS NULL
                """).param("buyer", buyer).param("shop", shop).query(Long.class).single() != 0;
    }
    public static void requireAiAllowed(JdbcClient jdbc, long buyer, long shop) {
        if (active(jdbc, buyer, shop)) throw ConversationException.conflict("人工客服正在接待，请结束人工会话后再使用AI客服");
    }
    public static boolean legacyProcessing(JdbcClient jdbc, long buyer, long shop) {
        return jdbc.sql("SELECT COUNT(*) FROM support_chat_scope WHERE buyer_id=:buyer AND tenant_id=:shop AND legacy_processing_token IS NOT NULL AND legacy_expires_at>UTC_TIMESTAMP(3)")
                .param("buyer", buyer).param("shop", shop).query(Long.class).single() != 0;
    }
}
