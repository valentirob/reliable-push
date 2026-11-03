package io.reliablepush.devices.identity;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The transport rule for everything fetched from an identity provider: https, except when the <em>issuer</em> host is
 * {@code localhost} or {@code 127.0.0.1} (tests and the demo), where plain http is allowed too. The rule is anchored on
 * the configured issuer, so a provider cannot talk this service into fetching keys over http by pointing its discovery
 * document somewhere else.
 */
final class JwksUrls {

    private JwksUrls() {}

    /** Parses an absolute http(s) URL with a host and no credentials; {@code null} if it is anything else. */
    static URI parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(value.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null || uri.getUserInfo() != null) {
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** True if the issuer is served from this machine, which relaxes the https requirement. */
    static boolean isLocal(URI issuer) {
        String host = issuer.getHost().toLowerCase(Locale.ROOT);
        return host.equals("localhost") || host.equals("127.0.0.1");
    }

    /** True if {@code target} may be fetched on behalf of {@code issuer}. */
    static boolean allowed(URI target, URI issuer) {
        return "https".equalsIgnoreCase(target.getScheme()) || ("http".equalsIgnoreCase(target.getScheme()) && isLocal(issuer));
    }
}
