package io.reliablepush.payload;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrackingIdsTest {

    @Test
    void newIdIsLowerCaseVersion4Uuid() {
        String id = TrackingIds.newId();

        assertThat(id).isEqualTo(id.toLowerCase(java.util.Locale.ROOT));
        assertThat(UUID.fromString(id).version()).isEqualTo(4);
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(TrackingIds.isValid(id)).isTrue();
    }

    @Test
    void newIdIsUnique() {
        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            ids.add(TrackingIds.newId());
        }
        assertThat(ids).hasSize(1000);
    }

    @Test
    void isValidAcceptsAnyUuid() {
        assertThat(TrackingIds.isValid("6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11")).isTrue();
        assertThat(TrackingIds.isValid("6F1C2B9E-5D1A-4C55-9A38-0C1F3A7D9E11")).isTrue();
        assertThat(TrackingIds.isValid("00000000-0000-0000-0000-000000000000")).isTrue();
        assertThat(TrackingIds.isValid("6f1c2b9e-5d1a-1c55-1a38-0c1f3a7d9e11")).isTrue();
    }

    @Test
    void isValidRejectsGarbage() {
        assertThat(TrackingIds.isValid(null)).isFalse();
        assertThat(TrackingIds.isValid("")).isFalse();
        assertThat(TrackingIds.isValid("not-a-uuid")).isFalse();
        assertThat(TrackingIds.isValid("6f1c2b9e5d1a4c559a380c1f3a7d9e11")).isFalse();
        assertThat(TrackingIds.isValid("6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e1")).isFalse();
        assertThat(TrackingIds.isValid("6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e111")).isFalse();
        assertThat(TrackingIds.isValid("6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e1g")).isFalse();
        assertThat(TrackingIds.isValid(" 6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11")).isFalse();
        assertThat(TrackingIds.isValid("6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11\n")).isFalse();
    }

    @Test
    void requireReturnsTheLowerCaseFormOrRejects() {
        assertThat(TrackingIds.require("6F1C2B9E-5D1A-4C55-9A38-0C1F3A7D9E11")).isEqualTo("6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TrackingIds.require("nope"))
                .isInstanceOfSatisfying(PayloadException.class,
                        e -> assertThat(e.code()).isEqualTo(PayloadException.Code.INVALID_TRACKING_ID));
    }
}
