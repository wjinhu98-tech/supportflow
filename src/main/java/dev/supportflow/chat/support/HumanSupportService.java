package dev.supportflow.chat.support;

import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.merchant.MerchantAccessService;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.supportflow.chat.support.HumanSupportModels.*;

/** Mutations lock scope, conversation, authorization and then session. No AI or order tools are dependencies. */
@Service
public class HumanSupportService {
    static final int PAGE_SIZE = 30;
    private static final String SESSION_COLUMNS = "s.id,s.public_id,s.conversation_id,s.status,s.assigned_staff_id,s.created_at,s.updated_at,s.closed_at";
    private static final String MESSAGE_COLUMNS = "id,public_id,client_message_id,role,text,created_at";
    private static final RowMapper<StoredSession> SESSION_MAPPER = (row, ignored) -> new StoredSession(
            row.getLong("id"), UUID.fromString(row.getString("public_id")), row.getLong("conversation_id"),
            Status.valueOf(row.getString("status")), row.getObject("assigned_staff_id", Long.class),
            instant(row, "created_at"), instant(row, "updated_at"), instant(row, "closed_at"));
    private static final RowMapper<MessageView> MESSAGE_MAPPER = (row, ignored) -> new MessageView(
            UUID.fromString(row.getString("public_id")), UUID.fromString(row.getString("client_message_id")),
            Role.valueOf(row.getString("role")), row.getString("text"), instant(row, "created_at"));
    private final JdbcClient jdbc;
    private final MerchantAccessService merchants;
    private final TransactionTemplate transaction;

    public HumanSupportService(JdbcClient jdbc, MerchantAccessService merchants, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.merchants = merchants;
        transaction = new TransactionTemplate(manager);
        // Parent discovery can precede a mutex wait; reads after admission must see the winning commit.
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    @Transactional(readOnly = true)
    public Snapshot buyerSnapshot(CustomerPrincipal principal, long shopId, UUID conversationId) {
        long conversation = buyerConversation(principal, shopId, conversationId, false);
        StoredSession session = latestSession(conversation);
        return buyerSnapshot(conversation, session);
    }

    @Transactional(readOnly = true)
    public MessagePage buyerMessages(CustomerPrincipal principal, long shopId, UUID conversationId, UUID beforeId) {
        return messages(buyerConversation(principal, shopId, conversationId, false), null, beforeId);
    }

    public Snapshot start(CustomerPrincipal principal, long shopId, UUID conversationId, StartRequest request) {
        if (request == null || request.requestId() == null) throw HumanSupportException.invalid();
        return transaction.execute(state -> {
            long conversation = buyerConversation(principal, shopId, conversationId, true);
            var existing = jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM support_human_session s WHERE conversation_id=:conversation AND request_id=:request")
                    .param("conversation", conversation).param("request", request.requestId().toString())
                    .query(SESSION_MAPPER).optional();
            if (existing.isPresent()) return buyerSnapshot(conversation, existing.get());
            long buyer = principal.getIdentity().buyerId();
            if (HumanSupportScope.active(jdbc, buyer, shopId)) {
                throw HumanSupportException.conflict("已有人工会话，请刷新会话状态");
            }
            // Terminally fence abandoned AI turns under the same scope lock used by succeed/resume.
            jdbc.sql("""
                    UPDATE support_chat_turn t JOIN support_chat_conversation c ON c.id=t.conversation_id
                    SET t.status='FAILED',t.updated_at=UTC_TIMESTAMP(3)
                    WHERE c.buyer_id=:buyer AND c.tenant_id=:shop AND c.deleted_at IS NULL
                      AND t.status='PROCESSING' AND t.expires_at<=UTC_TIMESTAMP(3)
                    """).param("buyer", buyer).param("shop", shopId).update();
            if (HumanSupportScope.legacyProcessing(jdbc, buyer, shopId) || jdbc.sql("""
                    SELECT COUNT(*) FROM support_chat_turn t JOIN support_chat_conversation c ON c.id=t.conversation_id
                    WHERE c.buyer_id=:buyer AND c.tenant_id=:shop AND c.deleted_at IS NULL AND t.status='PROCESSING'
                    """).param("buyer", buyer).param("shop", shopId).query(Long.class).single() != 0) {
                throw HumanSupportException.conflict("AI正在回复，请稍后再转人工");
            }
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO support_human_session(public_id,request_id,conversation_id,tenant_id,buyer_id)
                    VALUES(:id,:request,:conversation,:shop,:buyer)
                    """).param("id", id.toString()).param("request", request.requestId().toString())
                    .param("conversation", conversation).param("shop", shopId)
                    .param("buyer", principal.getIdentity().buyerId()).update();
            // Keep history immutable; a watermark clears only future structured AI context.
            jdbc.sql("""
                    UPDATE support_chat_conversation SET human_context_reset_after_message_id=
                      COALESCE((SELECT MAX(message.id) FROM support_chat_message message WHERE message.conversation_id=:conversation),0),
                      updated_at=UTC_TIMESTAMP(3) WHERE id=:conversation
                    """).param("conversation", conversation).update();
            return buyerSnapshot(conversation, session(conversation, id, false));
        });
    }

    public MessageView buyerSend(CustomerPrincipal principal, long shopId, UUID conversationId, UUID sessionId, MessageRequest request) {
        validate(request);
        return transaction.execute(state -> {
            long conversation = buyerConversation(principal, shopId, conversationId, true);
            StoredSession session = session(conversation, sessionId, true);
            return append(session, Role.BUYER, null, request);
        });
    }

    public Snapshot buyerClose(CustomerPrincipal principal, long shopId, UUID conversationId, UUID sessionId) {
        return transaction.execute(state -> {
            long conversation = buyerConversation(principal, shopId, conversationId, true);
            StoredSession session = session(conversation, sessionId, true);
            close(session);
            return buyerSnapshot(conversation, session(conversation, sessionId, false));
        });
    }

    @Transactional(readOnly = true)
    public SessionPage queue(CustomerPrincipal principal, long shopId, Status status, UUID beforeId) {
        long staff = merchants.requireChatShop(principal, shopId).staffId();
        Status filter = status == null ? Status.WAITING : status;
        String scope = " s.tenant_id=:shop AND s.status=:status AND c.deleted_at IS NULL AND "
                + (filter == Status.WAITING ? "s.assigned_staff_id IS NULL" : "s.assigned_staff_id=:staff");
        Map<String, Object> params = new java.util.HashMap<>(Map.of("shop", shopId, "status", filter.name(), "staff", staff, "limit", PAGE_SIZE + 1));
        if (beforeId != null) {
            long before = jdbc.sql("SELECT s.id FROM support_human_session s JOIN support_chat_conversation c ON c.id=s.conversation_id WHERE "
                            + scope + " AND s.public_id=:before")
                    .params(params).param("before", beforeId.toString()).query(Long.class).optional()
                    .orElseThrow(HumanSupportException::notFound);
            params.put("before", before);
        }
        var rows = jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM support_human_session s JOIN support_chat_conversation c ON c.id=s.conversation_id WHERE "
                        + scope + (beforeId == null ? "" : " AND s.id<:before") + " ORDER BY s.id DESC LIMIT :limit")
                .params(params).query(SESSION_MAPPER).list();
        var page = rows.stream().limit(PAGE_SIZE).map(row -> row.view(staff)).toList();
        return new SessionPage(page, rows.size() > PAGE_SIZE ? page.getLast().id() : null);
    }

    @Transactional(readOnly = true)
    public Snapshot merchantSnapshot(CustomerPrincipal principal, long shopId, UUID sessionId, UUID beforeId) {
        long staff = merchants.requireChatShop(principal, shopId).staffId();
        long conversation = merchantConversation(shopId, sessionId, false);
        StoredSession session = session(conversation, sessionId, false);
        requireVisible(session, staff);
        return merchantSnapshot(session, staff, beforeId);
    }

    public Snapshot claim(CustomerPrincipal principal, long shopId, UUID sessionId) {
        merchants.requireChatShop(principal, shopId);
        return transaction.execute(state -> {
            long conversation = merchantConversation(shopId, sessionId, true);
            long staff = merchants.lockChatShop(principal, shopId).staffId();
            StoredSession session = session(conversation, sessionId, true);
            if (session.status() == Status.CLOSED) throw HumanSupportException.conflict("该人工会话已结束");
            if (session.staff() != null && session.staff() != staff) throw HumanSupportException.conflict("该会话已由其他客服接待");
            if (session.status() == Status.WAITING) {
                jdbc.sql("UPDATE support_human_session SET status='ACTIVE',assigned_staff_id=:staff,updated_at=UTC_TIMESTAMP(3) WHERE id=:id")
                        .param("staff", staff).param("id", session.storageId()).update();
            }
            return merchantSnapshot(session(conversation, sessionId, false), staff, null);
        });
    }

    public MessageView merchantSend(CustomerPrincipal principal, long shopId, UUID sessionId, MessageRequest request) {
        validate(request);
        merchants.requireChatShop(principal, shopId);
        return transaction.execute(state -> {
            long conversation = merchantConversation(shopId, sessionId, true);
            long staff = merchants.lockChatShop(principal, shopId).staffId();
            StoredSession session = session(conversation, sessionId, true);
            requireAssigned(session, staff);
            return append(session, Role.STAFF, staff, request);
        });
    }

    public Snapshot merchantClose(CustomerPrincipal principal, long shopId, UUID sessionId) {
        merchants.requireChatShop(principal, shopId);
        return transaction.execute(state -> {
            long conversation = merchantConversation(shopId, sessionId, true);
            long staff = merchants.lockChatShop(principal, shopId).staffId();
            StoredSession session = session(conversation, sessionId, true);
            requireAssigned(session, staff);
            close(session);
            return merchantSnapshot(session(conversation, sessionId, false), staff, null);
        });
    }

    private MessageView append(StoredSession session, Role role, Long staff, MessageRequest request) {
        var existing = jdbc.sql("SELECT " + MESSAGE_COLUMNS + ",sender_staff_id FROM support_human_message WHERE session_id=:session AND client_message_id=:client")
                .param("session", session.storageId()).param("client", request.clientMessageId().toString())
                .query((row, ignored) -> new StoredMessage(MESSAGE_MAPPER.mapRow(row, ignored), row.getObject("sender_staff_id", Long.class)))
                .optional();
        if (existing.isPresent()) {
            StoredMessage saved = existing.get();
            if (saved.view().role() != role || !java.util.Objects.equals(saved.staff(), staff)
                    || !saved.view().text().equals(request.text())) throw HumanSupportException.conflict("该消息标识已用于其他内容");
            return saved.view(); // Lost responses remain safely retryable after closure.
        }
        if (session.status() == Status.CLOSED) throw HumanSupportException.conflict("人工会话已结束，请重新转人工或使用AI客服");
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO support_human_message(public_id,client_message_id,conversation_id,session_id,role,sender_staff_id,text)
                VALUES(:id,:client,:conversation,:session,:role,:staff,:text)
                """).param("id", id.toString()).param("client", request.clientMessageId().toString())
                .param("conversation", session.conversation()).param("session", session.storageId()).param("role", role.name())
                .param("staff", staff, Types.BIGINT).param("text", request.text()).update();
        jdbc.sql("UPDATE support_human_session SET updated_at=UTC_TIMESTAMP(3) WHERE id=:id").param("id", session.storageId()).update();
        jdbc.sql("UPDATE support_chat_conversation SET updated_at=UTC_TIMESTAMP(3) WHERE id=:id").param("id", session.conversation()).update();
        return jdbc.sql("SELECT " + MESSAGE_COLUMNS + " FROM support_human_message WHERE public_id=:id")
                .param("id", id.toString()).query(MESSAGE_MAPPER).single();
    }

    private void close(StoredSession session) {
        if (session.status() == Status.CLOSED) return;
        jdbc.sql("UPDATE support_human_session SET status='CLOSED',active_slot=NULL,closed_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE id=:id")
                .param("id", session.storageId()).update();
        jdbc.sql("UPDATE support_chat_conversation SET updated_at=UTC_TIMESTAMP(3) WHERE id=:id").param("id", session.conversation()).update();
    }

    private long buyerConversation(CustomerPrincipal principal, long shop, UUID publicId, boolean lock) {
        if (principal == null || principal.getAccountType() != AccountType.BUYER) throw new AccessDeniedException("Buyer account required");
        if (publicId == null) throw HumanSupportException.notFound();
        if (lock) HumanSupportScope.lock(jdbc, principal.getIdentity().buyerId(), shop);
        return jdbc.sql("SELECT id FROM support_chat_conversation WHERE public_id=:id AND buyer_id=:buyer AND tenant_id=:shop AND deleted_at IS NULL"
                        + (lock ? " FOR UPDATE" : ""))
                .param("id", publicId.toString()).param("buyer", principal.getIdentity().buyerId()).param("shop", shop)
                .query(Long.class).optional().orElseThrow(HumanSupportException::notFound);
    }

    private long merchantConversation(long shop, UUID session, boolean lock) {
        // Discover the parent without locking a child first. Recheck the child after the parent lock.
        long conversation = jdbc.sql("SELECT conversation_id FROM support_human_session WHERE public_id=:id AND tenant_id=:shop")
                .param("id", session.toString()).param("shop", shop).query(Long.class).optional().orElseThrow(HumanSupportException::notFound);
        if (lock) {
            long buyer = jdbc.sql("SELECT buyer_id FROM support_chat_conversation WHERE id=:id AND tenant_id=:shop AND deleted_at IS NULL")
                    .param("id", conversation).param("shop", shop).query(Long.class).optional().orElseThrow(HumanSupportException::notFound);
            HumanSupportScope.lock(jdbc, buyer, shop);
        }
        return jdbc.sql("SELECT id FROM support_chat_conversation WHERE id=:id AND tenant_id=:shop AND deleted_at IS NULL" + (lock ? " FOR UPDATE" : ""))
                .param("id", conversation).param("shop", shop).query(Long.class).optional().orElseThrow(HumanSupportException::notFound);
    }

    private StoredSession session(long conversation, UUID id, boolean lock) {
        return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM support_human_session s WHERE s.conversation_id=:conversation AND s.public_id=:id" + (lock ? " FOR UPDATE" : ""))
                .param("conversation", conversation).param("id", id.toString()).query(SESSION_MAPPER).optional().orElseThrow(HumanSupportException::notFound);
    }
    private StoredSession latestSession(long conversation) {
        return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM support_human_session s WHERE s.conversation_id=:conversation ORDER BY s.id DESC LIMIT 1")
                .param("conversation", conversation).query(SESSION_MAPPER).optional().orElse(null);
    }
    private Snapshot buyerSnapshot(long conversation, StoredSession session) {
        MessagePage page = messages(conversation, null, null);
        return new Snapshot(session == null ? null : session.view(null), page.messages(), page.nextBeforeId());
    }
    private Snapshot merchantSnapshot(StoredSession session, long staff, UUID beforeId) {
        // Waiting queue metadata is visible; transcript requires successful assignment.
        MessagePage page = session.staff() == null ? new MessagePage(List.of(), null) : messages(session.conversation(), session.storageId(), beforeId);
        return new Snapshot(session.view(staff), page.messages(), page.nextBeforeId());
    }
    private MessagePage messages(long conversation, Long session, UUID beforeId) {
        String scope = "conversation_id=:conversation" + (session == null ? "" : " AND session_id=:session");
        Map<String, Object> params = new java.util.HashMap<>(Map.of("conversation", conversation, "limit", PAGE_SIZE + 1));
        if (session != null) params.put("session", session);
        if (beforeId != null) {
            long before = jdbc.sql("SELECT id FROM support_human_message WHERE " + scope + " AND public_id=:before")
                    .params(params).param("before", beforeId.toString()).query(Long.class).optional().orElseThrow(HumanSupportException::notFound);
            params.put("before", before);
        }
        var rows = jdbc.sql("SELECT " + MESSAGE_COLUMNS + " FROM support_human_message WHERE " + scope
                        + (beforeId == null ? "" : " AND id<:before") + " ORDER BY id DESC LIMIT :limit")
                .params(params).query(MESSAGE_MAPPER).list();
        var page = new ArrayList<>(rows.stream().limit(PAGE_SIZE).toList());
        Collections.reverse(page);
        return new MessagePage(page, rows.size() > PAGE_SIZE ? page.getFirst().id() : null);
    }
    private static void requireVisible(StoredSession session, long staff) {
        if (session.staff() != null && session.staff() != staff) throw HumanSupportException.notFound();
    }
    private static void requireAssigned(StoredSession session, long staff) {
        if (session.staff() == null || session.staff() != staff) throw HumanSupportException.conflict("请先接待此会话；仅接待客服可以回复或结束");
    }
    static void validate(MessageRequest request) {
        if (request == null || request.clientMessageId() == null || request.text() == null || request.text().isBlank()
                || request.text().length() > 2000 || request.text().matches("(?s).*<\\s*/?\\s*[A-Za-z][^>]*>.*")
                || request.text().codePoints().anyMatch(value -> Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t')) {
            throw HumanSupportException.invalid();
        }
    }
    private static Instant instant(ResultSet row, String column) throws SQLException {
        LocalDateTime value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    private record StoredMessage(MessageView view, Long staff) { }
    private record StoredSession(long storageId, UUID id, long conversation, Status status, Long staff,
                                 Instant created, Instant updated, Instant closed) {
        SessionView view(Long viewer) {
            return new SessionView(id, status, staff != null, staff != null && staff.equals(viewer), created, updated, closed);
        }
    }
}
