package io.reliablepush.devices.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JwksUrlsTest {

    private static URI uri(String value) {
        return URI.create(value);
    }

    @Test
    void remoteIssuerRequiresHttpsEverywhere() {
        URI issuer = uri("https://idp.example.org");

        assertThat(JwksUrls.allowed(uri("https://idp.example.org/jwks"), issuer)).isTrue();
        assertThat(JwksUrls.allowed(uri("https://keys.example.net/jwks"), issuer)).isTrue();
        assertThat(JwksUrls.allowed(uri("http://idp.example.org/jwks"), issuer)).isFalse();
        assertThat(JwksUrls.allowed(uri("http://169.254.169.254/jwks"), issuer)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:9000", "http://127.0.0.1:9000/realm", "https://localhost", "http://LOCALHOST"})
    void localIssuerMayUseHttp(String issuer) {
        assertThat(JwksUrls.allowed(uri("http://localhost:9000/jwks"), uri(issuer))).isTrue();
    }

    @Test
    void lookalikeHostsAreNotLocal() {
        assertThat(JwksUrls.isLocal(uri("http://localhost.evil.example"))).isFalse();
        assertThat(JwksUrls.isLocal(uri("http://127.0.0.1.evil.example"))).isFalse();
        assertThat(JwksUrls.isLocal(uri("http://evil.example/localhost"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "not a url", "ftp://x/jwks", "file:///etc/passwd", "/relative", "https://", "https://user:pw@idp.example.org"})
    void parseRejectsAnythingButPlainHttpUrls(String value) {
        assertThat(JwksUrls.parse(value)).isNull();
    }

    @Test
    void parseAcceptsHttpAndHttps() {
        assertThat(JwksUrls.parse("https://idp.example.org/jwks")).isNotNull();
        assertThat(JwksUrls.parse("http://localhost:1/jwks")).isNotNull();
        assertThat(JwksUrls.parse(null)).isNull();
    }
}
