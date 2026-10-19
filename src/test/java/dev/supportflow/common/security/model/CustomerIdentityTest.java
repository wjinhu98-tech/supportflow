package dev.supportflow.common.security.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerIdentityTest {

    @Test
    void shouldRejectNonPositiveGlobalBuyerId() {
        assertThatThrownBy(() -> new CustomerIdentity(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CustomerIdentity(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
