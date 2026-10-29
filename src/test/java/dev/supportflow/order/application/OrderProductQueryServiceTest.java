package dev.supportflow.order.application;

import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.order.domain.OrderQueryLimits;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import dev.supportflow.order.domain.repository.SupportOrderRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OrderProductQueryServiceTest {
    private final SupportOrderRepository repository = mock(SupportOrderRepository.class);
    private final OrderQueryService service = new OrderQueryService(repository);
    private final CustomerIdentity identity = new CustomerIdentity(200);

    @Test
    void currentShopTrustedBuyerAndLiteralKeywordAreAlwaysSentTogether() {
        when(repository.findByProductName(100, 200, "蓝牙耳机", 21)).thenReturn(List.of(order(1)));
        var matches = service.findOwnedOrdersByProduct(identity, 100, " 蓝牙耳机 ");
        assertThat(matches.items()).hasSize(1);
        assertThat(matches.hasMore()).isFalse();
        verify(repository).findByProductName(100, 200, "蓝牙耳机", OrderQueryLimits.MAX_PRODUCT_FETCH_SIZE);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void twentyOneRowsMeanTwentyChoicesAndRefinementRatherThanASilentNewestChoice() {
        when(repository.findByProductName(100, 200, "耳机", 21))
                .thenReturn(LongStream.rangeClosed(1, 21).mapToObj(this::order).toList());
        var matches = service.findOwnedOrdersByProduct(identity, 100, "耳机");
        assertThat(matches.items()).hasSize(20);
        assertThat(matches.items().getLast().id()).isEqualTo(20);
        assertThat(matches.hasMore()).isTrue();
    }

    @Test
    void noMatchAndExactlyTwentyMatchesHaveNoInventedExtraPage() {
        when(repository.findByProductName(100, 200, "耳机", 21)).thenReturn(List.of());
        assertThat(service.findOwnedOrdersByProduct(identity, 100, "耳机").items()).isEmpty();
        when(repository.findByProductName(100, 200, "耳机", 21))
                .thenReturn(LongStream.rangeClosed(1, 20).mapToObj(this::order).toList());
        assertThat(service.findOwnedOrdersByProduct(identity, 100, "耳机").hasMore()).isFalse();
    }

    @Test
    void missingIdentityInvalidShopOrUnboundedKeywordCannotReachTheRepository() {
        assertThatThrownBy(() -> service.findOwnedOrdersByProduct(null, 100, "耳机")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.findOwnedOrdersByProduct(identity, 0, "耳机")).isInstanceOf(IllegalArgumentException.class);
        for (String keyword : new String[]{null, "", " ", "\u00a0\u3000", "耳", "耳".repeat(101), "耳机\n", "耳\t机",
                "一 二 三 四 五", "黑色耳机 大号 新款 旗舰版"}) {
            assertThatThrownBy(() -> service.findOwnedOrdersByProduct(identity, 100, keyword)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void serviceUsesTheSameUnicodeWhitespaceNormalizationAsTheDomainValue() {
        when(repository.findByProductName(100, 200, "耳机 黑色", 21)).thenReturn(List.of());
        assertThat(service.findOwnedOrdersByProduct(identity, 100, "耳机\u3000  黑色").items()).isEmpty();
        verify(repository).findByProductName(100, 200, "耳机 黑色", 21);
    }

    private SupportOrder order(long id) {
        return new SupportOrder(id, 100, 200, 200, "SFP" + id, OrderStatus.PAID,
                BigDecimal.ONE, "CNY", Instant.EPOCH, Instant.EPOCH);
    }
}
