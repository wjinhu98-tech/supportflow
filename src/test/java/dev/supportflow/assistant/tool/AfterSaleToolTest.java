package dev.supportflow.assistant.tool;

import dev.supportflow.assistant.aftersale.AfterSaleService;
import dev.supportflow.assistant.aftersale.AfterSaleStatus;
import dev.supportflow.assistant.aftersale.AfterSaleTicket;
import dev.supportflow.assistant.execution.AfterSaleExecution;
import dev.supportflow.assistant.execution.ExecutionStatus;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AfterSaleToolTest {
    private final AfterSaleService cases = mock(AfterSaleService.class);
    private final AfterSaleTool tool = new AfterSaleTool(cases);
    private final CustomerIdentity identity = new CustomerIdentity(200);
    private final CustomerPrincipal principal = new CustomerPrincipal("buyer", "not-a-real-hash", identity,
            List.of(SecurityPermission.ORDER_READ));

    @ParameterizedTest
    @CsvSource({"PENDING_REVIEW,等待客服审核", "APPROVED,客服审核通过", "REJECTED,客服审核未通过"})
    void shouldUseCustomerFacingReviewLabelsAndDiscloseSimulation(AfterSaleStatus status, String label) {
        when(cases.submit(principal, 100, "SF10001", "商品破损")).thenReturn(ticket(status, null));

        String reply = tool.request(principal, 100, "SF10001", "商品破损");

        assertThat(reply).contains("case-1", "SF10001", label, "我的售后", "不会实际返款")
                .doesNotContain("Java", "SQL", "AI", "Tool", "Worker", status.name());
        verify(cases).submit(principal, 100, "SF10001", "商品破损");
        verifyNoMoreInteractions(cases);
    }

    @ParameterizedTest
    @CsvSource({"PENDING,等待处理", "PROCESSING,正在处理", "SUCCEEDED,演示处理完成", "FAILED,暂未完成"})
    void shouldDescribeExecutionWithoutLeakingInternalStatusOrError(ExecutionStatus status, String label) {
        AfterSaleExecution execution = new AfterSaleExecution(status, new BigDecimal("12.30"), "CNY",
                1, 3, null, null, null, "Java SQL internal error", false);
        when(cases.listOwned(identity, 100)).thenReturn(List.of(ticket(AfterSaleStatus.APPROVED, execution)));

        String reply = tool.history(principal, 100);

        assertThat(reply).contains("SF10001", label, "12.30", "不会实际返款")
                .doesNotContain("Java", "SQL", "AI", "Tool", "Worker", status.name(), "APPROVED");
        verify(cases).listOwned(identity, 100);
        verifyNoMoreInteractions(cases);
    }

    @Test
    void shouldReturnEmptyCurrentShopHistoryWithoutClaimingAnyRefundOccurred() {
        when(cases.listOwned(identity, 100)).thenReturn(List.of());
        assertThat(tool.history(principal, 100)).contains("当前店铺", "暂无售后申请");
        verify(cases).listOwned(identity, 100);
        verifyNoMoreInteractions(cases);
    }

    private AfterSaleTicket ticket(AfterSaleStatus status, AfterSaleExecution execution) {
        return new AfterSaleTicket("case-1", 100, "SF10001", "商品破损", status, null, null,
                Instant.EPOCH, Instant.EPOCH, execution);
    }
}
