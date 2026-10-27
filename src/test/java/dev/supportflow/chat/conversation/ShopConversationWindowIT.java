package dev.supportflow.chat.conversation;

import dev.supportflow.assistant.runtime.AgentRuntimeProperties;
import dev.supportflow.assistant.runtime.model.AgentModelProfile;
import dev.supportflow.assistant.runtime.store.*;
import dev.supportflow.chat.api.response.ChatResponse;
import dev.supportflow.chat.application.model.ChatReplyType;
import dev.supportflow.deployment.DemoShopData;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real storage and races, no daily data, file deletion or paid models. */
@Testcontainers
class ShopConversationWindowIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName("shop_window_test").withUsername("root").withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%").withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true").withUrlParam("sslMode", "DISABLED")
            .withUrlParam("allowPublicKeyRetrieval", "true");
    private static final AtomicLong SHOPS = new AtomicLong(30000);
    private JdbcConversationStore store;
    private JdbcAgentRunStore runs;
    private JdbcClient jdbc;
    private long shop;

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true).load().migrate();
    }
    @BeforeEach void setUp() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var manager = new DataSourceTransactionManager(source);
        jdbc = JdbcClient.create(source);
        var json = new ObjectMapper();
        store = new JdbcConversationStore(jdbc, json, manager);
        runs = new JdbcAgentRunStore(jdbc, json, manager);
        shop = SHOPS.incrementAndGet();
    }

    @Test void concurrentOpeningAndReconnectingAlwaysReturnOneWindowForTheBuyerAndShop() {
        try (var workers = Executors.newFixedThreadPool(8)) {
            var calls = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(index -> CompletableFuture.supplyAsync(() -> store.openWindow(200, shop).id(), workers)).toList();
            var ids = calls.stream().map(CompletableFuture::join).distinct().toList();
            assertThat(ids).hasSize(1);
            assertThat(store.openWindow(200, shop).id()).isEqualTo(ids.getFirst());
            assertThat(store.openWindow(201, shop).id()).isNotEqualTo(ids.getFirst());
            assertThat(store.openWindow(200, shop + 100000).id()).isNotEqualTo(ids.getFirst());
        }
    }

    @Test void allLegacySessionsRemainReadableInOneWindowWithBoundedPagination() {
        for (int index = 0; index < 23; index++) {
            Conversation old = store.create(200, shop);
            reply(old, "旧咨询" + index);
        }
        Conversation other = store.create(201, shop);
        replyAs(201, other, "其他买家不可见");
        Conversation window = store.openWindow(200, shop);
        reply(window, "继续同一家店铺");
        var first = store.window(200, shop, null);
        assertThat(first.conversation().id()).isEqualTo(window.id());
        assertThat(first.messages()).hasSize(40);
        assertThat(first.nextBeforeId()).isNotNull();
        var older = store.window(200, shop, first.nextBeforeId());
        assertThat(older.messages()).hasSize(8);
        assertThat(older.nextBeforeId()).isNull();
        var texts = java.util.stream.Stream.concat(older.messages().stream(), first.messages().stream())
                .map(ConversationMessage::text).toList();
        assertThat(texts).contains("旧咨询0", "旧咨询22", "继续同一家店铺").doesNotContain("其他买家不可见");
        assertThat(store.messages(200, shop, window.id(), null).messages()).hasSize(2);
    }

    @Test void deletingOnlyThisShopWindowHidesLegacyHistoryAndKeepsBusinessAndOtherShopsUntouched() {
        var legacy = store.create(200, shop);
        reply(legacy, "旧会话也从窗口删除");
        var window = store.openWindow(200, shop);
        reply(window, "准备删除");
        var anotherShop = store.openWindow(200, shop + 100000);
        reply(anotherShop, "其他店铺保留");
        var anotherBuyer = store.openWindow(201, shop);
        replyAs(201, anotherBuyer, "其他买家保留");
        store.deleteWindow(200, shop, window.id());
        var empty = store.window(200, shop, null);
        assertThat(empty.conversation()).isNull();
        assertThat(empty.messages()).isEmpty();
        assertThat(store.list(200, shop, null).conversations()).isEmpty();
        assertThatThrownBy(() -> store.messages(200, shop, legacy.id(), null)).isInstanceOf(ConversationException.class);
        assertThat(store.window(200, shop + 100000, null).messages()).hasSize(2);
        assertThat(store.window(201, shop, null).messages()).hasSize(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM support_chat_message WHERE conversation_id IN (SELECT id FROM support_chat_conversation WHERE buyer_id=200 AND tenant_id=:shop)")
                .param("shop", shop).query(Long.class).single()).isEqualTo(4);
    }

    @Test void retryingAnOldDeleteCannotEraseANewWindowOrAnotherOwnersConversation() {
        var old = store.openWindow(200, shop);
        reply(old, "删除前");
        store.deleteWindow(200, shop, old.id());
        var fresh = store.openWindow(200, shop);
        reply(fresh, "删除后新咨询");
        store.deleteWindow(200, shop, old.id());
        assertThat(fresh.id()).isNotEqualTo(old.id());
        assertThat(store.window(200, shop, null).messages()).extracting(ConversationMessage::text).contains("删除后新咨询");
        assertThatThrownBy(() -> store.deleteWindow(201, shop, fresh.id())).isInstanceOf(ConversationException.class);
        assertThatThrownBy(() -> store.deleteWindow(200, shop + 1, fresh.id())).isInstanceOf(ConversationException.class);
    }

    @Test void inFlightProcessingBlocksDeletionAndDeletedRunsCannotResumeOrAppendLateResults() {
        var window = store.openWindow(200, shop);
        var request = new ConversationMessageRequest(UUID.randomUUID(), "查物流 SF10001", "SF10001");
        var reservation = store.reserve(200, shop, window.id(), request);
        var key = new AgentRunKey(window.id(), request.clientMessageId(), 200, shop);
        runs.createLoop(new AgentRunInput(key, request, request.message(), request.orderNo()), 8, 90,
                new AgentRuntimeProperties(8, 90).budget(new AgentModelProfile("rules", false)));
        assertThatThrownBy(() -> store.deleteWindow(200, shop, window.id())).isInstanceOf(ConversationException.class);
        store.fail(200, shop, window.id(), request.clientMessageId(), reservation.processingToken());
        store.deleteWindow(200, shop, window.id());
        assertThatThrownBy(() -> runs.find(key)).isInstanceOf(ConversationException.class);
        assertThatThrownBy(() -> runs.claim(key, true)).isInstanceOf(ConversationException.class);
        assertThatThrownBy(() -> store.reserve(200, shop, window.id(), request)).isInstanceOf(ConversationException.class);
        assertThatThrownBy(() -> store.succeed(200, shop, window.id(), request, response(), reservation.processingToken()))
                .isInstanceOf(ConversationException.class);
    }

    @Test void aForeignOrDeletedMessageCursorCannotReadAnotherWindow() {
        var own = store.openWindow(200, shop);
        reply(own, "本人咨询");
        var foreign = store.openWindow(201, shop);
        replyAs(201, foreign, "其他人");
        UUID cursor = store.window(201, shop, null).messages().getFirst().id();
        assertThatThrownBy(() -> store.window(200, shop, cursor)).isInstanceOf(ConversationException.class);
        var ownCursor = store.window(200, shop, null).messages().getFirst().id();
        store.deleteWindow(200, shop, own.id());
        store.openWindow(200, shop);
        assertThatThrownBy(() -> store.window(200, shop, ownCursor)).isInstanceOf(ConversationException.class);
    }

    @Test void demoShopSeedingIsExplicitIdempotentAndDoesNotGrantStaffAccess() throws Exception {
        jdbc.sql("INSERT INTO support_merchant(id,name) VALUES (1,'学习商家')").update();
        jdbc.sql("INSERT INTO support_merchant_shop(shop_id,merchant_id,name) VALUES (100,1,'已有店铺')").update();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            connection.setAutoCommit(false);
            var first = DemoShopData.seed(connection);
            connection.commit();
            assertThat(first.shopsInserted()).isEqualTo(2);
            assertThat(first.ordersInserted()).isEqualTo(4);
            var second = DemoShopData.seed(connection);
            connection.commit();
            assertThat(second.shopsInserted()).isZero();
            assertThat(second.ordersInserted()).isZero();
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM support_merchant_staff_shop_grant WHERE shop_id IN (102,103)").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM support_order WHERE tenant_id IN (102,103) AND buyer_id=200").query(Long.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM support_shipment_event").query(Long.class).single()).isEqualTo(2);
    }

    private void reply(Conversation conversation, String text) { replyAs(200, conversation, text); }
    private void replyAs(long buyer, Conversation conversation, String text) {
        var request = new ConversationMessageRequest(UUID.randomUUID(), text, null);
        var reserved = store.reserve(buyer, conversation.shopId(), conversation.id(), request);
        store.succeed(buyer, conversation.shopId(), conversation.id(), request, response(), reserved.processingToken());
    }
    private static ChatResponse response() { return new ChatResponse("已收到咨询", ChatReplyType.TEXT, List.of(), null); }
}
