package io.reliablepush.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RatesTest {

    private static Counts counts(int registered, int cancelled) {
        return counts(registered, cancelled, 0);
    }

    private static Counts counts(int registered, int cancelled, int noDevice) {
        return new Counts(registered, cancelled, noDevice, 0, 0, 0, 0, 0);
    }

    @Test
    void rateIsPartOverRegisteredMinusCancelled() {
        assertThat(Rates.of(3, counts(10, 2))).isEqualTo(0.375);
    }

    @Test
    void untrackedNotificationsAreLeftOutOfTheDenominator() {
        assertThat(Rates.of(3, counts(12, 2, 4))).isEqualTo(0.5);
    }

    @Test
    void emptyDenominatorIsNull() {
        assertThat(Rates.of(0, counts(0, 0))).isNull();
        assertThat(Rates.of(0, counts(4, 4))).isNull();
        assertThat(Rates.of(0, counts(4, 1, 3))).isNull();
    }
}
