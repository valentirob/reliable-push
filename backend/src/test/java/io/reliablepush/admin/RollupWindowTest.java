package io.reliablepush.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RollupWindowTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-06");

    @Test
    void sevenDaysWhenRetentionIsLonger() {
        assertThat(RollupWindow.days(TODAY, 30))
                .hasSize(7)
                .first()
                .isEqualTo(LocalDate.parse("2026-10-30"));
        assertThat(RollupWindow.days(TODAY, 30)).last().isEqualTo(LocalDate.parse("2026-11-05"));
    }

    @Test
    void retentionOneIsYesterdayOnly() {
        assertThat(RollupWindow.days(TODAY, 1)).containsExactly(LocalDate.parse("2026-11-05"));
    }

    @Test
    void retentionSevenIsSevenDays() {
        assertThat(RollupWindow.days(TODAY, 7)).hasSize(7).first().isEqualTo(LocalDate.parse("2026-10-30"));
    }

    @Test
    void widthIsCapped() {
        assertThat(RollupWindow.width(30)).isEqualTo(7);
        assertThat(RollupWindow.width(3)).isEqualTo(3);
    }
}
