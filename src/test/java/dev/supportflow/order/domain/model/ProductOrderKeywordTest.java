package dev.supportflow.order.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductOrderKeywordTest {
    @Test
    void normalizesUnicodeWhitespaceAndDerivesImmutableTermsFromOneValue() {
        var keyword = new ProductOrderKeyword(" \u00a0蓝牙耳机\u3000  黑色\u00a0 ");
        assertThat(keyword.value()).isEqualTo("蓝牙耳机 黑色");
        assertThat(keyword.terms()).containsExactly("蓝牙耳机", "黑色");
        assertThatThrownBy(() -> keyword.terms().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void knownColourPrefixesSplitButUnrelatedChineseNamesAndLiteralPunctuationDoNot() {
        assertThat(new ProductOrderKeyword("黑色耳机").terms()).containsExactly("黑色", "耳机");
        assertThat(new ProductOrderKeyword("米白色耳机").terms()).containsExactly("米白色", "耳机");
        assertThat(new ProductOrderKeyword("和田玉手镯").terms()).containsExactly("和田玉手镯");
        assertThat(new ProductOrderKeyword("100% A_B USB!").terms()).containsExactly("100%", "A_B", "USB!");
    }

    @Test
    void theSameFourTermBoundIsAppliedAfterColourExpansion() {
        assertThat(new ProductOrderKeyword("蓝牙耳机 黑色 大号 新款").terms()).hasSize(4);
        assertThat(new ProductOrderKeyword("黑色耳机 大号 新款").terms()).hasSize(4);
        assertThatThrownBy(() -> new ProductOrderKeyword("耳机 黑色 大号 新款 旗舰版"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductOrderKeyword("黑色耳机 大号 新款 旗舰版"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankOversizedAndControlCharacterInputsAreRejectedBeforeTheyCanBeQueries() {
        for (String value : new String[]{null, "", " ", "\u00a0\u3000", "耳", "耳".repeat(101), "耳机\n", "耳\t机", "\r耳机"}) {
            assertThatThrownBy(() -> new ProductOrderKeyword(value)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
