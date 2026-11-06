package io.reliablepush.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserHashTest {

    @Test
    void hashesDeterministically() {
        String hash = UserHash.of("alice");

        assertThat(hash).isEqualTo("2bd806c97f0e00af").hasSize(16);
        assertThat(UserHash.of("alice")).isEqualTo(hash);
    }

    @Test
    void differentUsersGetDifferentHashes() {
        assertThat(UserHash.of("alice")).isNotEqualTo(UserHash.of("bob"));
    }
}
