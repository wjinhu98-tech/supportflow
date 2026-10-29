package dev.supportflow.merchant.knowledge;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class KnowledgeRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public KnowledgeRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void create(long shopId, String id, KnowledgeDraft draft) {
        jdbc.sql("""
                INSERT INTO support_knowledge_article (id, tenant_id, title, keywords_json, content)
                VALUES (:id, :shop, :title, :keywords, :content)
                """).param("id", id).param("shop", shopId).param("title", draft.title())
                .param("keywords", json.writeValueAsString(draft.keywords())).param("content", draft.content()).update();
    }

    /** Stable-ID import admission; an existing draft keeps every field and its first timestamps unchanged. */
    void claimImportDraft(long shopId, String id, KnowledgeDraft draft) {
        jdbc.sql("""
                INSERT INTO support_knowledge_article (id, tenant_id, title, keywords_json, content)
                VALUES (:id, :shop, :title, :keywords, :content)
                ON DUPLICATE KEY UPDATE id=id
                """).param("id", id).param("shop", shopId).param("title", draft.title())
                .param("keywords", json.writeValueAsString(draft.keywords())).param("content", draft.content()).update();
    }

    Optional<StoredKnowledge> find(long shopId, String id, boolean lock) {
        // Only the mutable article is locked: revision/audit tables remain SELECT+INSERT-only at runtime.
        return jdbc.sql("SELECT * FROM support_knowledge_article WHERE tenant_id=:shop AND id=:id"
                        + (lock ? " FOR UPDATE" : ""))
                .param("shop", shopId).param("id", id).query(this::stored).optional();
    }

    Optional<Long> sequence(long shopId, String id) {
        return jdbc.sql("SELECT sequence_id FROM support_knowledge_article WHERE tenant_id=:shop AND id=:id")
                .param("shop", shopId).param("id", id).query(Long.class).optional();
    }

    List<StoredKnowledge> list(long shopId, long beforeSequence) {
        return jdbc.sql("""
                SELECT * FROM support_knowledge_article
                WHERE tenant_id=:shop AND sequence_id<:before ORDER BY sequence_id DESC LIMIT :count
                """).param("shop", shopId).param("before", beforeSequence)
                .param("count", KnowledgeLimits.PAGE_SIZE + 1).query(this::stored).list();
    }

    KnowledgeArticle view(StoredKnowledge stored) {
        KnowledgeSnapshot snapshot = stored.publishedRevision() == null ? null
                : jdbc.sql("""
                SELECT * FROM support_knowledge_revision
                WHERE tenant_id=:shop AND article_id=:id AND revision=:revision
                """).param("shop", stored.shopId()).param("id", stored.id())
                .param("revision", stored.publishedRevision()).query((row, ignored) -> new KnowledgeSnapshot(
                        row.getInt("revision"), row.getString("title"), keywords(row), row.getString("content"),
                        instant(row, "published_at"))).optional().orElseThrow(() ->
                        new IllegalStateException("Published knowledge snapshot is missing"));
        return new KnowledgeArticle(stored.id(), stored.shopId(), stored.draft().title(), stored.draft().keywords(),
                stored.draft().content(), stored.status(), stored.version(), stored.publishedRevision(), snapshot,
                stored.createdAt(), stored.updatedAt());
    }

    boolean save(StoredKnowledge stored, KnowledgeDraft draft) {
        return jdbc.sql("""
                UPDATE support_knowledge_article SET title=:title, keywords_json=:keywords, content=:content,
                    version=version+1, updated_at=UTC_TIMESTAMP(3)
                WHERE tenant_id=:shop AND id=:id AND version=:version
                """).param("title", draft.title()).param("keywords", json.writeValueAsString(draft.keywords()))
                .param("content", draft.content()).param("shop", stored.shopId()).param("id", stored.id())
                .param("version", stored.version()).update() == 1;
    }

    Set<Integer> occupiedSlots(long shopId) {
        return new HashSet<>(jdbc.sql("""
                SELECT publication_slot FROM support_knowledge_article
                WHERE tenant_id=:shop AND publication_slot IS NOT NULL LIMIT :maximum
                """).param("shop", shopId).param("maximum", KnowledgeLimits.MAX_PUBLISHED + 1)
                .query(Integer.class).list());
    }

    boolean publish(StoredKnowledge stored, int slot) {
        return jdbc.sql("""
                UPDATE support_knowledge_article SET status='PUBLISHED', version=version+1,
                  last_revision=:revision, published_revision=:revision,
                  publication_slot=:slot, updated_at=UTC_TIMESTAMP(3)
                WHERE tenant_id=:shop AND id=:id AND version=:version
                """).param("revision", stored.lastRevision() + 1).param("slot", slot)
                .param("shop", stored.shopId()).param("id", stored.id())
                .param("version", stored.version()).update() == 1;
    }

    void revision(StoredKnowledge stored, int revision, String actor, long staffId) {
        jdbc.sql("""
                INSERT INTO support_knowledge_revision
                  (article_id, revision, tenant_id, title, keywords_json, content, actor, staff_id)
                VALUES (:id, :revision, :shop, :title, :keywords, :content, :actor, :staff)
                """).param("id", stored.id()).param("revision", revision).param("shop", stored.shopId())
                .param("title", stored.draft().title()).param("keywords", json.writeValueAsString(stored.draft().keywords()))
                .param("content", stored.draft().content()).param("actor", actor).param("staff", staffId).update();
    }

    boolean offline(StoredKnowledge stored) {
        return jdbc.sql("""
                UPDATE support_knowledge_article SET status='OFFLINE', version=version+1,
                  published_revision=NULL, publication_slot=NULL, updated_at=UTC_TIMESTAMP(3)
                WHERE tenant_id=:shop AND id=:id AND version=:version AND status='PUBLISHED'
                """).param("shop", stored.shopId()).param("id", stored.id())
                .param("version", stored.version()).update() == 1;
    }

    void audit(StoredKnowledge stored, KnowledgeAction action, String actor, long staffId) {
        jdbc.sql("""
                INSERT INTO support_knowledge_audit
                  (article_id, tenant_id, action, actor, staff_id, version, published_revision, title, keywords_json, content)
                VALUES (:id, :shop, :action, :actor, :staff, :version, :revision, :title, :keywords, :content)
                """).param("id", stored.id()).param("shop", stored.shopId()).param("action", action.name())
                .param("actor", actor).param("staff", staffId).param("version", stored.version())
                .param("revision", stored.publishedRevision(), Types.INTEGER).param("title", stored.draft().title())
                .param("keywords", json.writeValueAsString(stored.draft().keywords()))
                .param("content", stored.draft().content()).update();
    }

    List<KnowledgeAudit> audit(long shopId, String id, long beforeId) {
        return jdbc.sql("""
                SELECT * FROM support_knowledge_audit
                WHERE tenant_id=:shop AND article_id=:id AND id<:before ORDER BY id DESC LIMIT :count
                """).param("shop", shopId).param("id", id).param("before", beforeId)
                .param("count", KnowledgeLimits.PAGE_SIZE + 1)
                .query((row, ignored) -> new KnowledgeAudit(Long.toString(row.getLong("id")),
                        KnowledgeAction.valueOf(row.getString("action")), row.getString("actor"),
                        row.getInt("version"), row.getObject("published_revision", Integer.class),
                        row.getString("title"), keywords(row), row.getString("content"), instant(row, "created_at")))
                .list();
    }

    /** Every retrieval re-reads current publication state, in the requested shop, before any ranking. */
    public List<PublishedKnowledge> publishedForShop(long shopId) {
        return jdbc.sql("""
                SELECT a.id, r.revision, r.title, r.keywords_json, r.content, r.published_at
                FROM support_knowledge_article a
                LEFT JOIN support_knowledge_revision r ON r.article_id=a.id
                  AND r.revision=a.published_revision AND r.tenant_id=a.tenant_id
                WHERE a.tenant_id=:shop AND a.status='PUBLISHED'
                ORDER BY a.id LIMIT :maximum
                """).param("shop", shopId).param("maximum", KnowledgeLimits.MAX_PUBLISHED + 1)
                .query((row, ignored) -> {
                    if (row.getObject("revision") == null) throw new IllegalStateException("Published source missing");
                    var draft = new KnowledgeDraft(row.getString("title"), keywords(row), row.getString("content"));
                    return new PublishedKnowledge(row.getString("id"), row.getInt("revision"), draft.title(),
                            draft.keywords(), draft.content(), instant(row, "published_at"));
                }).list();
    }

    private StoredKnowledge stored(ResultSet row, int ignored) throws SQLException {
        return new StoredKnowledge(row.getString("id"), row.getLong("tenant_id"), row.getLong("sequence_id"),
                new KnowledgeDraft(row.getString("title"), keywords(row), row.getString("content")),
                KnowledgeStatus.valueOf(row.getString("status")), row.getInt("version"), row.getInt("last_revision"),
                row.getObject("published_revision", Integer.class), row.getObject("publication_slot", Integer.class),
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private List<String> keywords(ResultSet row) throws SQLException {
        return List.copyOf(Arrays.asList(json.readValue(row.getString("keywords_json"), String[].class)));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
}
