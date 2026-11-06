package io.reliablepush.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class SecretRefsTest {

    private static final String PROP = "reliable-push.apps.shop.fallback.hmac-secret-ref";

    private StandardEnvironment environmentWith(String name, String value) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(name, value)));
        return env;
    }

    @Test
    void resolvesSecretContainingPlaceholderSyntaxVerbatim() {
        StandardEnvironment env = environmentWith("RP_HOOK", "abc${nope}SECRETTAIL");

        assertThat(SecretRefs.resolve(env, PROP, "env:RP_HOOK").value()).isEqualTo("abc${nope}SECRETTAIL");
    }

    @Test
    void unsetVariableMessageNamesVariableOnly() {
        StandardEnvironment env = environmentWith("RP_HOOK", "abc${nope}SECRETTAIL");

        assertThatThrownBy(() -> SecretRefs.resolve(env, PROP, "env:RP_OTHER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RP_OTHER")
                .hasMessageNotContaining("SECRETTAIL");
    }

    @Test
    void inlineValueIsRejectedWithoutEchoingIt() {
        StandardEnvironment env = new StandardEnvironment();

        assertThatThrownBy(() -> SecretRefs.resolve(env, PROP, "inline${secret}VALUE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROP)
                .hasMessageNotContaining("VALUE");
    }
}
