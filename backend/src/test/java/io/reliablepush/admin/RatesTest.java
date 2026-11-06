package io.reliablepush.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RatesTest {

    private static Counts counts(int registered, int cancelled) {
        return new Counts(registered, cancelled, 0, 0, 0, 0, 0);
    }

    @Test
    void rateIsPartOverRegisteredMinusCancelled() {
        assertThat(Rates.of(3, counts(10, 2))).isEqualTo(0.375);
    }

    @Test
    void emptyDenominatorIsNull() {
        assertThat(Rates.of(0, counts(0, 0))).isNull();
        assertThat(Rates.of(0, counts(4, 4))).isNull();
    }
}
