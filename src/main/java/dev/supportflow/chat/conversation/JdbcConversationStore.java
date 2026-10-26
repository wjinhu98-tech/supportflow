package dev.supportflow.chat.conversation;

import dev.supportflow.chat.api.response.ChatResponse;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Owned, bounded keyset reads and short transactional turn state changes. No remote calls occur here. */
@Repository
public class JdbcConversationStore implements ConversationStore {
    private static final String CONVERSATION_COLUMNS = "id, public_id, tenant_id, created_at, updated_at";
    private static final String MESSAGE_COLUMNS = "id, public_id, role, text, response_json, created_at";
    private static final RowMapper<StoredConversation> CONVERSATION_MAPPER = (row, ignored) ->
            new StoredConversation(row.getLong("id"), new Conversation(
                    UUID.fromString(row.getString("public_id")), row.getLong("tenant_id"),
                    instant(row, "created_at"), instant(row, "updated_at")));

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public JdbcConversationStore(JdbcClient jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(10);
    }

    @Override
    public Conversation create(long buyerId, long shopId) {
        UUID id = UUID.randomUUID();
        return transaction.execute(status -> {
            jdbc.sql("""
                    INSERT INTO support_chat_conversation (public_id, buyer_id, tenant_id)
                    VALUES (:id, :buyer, :shop)
                    """).param("id", id.toString()).param("buyer", buyerId).param("shop", shopId).update();
            return owned(buyerId, shopId, id, false).value();
        });
    }

    @Override
    public ConversationPage list(long buyerId, long shopId, UUID beforeId) {
        Long before = beforeId == null ? null : owned(buyerId, shopId, beforeId, false).storageId();
        List<StoredConversation> rows = jdbc.sql("SELECT " + CONVERSATION_COLUMNS + """
                 FROM support_chat_conversation
                 WHERE buyer_id=:buyer AND tenant_id=:shop
                """ + (before == null ? "" : " AND id < :before") + " ORDER BY id DESC LIMIT :limit")
                .params(before == null
                        ? java.util.Map.of("buyer", buyerId, "shop", shopId,
                                "limit", ConversationLimits.CONVERSATIONS_PER_PAGE + 1)
                        : java.util.Map.of("buyer", buyerId, "shop", shopId, "before", before,
                                "limit", ConversationLimits.CONVERSATIONS_PER_PAGE + 1))
                .query(CONVERSATION_MAPPER).list();
        boolean more = rows.size() > ConversationLimits.CONVERSATIONS_PER_PAGE;
        List<Conversation> page = rows.stream().limit(ConversationLimits.CONVERSATIONS_PER_PAGE)
                .map(StoredConversation::value).toList();
        return new ConversationPage(page, more ? page.getLast().id() : null);
    }

    @Override
    public ConversationMessages messages(long buyerId, long shopId, UUID id, UUID beforeId) {
        StoredConversation conversation = owned(buyerId, shopId, id, false);
        Long before = beforeId == null ? null : jdbc.sql("""
                SELECT id FROM support_chat_message WHERE public_id=:id AND conversation_id=:conversation
                """).param("id", beforeId.toString()).param("conversation", conversation.storageId())
                .query(Long.class).optional().orElseThrow(ConversationException::notFound);
        List<ConversationMessage> rows = jdbc.sql("SELECT " + MESSAGE_COLUMNS + """
                 FROM support_chat_message WHERE conversation_id=:conversation
                """ + (before == null ? "" : " AND id < :before") + " ORDER BY id DESC LIMIT :limit")
                .params(before == null
                        ? java.util.Map.of("conversation", conversation.storageId(),
                                "limit", ConversationLimits.MESSAGES_PER_PAGE + 1)
                        : java.util.Map.of("conversation", conversation.storageId(), "before", before,
                                "limit", ConversationLimits.MESSAGES_PER_PAGE + 1))
                .query(this::message).list();
        boolean more = rows.size() > ConversationLimits.MESSAGES_PER_PAGE;
        List<ConversationMessage> page = new ArrayList<>(rows.stream()
                .limit(ConversationLimits.MESSAGES_PER_PAGE).toList());
        Collections.reverse(page);
        return new ConversationMessages(conversation.value(), page, more ? page.getFirst().id() : null);
    }

    @Override
    public Reservation reserve(long buyerId, long shopId, UUID id, ConversationMessageRequest request) {
        ReserveDecision decision = transaction.execute(status -> {
            StoredConversation conversation = owned(buyerId, shopId, id, true);
            // Lease expiry is terminal for that key. A new key may proceed, but no old side effect is replayed.
            jdbc.sql("""
                    UPDATE support_chat_turn SET status='FAILED', updated_at=UTC_TIMESTAMP(3)
                    WHERE conversation_id=:conversation AND status='PROCESSING' AND expires_at<=UTC_TIMESTAMP(3)
                    """).param("conversation", conversation.storageId()).update();
            List<StoredTurn> existing = turns(conversation.storageId(), request.clientMessageId());
            if (!existing.isEmpty()) {
                StoredTurn turn = existing.getFirst();
                if (!turn.requestHash().equals(hash(request))) {
                    return ReserveDecision.conflict("该消息标识已经用于其他内容，请创建一条新消息");
                }
                if (turn.status() == ConversationTurnStatus.SUCCEEDED) {
                    return ReserveDecision.ready(new Reservation(completed(conversation, turn.storageId())));
                }
                return ReserveDecision.conflict(turn.status() == ConversationTurnStatus.PROCESSING
                        ? "上一条消息仍在处理中，请稍后刷新记录，不要重复提交"
                        : "该消息未能完整处理，请先核实已有结果，再使用新消息继续咨询");
            }
            long active = jdbc.sql("""
                    SELECT COUNT(*) FROM support_chat_turn WHERE conversation_id=:conversation AND status='PROCESSING'
                    """).param("conversation", conversation.storageId()).query(Long.class).single();
            if (active != 0) return ReserveDecision.conflict("该会话仍有消息正在处理，请稍后再发送");
            UUID token = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO support_chat_turn
                      (conversation_id, client_message_id, request_hash, status, expires_at, processing_token)
                    VALUES (:conversation, :clientId, :hash, 'PROCESSING',
                            TIMESTAMPADD(SECOND, :leaseSeconds, UTC_TIMESTAMP(3)), :token)
                    """).param("conversation", conversation.storageId())
                    .param("clientId", request.clientMessageId().toString()).param("hash", hash(request))
                    .param("leaseSeconds", ConversationLimits.PROCESSING_SECONDS)
                    .param("token", token.toString()).update();
            return ReserveDecision.ready(Reservation.fresh(request.resetContext()
                    ? ConversationContextRef.none() : latestContext(conversation.storageId()), token));
        });
        // Return a conflict after committing lease expiry, rather than rolling that state change back.
        if (decision.conflictMessage() != null) throw ConversationException.conflict(decision.conflictMessage());
        return decision.reservation();
    }

    @Override
    public ConversationTurnResponse succeed(long buyerId, long shopId, UUID id,
                                            ConversationMessageRequest request, ChatResponse response) {
        return succeed(buyerId, shopId, id, request, response, null);
    }

    @Override
    public ConversationTurnResponse succeed(long buyerId, long shopId, UUID id,
                                            ConversationMessageRequest request, ChatResponse response, UUID processingToken) {
        String snapshot = json.writeValueAsString(response);
        if (response.reply() == null || response.reply().isBlank()
                || response.reply().length() > ConversationLimits.MAX_REPLY_LENGTH
                || snapshot.getBytes(StandardCharsets.UTF_8).length > ConversationLimits.MAX_SNAPSHOT_BYTES) {
            throw ConversationException.unavailable();
        }
        return transaction.execute(status -> {
            StoredConversation conversation = owned(buyerId, shopId, id, true);
            StoredTurn turn = turns(conversation.storageId(), request.clientMessageId()).stream()
                    .findFirst().orElseThrow(ConversationException::notFound);
            if (turn.status() != ConversationTurnStatus.PROCESSING || !turn.requestHash().equals(hash(request))) {
                throw ConversationException.conflict("该消息的处理状态已变化，请刷新记录并核实结果");
            }
            int changed = jdbc.sql("""
                    UPDATE support_chat_turn SET status='SUCCEEDED', updated_at=UTC_TIMESTAMP(3)
                    WHERE id=:id AND status='PROCESSING' AND expires_at>UTC_TIMESTAMP(3)
                      AND (processing_token=:token OR (processing_token IS NULL AND :token IS NULL))
                    """).param("id", turn.storageId())
                    .param("token", processingToken == null ? null : processingToken.toString(), Types.VARCHAR).update();
            if (changed != 1) throw ConversationException.conflict("本次回复处理超时，请先核实已有结果");
            insertMessage(conversation.storageId(), turn.storageId(), ConversationRole.USER, request.message(), null);
            insertMessage(conversation.storageId(), turn.storageId(), ConversationRole.ASSISTANT, response.reply(), snapshot);
            jdbc.sql("UPDATE support_chat_conversation SET updated_at=UTC_TIMESTAMP(3) WHERE id=:id")
                    .param("id", conversation.storageId()).update();
            return completed(owned(buyerId, shopId, id, false), turn.storageId());
        });
    }

    @Override
    public void fail(long buyerId, long shopId, UUID id, UUID clientMessageId) {
        fail(buyerId, shopId, id, clientMessageId, null);
    }

    @Override
    public void fail(long buyerId, long shopId, UUID id, UUID clientMessageId, UUID processingToken) {
        transaction.executeWithoutResult(status -> {
            StoredConversation conversation = owned(buyerId, shopId, id, true);
            jdbc.sql("""
                    UPDATE support_chat_turn SET status='FAILED', updated_at=UTC_TIMESTAMP(3)
                    WHERE conversation_id=:conversation AND client_message_id=:client AND status='PROCESSING'
                      AND (processing_token=:token OR (processing_token IS NULL AND :token IS NULL))
                    """).param("conversation", conversation.storageId())
                    .param("client", clientMessageId.toString())
                    .param("token", processingToken == null ? null : processingToken.toString(), Types.VARCHAR).update();
        });
    }

    @Override
    public boolean completed(long buyerId, long shopId, UUID id, UUID clientMessageId) {
        StoredConversation conversation = owned(buyerId, shopId, id, false);
        return turns(conversation.storageId(), clientMessageId).stream()
                .anyMatch(turn -> turn.status() == ConversationTurnStatus.SUCCEEDED);
    }

    @Override
    public boolean latest(long buyerId, long shopId, UUID id, UUID clientMessageId) {
        StoredConversation conversation = owned(buyerId, shopId, id, false);
        return jdbc.sql("""
                SELECT client_message_id FROM support_chat_turn
                WHERE conversation_id=:conversation ORDER BY id DESC LIMIT 1
                """).param("conversation", conversation.storageId()).query(String.class).optional()
                .map(clientMessageId.toString()::equals).orElse(false);
    }

    @Override
    public boolean recoverable(long buyerId, long shopId, UUID id, UUID clientMessageId, boolean reclaimProcessing) {
        StoredConversation conversation = owned(buyerId, shopId, id, false);
        return jdbc.sql("""
                SELECT COUNT(*) FROM support_chat_turn
                WHERE conversation_id=:conversation AND client_message_id=:client
                  AND (status='FAILED' OR (status='PROCESSING' AND (expires_at<=UTC_TIMESTAMP(3) OR :reclaim=1)))
                """).param("conversation", conversation.storageId()).param("client", clientMessageId.toString())
                .param("reclaim", reclaimProcessing ? 1 : 0).query(Long.class).single() == 1;
    }

    @Override
    public Reservation reserveResume(long buyerId, long shopId, UUID id, ConversationMessageRequest request,
                                     boolean reclaimProcessing) {
        return transaction.execute(status -> {
            StoredConversation conversation = owned(buyerId, shopId, id, true);
            StoredTurn turn = turns(conversation.storageId(), request.clientMessageId()).stream()
                    .findFirst().orElseThrow(ConversationException::notFound);
            if (!turn.requestHash().equals(hash(request))) {
                throw ConversationException.conflict("原消息内容不一致，无法恢复");
            }
            if (turn.status() == ConversationTurnStatus.SUCCEEDED) {
                return new Reservation(completed(conversation, turn.storageId()));
            }
            long latest = jdbc.sql("SELECT MAX(id) FROM support_chat_turn WHERE conversation_id=:conversation")
                    .param("conversation", conversation.storageId()).query(Long.class).single();
            if (turn.storageId() != latest) {
                throw ConversationException.conflict("这条咨询已有后续消息，请使用新消息继续查询");
            }
            UUID token = UUID.randomUUID();
            int changed = jdbc.sql("""
                    UPDATE support_chat_turn SET status='PROCESSING', processing_token=:token,
                      expires_at=TIMESTAMPADD(SECOND, :seconds, UTC_TIMESTAMP(3)), updated_at=UTC_TIMESTAMP(3)
                    WHERE id=:id AND (status='FAILED' OR
                      (status='PROCESSING' AND (expires_at<=UTC_TIMESTAMP(3) OR :reclaim=1)))
                    """).param("id", turn.storageId()).param("token", token.toString())
                    .param("seconds", ConversationLimits.PROCESSING_SECONDS)
                    .param("reclaim", reclaimProcessing ? 1 : 0).update();
            if (changed != 1) throw ConversationException.conflict("该消息仍在处理中，请稍后刷新记录");
            return Reservation.fresh(ConversationContextRef.none(), token);
        });
    }

    /** One successful snapshot pointer, read while the owned conversation row is locked. */
    private ConversationContextRef latestContext(long conversationId) {
        return jdbc.sql("""
                SELECT JSON_EXTRACT(message.response_json, '$.context') AS context_json
                FROM support_chat_message message
                JOIN support_chat_turn turn ON turn.id=message.turn_id AND turn.conversation_id=message.conversation_id
                WHERE message.conversation_id=:conversation AND message.role='ASSISTANT' AND turn.status='SUCCEEDED'
                ORDER BY message.id DESC LIMIT 1
                """).param("conversation", conversationId).query((row, ignored) -> {
                    String value = row.getString("context_json");
                    return value == null || value.equals("null") ? ConversationContextRef.none()
                            : json.readValue(value, ConversationContextRef.class);
                }).optional().orElseGet(ConversationContextRef::none);
    }

    private StoredConversation owned(long buyerId, long shopId, UUID id, boolean lock) {
        return jdbc.sql("SELECT " + CONVERSATION_COLUMNS + """
                 FROM support_chat_conversation WHERE public_id=:id AND buyer_id=:buyer AND tenant_id=:shop
                """ + (lock ? " FOR UPDATE" : ""))
                .param("id", id.toString()).param("buyer", buyerId).param("shop", shopId)
                .query(CONVERSATION_MAPPER).optional().orElseThrow(ConversationException::notFound);
    }

    private List<StoredTurn> turns(long conversationId, UUID clientMessageId) {
        return jdbc.sql("""
                SELECT id, request_hash, status FROM support_chat_turn
                WHERE conversation_id=:conversation AND client_message_id=:client
                """).param("conversation", conversationId).param("client", clientMessageId.toString())
                .query((row, ignored) -> new StoredTurn(row.getLong("id"), row.getString("request_hash"),
                        ConversationTurnStatus.valueOf(row.getString("status")))).list();
    }

    private void insertMessage(long conversation, long turn, ConversationRole role, String text, String response) {
        jdbc.sql("""
                INSERT INTO support_chat_message (public_id, conversation_id, turn_id, role, text, response_json)
                VALUES (:id, :conversation, :turn, :role, :text, :response)
                """).param("id", UUID.randomUUID().toString()).param("conversation", conversation)
                .param("turn", turn).param("role", role.name()).param("text", text)
                .param("response", response, Types.LONGVARCHAR).update();
    }

    private ConversationTurnResponse completed(StoredConversation conversation, long turnId) {
        List<ConversationMessage> messages = jdbc.sql("SELECT " + MESSAGE_COLUMNS + """
                 FROM support_chat_message WHERE conversation_id=:conversation AND turn_id=:turn ORDER BY id
                """).param("conversation", conversation.storageId()).param("turn", turnId)
                .query(this::message).list();
        if (messages.size() != 2 || messages.getFirst().role() != ConversationRole.USER
                || messages.getLast().role() != ConversationRole.ASSISTANT) {
            throw ConversationException.unavailable();
        }
        return new ConversationTurnResponse(conversation.value(), messages);
    }

    private ConversationMessage message(ResultSet row, int ignored) throws SQLException {
        String response = row.getString("response_json");
        return new ConversationMessage(UUID.fromString(row.getString("public_id")),
                ConversationRole.valueOf(row.getString("role")), row.getString("text"),
                instant(row, "created_at"), response == null ? null : json.readValue(response, ChatResponse.class));
    }

    private static Instant instant(ResultSet row, String field) throws SQLException {
        return row.getObject(field, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    static String hash(ConversationMessageRequest request) {
        String order = request.orderNo() == null ? "-" : request.orderNo().length() + ":" + request.orderNo();
        String payload = request.message().length() + ":" + request.message() + "|" + order
                + (request.resetContext() ? "|reset-context:true" : "");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    private record StoredConversation(long storageId, Conversation value) { }
    private record StoredTurn(long storageId, String requestHash, ConversationTurnStatus status) { }
    private record ReserveDecision(Reservation reservation, String conflictMessage) {
        static ReserveDecision ready(Reservation reservation) { return new ReserveDecision(reservation, null); }
        static ReserveDecision conflict(String message) { return new ReserveDecision(null, message); }
    }
}
