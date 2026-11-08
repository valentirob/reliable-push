package io.reliablepush.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.support.OpenApiAssert;
import io.reliablepush.support.PostgresTestBase;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles({"api", "worker", "test", "admin"})
@ExtendWith(OutputCaptureExtension.class)
class AdminApiIT extends PostgresTestBase {

    private static final String SHOP_KEY = "test-admin-key-shop";
    private static final String ALL_KEY = "test-admin-key-all";
    private static final String CSP =
            "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    @Autowired
    FilterRegistrationBean<AdminKeyFilter> adminKeyFilter;

    private ResponseEntity<String> get(String path, String header, String key) {
        return http.get()
                .uri(path)
                .headers(h -> {
                    if (key != null) {
                        h.set(header, key);
                    }
                })
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    private ResponseEntity<String> getMe(String key) {
        return get("/v1/admin/me", "X-Admin-Key", key);
    }

    @Test
    void meShowsKeyScope() {
        ResponseEntity<String> response = getMe(SHOP_KEY);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.getBody(), "$.keyId")).isEqualTo("admin-shop");
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.apps")).containsExactly("shop");
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.permissions")).isEmpty();
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        OpenApiAssert.assertMatchesContract("GET", "/v1/admin/me", response);
    }

    @Test
    void meOfAllAppsKeyListsSortedAppsAndPayloadPermission() {
        ResponseEntity<String> response = getMe(ALL_KEY);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.apps")).isSorted().contains("shop", "other");
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.permissions")).containsExactly("payload");
        OpenApiAssert.assertMatchesContract("GET", "/v1/admin/me", response);
    }

    @Test
    void missingAndUnknownKeyGetTheSame401(CapturedOutput output) {
        String wrong = "nope-" + java.util.UUID.randomUUID();
        ResponseEntity<String> missing = getMe(null);
        ResponseEntity<String> unknown = getMe(wrong);

        for (ResponseEntity<String> response : List.of(missing, unknown)) {
            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("AdminKey realm=\"reliable-push\"");
            assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
            OpenApiAssert.assertMatchesContract("GET", "/v1/admin/me", response);
        }
        assertThat(unknown.getBody()).isEqualTo(missing.getBody());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<String> lines = output.getOut().lines().filter(l -> l.contains("\"auth.failed\"")).toList();
            assertThat(lines).anySatisfy(l -> assertThat(JsonPath.<String>read(l, "$.reason")).isEqualTo("missing_admin_key"));
            assertThat(lines).anySatisfy(l -> assertThat(JsonPath.<String>read(l, "$.reason")).isEqualTo("unknown_admin_key"));
        });
        assertThat(output.getAll()).doesNotContain(wrong);
    }

    /**
     * The container trims optional whitespace around a header value on the wire, so the exact comparison is exercised
     * on the filter itself with a request whose header keeps its padding.
     */
    @Test
    void paddedKeyIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/me");
        request.addHeader("X-Admin-Key", " " + SHOP_KEY + " ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        adminKeyFilter.getFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void senderKeyIsNotAnAdminKey() {
        assertThat(getMe("test-sender-key-shop").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void adminKeyIsNotAHeaderForTheSenderApi() {
        assertThat(get("/v1/notifications/" + java.util.UUID.randomUUID(), "X-Api-Key", SHOP_KEY).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    void postIs405() {
        ResponseEntity<String> response = http.post()
                .uri("/v1/admin/me")
                .header("X-Admin-Key", SHOP_KEY)
                .retrieve()
                .onStatus(status -> true, (request, resp) -> {})
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void pageIsServedWithHeaders() {
        for (String path : List.of("/admin/", "/admin")) {
            ResponseEntity<String> response = get(path, "X-None", null);

            assertThat(response.getStatusCode().value()).as(path).isEqualTo(200);
            assertThat(response.getHeaders().getContentType().toString()).startsWith("text/html");
            assertThat(response.getHeaders().getFirst("Content-Security-Policy")).isEqualTo(CSP);
            assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
            assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            // revalidated on every load: a tab never mixes old and new modules after an upgrade
            assertThat(response.getHeaders().getFirst("Cache-Control")).as(path).isEqualTo("no-cache");
        }
    }

    @Test
    void pageAssetsAreServedWithTheRightContentType() {
        List<String> scripts = List.of("/admin/js/main.js", "/admin/js/api.js", "/admin/js/dom.js",
                "/admin/js/views/signin.js", "/admin/js/views/overview.js", "/admin/js/views/trends.js",
                "/admin/js/views/search.js", "/admin/js/views/notification.js", "/admin/js/views/devices.js");
        for (String path : scripts) {
            ResponseEntity<String> response = get(path, "X-None", null);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(200);
            assertThat(response.getHeaders().getContentType().toString()).as(path).containsAnyOf("javascript", "ecmascript");
            assertThat(response.getHeaders().getFirst("Content-Security-Policy")).as(path).isEqualTo(CSP);
            assertThat(response.getHeaders().getFirst("Cache-Control")).as(path).isEqualTo("no-cache");
        }
        ResponseEntity<String> css = get("/admin/admin.css", "X-None", null);
        assertThat(css.getStatusCode().value()).isEqualTo(200);
        assertThat(css.getHeaders().getContentType().toString()).startsWith("text/css");
        assertThat(css.getHeaders().getFirst("Cache-Control")).isEqualTo("no-cache");
    }

    @Test
    void assetsReferencedByPageAtAdminWithoutSlashResolve() {
        String html = get("/admin", "X-None", null).getBody();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s(?:href|src)=\"([^\"]+)\"").matcher(html);
        int fetched = 0;
        while (m.find()) {
            String url = m.group(1);
            if (url.startsWith("#")) continue;
            // the browser resolves against http://host/admin: a relative URL would land outside /admin/
            String resolved = url.startsWith("/") ? url : "/" + url;
            ResponseEntity<String> response = get(resolved, "X-None", null);
            assertThat(url).startsWith("/admin/");
            assertThat(response.getStatusCode().value()).as(url).isEqualTo(200);
            String type = response.getHeaders().getContentType().toString();
            assertThat(type).as(url).matches(url.endsWith(".css") ? "text/css.*" : ".*(java|ecma)script.*");
            fetched++;
        }
        assertThat(fetched).isEqualTo(2);
    }
}
