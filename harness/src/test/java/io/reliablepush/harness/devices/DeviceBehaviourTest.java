package io.reliablepush.harness.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliablepush.harness.devices.DeviceBehaviour.DeliverAfter;
import io.reliablepush.harness.devices.DeviceBehaviour.Never;
import io.reliablepush.harness.devices.DeviceBehaviour.OfflineThenLate;
import io.reliablepush.harness.devices.DeviceBehaviour.ReadAfter;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeviceBehaviourTest {

    @Test
    void parsesAllForms() {
        assertThat(DeviceBehaviour.parse("deliver:3s")).isEqualTo(new DeliverAfter(Duration.ofSeconds(3)));
        assertThat(DeviceBehaviour.parse("read:2s:5s"))
                .isEqualTo(new ReadAfter(Duration.ofSeconds(2), Duration.ofSeconds(5)));
        assertThat(DeviceBehaviour.parse("never")).isEqualTo(new Never());
        assertThat(DeviceBehaviour.parse("late:30s")).isEqualTo(new OfflineThenLate(Duration.ofSeconds(30)));
        assertThat(DeviceBehaviour.parse(" deliver:500ms ")).isEqualTo(new DeliverAfter(Duration.ofMillis(500)));
        assertThat(DeviceBehaviour.parse("deliver:2m")).isEqualTo(new DeliverAfter(Duration.ofMinutes(2)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "deliver", "deliver:", "deliver:abc", "deliver:-1s", "deliver:3", "deliver:3s:4s",
            "read:2s", "read:5s:2s", "never:1s", "late:", "late:1x", "fly:1s", "DELIVER:1s", "deliver:99999999999999999999s"})
    void rejectsGarbage(String text) {
        assertThatThrownBy(() -> DeviceBehaviour.parse(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("behaviour");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> DeviceBehaviour.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
