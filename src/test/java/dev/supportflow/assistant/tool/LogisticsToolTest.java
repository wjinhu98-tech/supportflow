package dev.supportflow.assistant.tool;

import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LogisticsToolTest {

    private JdbcClient jdbcClient;
    private JdbcClient.StatementSpec statement;
    private LogisticsTool tool;

    @BeforeEach
    void setUp() {
        jdbcClient = mock(JdbcClient.class);
        statement = mock(JdbcClient.StatementSpec.class);
        tool = new LogisticsTool(jdbcClient);
    }

    @Test
    void shouldNotInventShipmentEventsFromShippedOrderStatus() throws Exception {
        returnEvents(List.of());
        String reply = tool.lookup(order(1001L));
        assertThat(reply).contains("SF10001", "暂无录入", "未连接真实快递查询服务")
                .doesNotContain("已签收", "运输中");
        verify(statement).param("orderId", 1001L);
        verify(statement).param("limit", 50);
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient).sql(query.capture());
        assertThat(query.getValue()).contains("order_id = :orderId", "ORDER BY occurred_at ASC, id ASC", "LIMIT :limit")
                .doesNotContain("SF10001");
    }

    @Test
    void shouldRenderOnlyRecordedEventsWithUtcTimesAndSourceDisclosure() throws Exception {
        returnEvents(List.of(
                row("2026-10-20T02:03:04.123", "商家录入：包裹已交接"),
                row("2026-10-20T05:06:07.456", "客服录入：已到区域分拨中心")
        ));
        assertThat(tool.lookup(order(1001L)))
                .contains("本项目物流模块", "并非实时快递联网查询", "UTC")
                .containsSubsequence("2026-10-20T02:03:04.123Z", "商家录入：包裹已交接",
                        "2026-10-20T05:06:07.456Z", "客服录入：已到区域分拨中心");
    }

    @Test
    void shouldDiscloseDisplayLimitWithoutClaimingUnqueriedEvents() throws Exception {
        List<ResultSet> rows = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            rows.add(row("2026-10-20T02:03:04.123", "录入事件 " + index));
        }
        returnEvents(rows);
        assertThat(tool.lookup(order(1001L))).contains("最多展示 50 条已录入事件")
                .doesNotContain("还有更多");
    }

    @Test
    void shouldRejectMissingOrNonpositiveValidatedOrderBeforeQuery() {
        assertThatThrownBy(() -> tool.lookup(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> tool.lookup(order(0L))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbcClient);
    }

    @Test
    void shouldNotHideDatabaseFailureAsNoEvents() {
        when(jdbcClient.sql(anyString())).thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThatThrownBy(() -> tool.lookup(order(1001L)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void shouldRejectCorruptPersistedEventRatherThanFabricateTime() throws Exception {
        ResultSet corrupt = mock(ResultSet.class);
        when(corrupt.getString("description")).thenReturn("录入事件");
        returnEvents(List.of(corrupt));
        assertThatThrownBy(() -> tool.lookup(order(1001L)))
                .isInstanceOf(DataRetrievalFailureException.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void returnEvents(List<ResultSet> rows) {
        when(jdbcClient.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(0);
            List<Object> mapped = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) {
                mapped.add(mapper.mapRow(rows.get(index), index));
            }
            JdbcClient.MappedQuerySpec query = mock(JdbcClient.MappedQuerySpec.class);
            when(query.list()).thenReturn(mapped);
            return query;
        });
    }

    private ResultSet row(String occurredAt, String description) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("occurred_at", LocalDateTime.class)).thenReturn(LocalDateTime.parse(occurredAt));
        when(row.getString("description")).thenReturn(description);
        return row;
    }

    private SupportOrder order(long id) {
        return new SupportOrder(id, 100L, 200L, 200L, "SF10001", OrderStatus.SHIPPED,
                new BigDecimal("199.90"), "CNY", Instant.EPOCH, Instant.EPOCH);
    }
}
