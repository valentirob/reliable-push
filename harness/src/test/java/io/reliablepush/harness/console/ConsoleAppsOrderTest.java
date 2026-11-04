package io.reliablepush.harness.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The console lists the default app {@code shop} first (its selects default to the first entry), then by id. */
class ConsoleAppsOrderTest {

    @Test
    void shopFirstThenAlphabetical() {
        assertThat(ConsoleApiController.displayOrder(Set.of("limited", "news", "shop")))
                .containsExactly("shop", "limited", "news");
        assertThat(ConsoleApiController.displayOrder(Set.of("news", "limited"))).containsExactly("limited", "news");
    }
}
