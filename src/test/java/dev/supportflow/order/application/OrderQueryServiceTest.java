package dev.supportflow.order.application;

import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import dev.supportflow.order.application.model.OrderPage;
import dev.supportflow.order.domain.OrderQueryLimits;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import dev.supportflow.order.domain.repository.SupportOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OrderQueryServiceTest {

    private SupportOrderRepository supportOrderRepository;

    private OrderQueryService orderQueryService;

    private static final CustomerIdentity CUSTOMER_IDENTITY =
            new CustomerIdentity(200L);

    @BeforeEach
    void setUp() {
        supportOrderRepository = mock(SupportOrderRepository.class);

        orderQueryService = new OrderQueryService(supportOrderRepository);
    }

    @Test
    @DisplayName("范围内存在订单时返回仓储查询结果")
    void shouldReturnOwnedOrder() {
        SupportOrder expectedOrder = new SupportOrder(
                1001L,
                100L,
                200L,
                200L,
                "SF10001",
                OrderStatus.SHIPPED,
                new BigDecimal("199.90"),
                "CNY",
                Instant.parse("2026-10-01T02:03:04.123Z"),
                Instant.parse("2026-10-02T05:06:07.456Z")
        );

        when(supportOrderRepository.findByOrderNo(
                100L,
                200L,
                "SF10001"
        )).thenReturn(Optional.of(expectedOrder));

        SupportOrder actualOrder = orderQueryService.getOwnedOrder(
                CUSTOMER_IDENTITY,
                100L,
                "SF10001"
        );

        assertThat(actualOrder).isSameAs(expectedOrder);

        verify(supportOrderRepository).findByOrderNo(
                100L,
                200L,
                "SF10001"
        );
    }

    @Test
    @DisplayName("范围内没有订单时抛出订单查询业务异常")
    void shouldThrowWhenOrderIsNotAccessible() {
        when(supportOrderRepository.findByOrderNo(
                100L,
                200L,
                "SF_NOT_EXISTS"
        )).thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> orderQueryService.getOwnedOrder(
                        CUSTOMER_IDENTITY,
                        100L,
                        "SF_NOT_EXISTS"
                )
        )
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessage("订单不存在或无权访问");

        verify(supportOrderRepository).findByOrderNo(
                100L,
                200L,
                "SF_NOT_EXISTS"
        );
    }

    @Test
    @DisplayName("同一买家身份可以查询另一家商铺")
    void shouldPassRequestedShopAndAuthenticatedBuyerToRepository() {
        when(supportOrderRepository.findByOrderNo(300L, 200L, "SF10001"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderQueryService.getOwnedOrder(
                CUSTOMER_IDENTITY, 300L, "SF10001"
        )).isInstanceOf(OrderNotFoundException.class);

        verify(supportOrderRepository).findByOrderNo(300L, 200L, "SF10001");
    }

    @Test
    @DisplayName("跨商铺列表多读一行并以最后一条已返回订单生成游标")
    void shouldBuildCursorWithoutDroppingNextPageOrder() {
        SupportOrder first = order(1001L, 100L);
        SupportOrder second = order(1002L, 300L);
        SupportOrder third = order(1004L, 100L);
        when(supportOrderRepository.findByBuyerId(200L, 0L, 3))
                .thenReturn(List.of(first, second, third));

        OrderPage page = orderQueryService.listOwnedOrders(CUSTOMER_IDENTITY, 0L, 2);

        assertThat(page.items()).containsExactly(first, second);
        assertThat(page.nextAfterId()).isEqualTo(1002L);
        verify(supportOrderRepository).findByBuyerId(200L, 0L, 3);

        when(supportOrderRepository.findByBuyerId(200L, 1002L, 3))
                .thenReturn(List.of(third));
        OrderPage nextPage = orderQueryService.listOwnedOrders(
                CUSTOMER_IDENTITY, page.nextAfterId(), 2);
        assertThat(nextPage.items()).containsExactly(third);
        assertThat(nextPage.nextAfterId()).isNull();
    }

    @Test
    @DisplayName("最后一页不返回后续游标，空列表正常返回")
    void shouldFinishPaginationAndHandleEmptyOrders() {
        SupportOrder last = order(1004L, 100L);
        when(supportOrderRepository.findByBuyerId(200L, 1002L, 3))
                .thenReturn(List.of(last));
        when(supportOrderRepository.findByBuyerId(200L, 1004L, 3))
                .thenReturn(List.of());

        OrderPage lastPage = orderQueryService.listOwnedOrders(
                CUSTOMER_IDENTITY, 1002L, 2
        );
        assertThat(lastPage.items()).containsExactly(last);
        assertThat(lastPage.nextAfterId()).isNull();

        OrderPage empty = orderQueryService.listOwnedOrders(
                CUSTOMER_IDENTITY, 1004L, 2
        );
        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextAfterId()).isNull();
    }

    @Test
    @DisplayName("恰好填满一页且没有预读行时不返回下一页游标")
    void shouldFinishWhenResultExactlyFillsPage() {
        SupportOrder first = order(1001L, 100L);
        SupportOrder second = order(1002L, 300L);
        when(supportOrderRepository.findByBuyerId(200L, 0L, 3))
                .thenReturn(List.of(first, second));

        OrderPage page = orderQueryService.listOwnedOrders(CUSTOMER_IDENTITY, 0L, 2);

        assertThat(page.items()).containsExactly(first, second);
        assertThat(page.nextAfterId()).isNull();
    }

    @Test
    @DisplayName("最大页大小仍允许预读且不会把预读订单返回给客户端")
    void shouldAllowMaximumPageSizeWithLookahead() {
        List<SupportOrder> rows = LongStream.rangeClosed(1, OrderQueryLimits.MAX_FETCH_SIZE)
                .mapToObj(id -> order(id, 100L))
                .toList();
        when(supportOrderRepository.findByBuyerId(200L, 0L, OrderQueryLimits.MAX_FETCH_SIZE))
                .thenReturn(rows);

        OrderPage page = orderQueryService.listOwnedOrders(
                CUSTOMER_IDENTITY, 0L, OrderQueryLimits.MAX_PAGE_SIZE);

        assertThat(page.items()).containsExactlyElementsOf(
                rows.subList(0, OrderQueryLimits.MAX_PAGE_SIZE));
        assertThat(page.nextAfterId()).isEqualTo((long) OrderQueryLimits.MAX_PAGE_SIZE);
        verify(supportOrderRepository).findByBuyerId(200L, 0L, OrderQueryLimits.MAX_FETCH_SIZE);
    }

    @Test
    @DisplayName("应用层拒绝无界分页和负游标")
    void shouldRejectInvalidPageParameters() {
        assertThatThrownBy(() -> orderQueryService.listOwnedOrders(
                CUSTOMER_IDENTITY, -1L, 20
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderQueryService.listOwnedOrders(
                CUSTOMER_IDENTITY, 0L, 0
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderQueryService.listOwnedOrders(
                CUSTOMER_IDENTITY, 0L, OrderQueryLimits.MAX_PAGE_SIZE + 1
        )).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(supportOrderRepository);
    }

    @Test
    @DisplayName("Shop pagination keeps the lookahead order for the next page")
    void shouldBuildShopCursorWithoutDroppingNextPageOrder() {
        SupportOrder first = order(1001L, 100L);
        SupportOrder second = order(1004L, 100L);
        SupportOrder third = order(1006L, 100L);
        when(supportOrderRepository.findByShopAndBuyerId(100L, 200L, 0L, 3))
                .thenReturn(List.of(first, second, third));
        when(supportOrderRepository.findByShopAndBuyerId(100L, 200L, 1004L, 3))
                .thenReturn(List.of(third));

        OrderPage firstPage = orderQueryService.listOwnedOrdersInShop(
                CUSTOMER_IDENTITY, 100L, 0L, 2);
        assertThat(firstPage.items()).containsExactly(first, second);
        assertThat(firstPage.nextAfterId()).isEqualTo(1004L);

        OrderPage secondPage = orderQueryService.listOwnedOrdersInShop(
                CUSTOMER_IDENTITY, 100L, firstPage.nextAfterId(), 2);
        assertThat(secondPage.items()).containsExactly(third);
        assertThat(secondPage.nextAfterId()).isNull();
        verify(supportOrderRepository).findByShopAndBuyerId(100L, 200L, 0L, 3);
        verify(supportOrderRepository).findByShopAndBuyerId(100L, 200L, 1004L, 3);
        verifyNoMoreInteractions(supportOrderRepository);
    }

    @Test
    @DisplayName("An exactly full shop page has no next cursor without a lookahead row")
    void shouldFinishShopPaginationWhenResultExactlyFillsPage() {
        SupportOrder first = order(1001L, 100L);
        SupportOrder second = order(1004L, 100L);
        when(supportOrderRepository.findByShopAndBuyerId(100L, 200L, 0L, 3))
                .thenReturn(List.of(first, second));

        OrderPage page = orderQueryService.listOwnedOrdersInShop(
                CUSTOMER_IDENTITY, 100L, 0L, 2);

        assertThat(page.items()).containsExactly(first, second);
        assertThat(page.nextAfterId()).isNull();
        verify(supportOrderRepository).findByShopAndBuyerId(100L, 200L, 0L, 3);
        verifyNoMoreInteractions(supportOrderRepository);
    }

    @Test
    @DisplayName("An empty shop query never falls back to account-wide orders")
    void shouldReturnEmptyShopPageWithoutAccountWideFallback() {
        when(supportOrderRepository.findByShopAndBuyerId(999L, 200L, 0L, 3))
                .thenReturn(List.of());

        OrderPage page = orderQueryService.listOwnedOrdersInShop(
                CUSTOMER_IDENTITY, 999L, 0L, 2);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextAfterId()).isNull();
        verify(supportOrderRepository).findByShopAndBuyerId(999L, 200L, 0L, 3);
        verifyNoMoreInteractions(supportOrderRepository);
    }

    @Test
    @DisplayName("The authenticated platform buyer is used for the requested shop")
    void shouldUseAuthenticatedBuyerForShopQuery() {
        CustomerIdentity otherBuyer = new CustomerIdentity(987L);
        when(supportOrderRepository.findByShopAndBuyerId(300L, 987L, 17L, 2))
                .thenReturn(List.of());

        OrderPage page = orderQueryService.listOwnedOrdersInShop(
                otherBuyer, 300L, 17L, 1);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextAfterId()).isNull();
        verify(supportOrderRepository).findByShopAndBuyerId(300L, 987L, 17L, 2);
        verifyNoMoreInteractions(supportOrderRepository);
    }

    @Test
    @DisplayName("Maximum shop page size uses the shared bound and hides lookahead")
    void shouldAllowMaximumShopPageSizeWithLookahead() {
        List<SupportOrder> rows = LongStream.rangeClosed(1, OrderQueryLimits.MAX_FETCH_SIZE)
                .mapToObj(id -> order(id, 100L))
                .toList();
        when(supportOrderRepository.findByShopAndBuyerId(
                100L, 200L, 0L, OrderQueryLimits.MAX_FETCH_SIZE))
                .thenReturn(rows);

        OrderPage page = orderQueryService.listOwnedOrdersInShop(
                CUSTOMER_IDENTITY, 100L, 0L, OrderQueryLimits.MAX_PAGE_SIZE);

        assertThat(page.items()).containsExactlyElementsOf(
                rows.subList(0, OrderQueryLimits.MAX_PAGE_SIZE));
        assertThat(page.nextAfterId()).isEqualTo((long) OrderQueryLimits.MAX_PAGE_SIZE);
        verify(supportOrderRepository).findByShopAndBuyerId(
                100L, 200L, 0L, OrderQueryLimits.MAX_FETCH_SIZE);
        verifyNoMoreInteractions(supportOrderRepository);
    }

    @Test
    @DisplayName("The largest id cursor remains a valid empty shop page")
    void shouldAllowLastLongCursorForShopQuery() {
        when(supportOrderRepository.findByShopAndBuyerId(
                100L, 200L, Long.MAX_VALUE, 2)).thenReturn(List.of());

        OrderPage page = orderQueryService.listOwnedOrdersInShop(
                CUSTOMER_IDENTITY, 100L, Long.MAX_VALUE, 1);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextAfterId()).isNull();
        verify(supportOrderRepository).findByShopAndBuyerId(
                100L, 200L, Long.MAX_VALUE, 2);
        verifyNoMoreInteractions(supportOrderRepository);
    }

    @Test
    @DisplayName("Invalid shop or pagination is rejected before repository access")
    void shouldRejectInvalidShopAndShopPagination() {
        assertThatThrownBy(() -> orderQueryService.listOwnedOrdersInShop(
                CUSTOMER_IDENTITY, 0L, 0L, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderQueryService.listOwnedOrdersInShop(
                CUSTOMER_IDENTITY, -1L, 0L, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderQueryService.listOwnedOrdersInShop(
                CUSTOMER_IDENTITY, 100L, -1L, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderQueryService.listOwnedOrdersInShop(
                CUSTOMER_IDENTITY, 100L, 0L, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderQueryService.listOwnedOrdersInShop(
                CUSTOMER_IDENTITY, 100L, 0L, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderQueryService.listOwnedOrdersInShop(
                CUSTOMER_IDENTITY, 100L, 0L, OrderQueryLimits.MAX_PAGE_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orderQueryService.listOwnedOrdersInShop(
                CUSTOMER_IDENTITY, 100L, 0L, Integer.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(supportOrderRepository);
    }

    @Test
    @DisplayName("Shop queries require a trusted customer identity")
    void shouldRejectNullIdentityForShopQuery() {
        assertThatThrownBy(() -> orderQueryService.listOwnedOrdersInShop(
                null, 100L, 0L, 20))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("identity must not be null");
        verifyNoInteractions(supportOrderRepository);
    }

    private SupportOrder order(long id, long shopId) {
        return new SupportOrder(
                id, shopId, 200L, 200L, "SF10001", OrderStatus.PAID,
                new BigDecimal("10.00"), "CNY",
                Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z")
        );
    }
}
