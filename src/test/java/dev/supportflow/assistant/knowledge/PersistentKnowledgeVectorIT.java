package dev.supportflow.assistant.knowledge;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Actual MySQL bytes and restart reuse; synthetic normalized vectors, no remote or daily database. */
@Testcontainers
class PersistentKnowledgeVectorIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName("vectors_test").withUsername("owner").withPassword(UUID.randomUUID().toString());
    static JdbcTemplate jdbc;
    static JdbcKnowledgeVectorStore store;
    static final RagProperties PROPS = new RagProperties(true,"unused",0.78,0.025,32,8000,false,true);
    @BeforeAll static void setup() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())
                .cleanDisabled(true).load().migrate();
        jdbc=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()));
        store=new JdbcKnowledgeVectorStore(JdbcClient.create(jdbc));
    }
    static float[] vector(){var v=new float[512];v[0]=1;return v;}
    static KnowledgeDocument doc(String version,String body){return new KnowledgeDocument("kb-"+version,version,"水杯清洗",List.of("清洗"),body);}
    @Test void restartsReuseVectorsButNeverReuseChangedOrOtherShopPublications() {
        AtomicInteger sourceCalls=new AtomicInteger();
        KnowledgeEmbedding embedding=(text,query)->{if(!query)sourceCalls.incrementAndGet();return vector();};
        var first=doc("restart-v1","水杯可以使用温水清洗。");
        var search=new RagKnowledgeSearch(PROPS,embedding,new SimpleMeterRegistry(),store);
        search.search(100,List.of(first),"水杯怎么清洗");assertThat(sourceCalls).hasValue(1);
        var restarted=new RagKnowledgeSearch(PROPS,embedding,new SimpleMeterRegistry(),store);
        restarted.search(100,List.of(first),"水杯怎么清洗");assertThat(sourceCalls).hasValue(1);
        assertThat(restarted.search(100,List.of(),"水杯怎么清洗")).isEmpty();
        restarted.search(101,List.of(first),"水杯怎么清洗");assertThat(sourceCalls).hasValue(2);
        restarted.search(100,List.of(doc("restart-v1","水杯禁止高温清洗。")),"水杯怎么清洗");assertThat(sourceCalls).hasValue(3);
        restarted.search(100,List.of(doc("restart-v2","水杯禁止高温清洗。")),"水杯怎么清洗");assertThat(sourceCalls).hasValue(4);
    }
    @Test void statusDoesNotEmbedAndWarmupCountsOnlyPersistedCurrentSources() {
        AtomicInteger calls=new AtomicInteger();
        var search=new RagKnowledgeSearch(PROPS,(text,query)->{calls.incrementAndGet();return vector();},new SimpleMeterRegistry(),store);
        var source=doc("warmup-v1","水杯清洗说明。".repeat(60));
        assertThat(search.index(102,List.of(source),false).status()).isEqualTo("PARTIAL");assertThat(calls).hasValue(0);
        var warmed=search.index(102,List.of(source),true);
        assertThat(warmed.status()).isEqualTo("READY");assertThat(warmed.indexedChunks()).isEqualTo(warmed.expectedChunks());
        int prior=calls.get();search.index(102,List.of(source),true);assertThat(calls).hasValue(prior);
        assertThat(search.index(102,List.of(),false).status()).isEqualTo("EMPTY");
    }
    @Test void immutableKeysPreserveFirstVectorAndRejectInvalidShapes() {
        var key=new KnowledgeVectorStore.SourceKey(103,"kb-store","v1","a".repeat(64));
        store.save(key,0,vector());var alternate=new float[512];alternate[1]=1;store.save(key,0,alternate);
        assertThat(store.load(key).get(0)).containsExactly(vector());
        assertThatThrownBy(()->store.save(key,1,new float[]{1})).isInstanceOf(IllegalArgumentException.class);
        var invalid=vector();invalid[2]=Float.NaN;
        assertThatThrownBy(()->store.save(key,1,invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.load(key)).hasSize(1);
    }
    @Test void unavailableStoreFallsBackWithoutInventingReadiness() {
        KnowledgeVectorStore unavailable=new KnowledgeVectorStore(){
            public java.util.Map<Integer,float[]> load(SourceKey key){throw new IllegalStateException("not exposed");}
            public void save(SourceKey key,int chunk,float[] v){throw new IllegalStateException("not exposed");}
        };
        var search=new RagKnowledgeSearch(PROPS,(text,query)->vector(),new SimpleMeterRegistry(),unavailable);
        var source=doc("failure","水杯可以温水清洗。");
        assertThat(search.search(104,List.of(source),"水杯清洗")).isNotEmpty();
        assertThat(search.index(104,List.of(source),true).status()).isEqualTo("UNAVAILABLE");
    }
}
