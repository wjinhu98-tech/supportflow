package dev.supportflow.merchant.knowledge;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class KnowledgeImportRepository {
    private final JdbcClient jdbc;

    public KnowledgeImportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    Optional<String> find(long shopId, String hash) {
        return jdbc.sql("SELECT article_id FROM support_knowledge_import WHERE tenant_id=:shop AND content_hash=:hash")
                .param("shop", shopId).param("hash", hash).query(String.class).optional();
    }

    void append(long shopId, String hash, String articleId, String actor, long staffId) {
        int inserted = jdbc.sql("""
                INSERT INTO support_knowledge_import(tenant_id,content_hash,article_id,actor,staff_id,normalization_version,imported_at)
                VALUES(:shop,:hash,:id,:actor,:staff,:normalization,UTC_TIMESTAMP(3))
                """).param("shop", shopId).param("hash", hash).param("id", articleId).param("actor", actor)
                .param("staff", staffId).param("normalization", KnowledgeImportFileParser.NORMALIZATION_VERSION).update();
        if (inserted != 1) throw new IllegalStateException("Knowledge import identity was not persisted");
    }
}
