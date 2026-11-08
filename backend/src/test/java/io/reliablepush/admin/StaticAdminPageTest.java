package io.reliablepush.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Enforces the security rules of the static admin page (spec section 5) over the classpath files. */
class StaticAdminPageTest {

    private static final String BASE = "admin-ui/";
    private static final List<String> JS = List.of("js/main.js", "js/api.js", "js/dom.js", "js/views/signin.js",
            "js/views/overview.js", "js/views/trends.js", "js/views/search.js", "js/views/notification.js",
            "js/views/devices.js", "js/views/circuit.js");
    private static final List<String> ALL =
            Stream.concat(Stream.of("index.html", "admin.css"), JS.stream()).collect(Collectors.toList());

    private static String read(String name) {
        try (InputStream in = StaticAdminPageTest.class.getClassLoader().getResourceAsStream(BASE + name)) {
            assertThat(in).as(BASE + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void noInlineScriptOrStyle() {
        String html = read("index.html");
        assertThat(Pattern.compile("<script(?![^>]*\\ssrc=)[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html).find())
                .as("inline script").isFalse();
        assertThat(Pattern.compile("<style", Pattern.CASE_INSENSITIVE).matcher(html).find()).as("style element").isFalse();
        assertThat(Pattern.compile("\\sstyle\\s*=", Pattern.CASE_INSENSITIVE).matcher(html).find()).as("style attribute").isFalse();
        assertThat(Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE).matcher(html).find()).as("on* handler").isFalse();
        assertThat(html).contains("type=\"module\"").contains("js/main.js").contains("admin.css");
    }

    @Test
    void searchResultLinksUseTheAppOfTheSearchNotTheSelector() {
        // the selector may have changed since the search: a row's links must point to the app that produced it
        String src = read("js/views/search.js");
        int start = src.indexOf("function addRows(");
        int end = src.indexOf("async function run(", start);
        assertThat(start).isPositive();
        String addRows = src.substring(start, end);
        assertThat(addRows).doesNotContain("app.value").contains("#/devices/").contains("#/n/");
    }

    @Test
    void noInnerHtml() {
        for (String js : JS) {
            String src = read(js);
            for (String bad : List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function")) {
                assertThat(src).as(js + " must not use " + bad).doesNotContain(bad);
            }
            assertThat(src).as(js + " must not set a style attribute").doesNotContain(".style.").doesNotContain("'style'");
        }
    }

    @Test
    void noRemoteReferences() {
        for (String name : ALL) {
            String src = read(name).replace("http://www.w3.org/2000/svg", "");
            assertThat(src).as(name).doesNotContain("http://").doesNotContain("https://");
        }
    }

    @Test
    void keyOnlyInSessionStorage() {
        for (String name : ALL) {
            String src = read(name);
            assertThat(src).as(name).doesNotContain("localStorage");
            if (!name.equals("js/api.js")) {
                assertThat(src).as(name).doesNotContain("sessionStorage");
            }
        }
        assertThat(read("js/api.js")).contains("sessionStorage");
        assertThat(read("js/views/signin.js")).contains(".trim()");
    }

    @Test
    void everyRouteHasAView() {
        String main = read("js/main.js");
        Map<String, String> routes = Map.of(
                "overview", "views/overview.js", "trends", "views/trends.js", "search", "views/search.js",
                "n", "views/notification.js", "devices", "views/devices.js", "circuit", "views/circuit.js");
        routes.forEach((route, view) -> {
            assertThat(main).as("route " + route).contains("'" + route + "'");
            assertThat(main).as("import " + view).contains(view);
        });
        assertThat(main).contains("signin");
    }

    @Test
    void circuitViewIsServedAndWiredToTheApi() {
        String circuit = read("js/views/circuit.js");
        assertThat(circuit).contains("Resume fallbacks").contains("fallback-control").contains("confirm(");
        String api = read("js/api.js");
        assertThat(api).contains("/fallback-circuits").contains(":resume").contains("/events");
        assertThat(read("js/views/overview.js")).contains("#/circuit/").contains("Fallbacks suspended by configuration");
    }

    @Test
    void circuitBadgesNeverDelayTheOverviewAndAFailedResumeReloadsTheState() {
        // a 503 of the circuit route must not hold back the tiles through the retry backoff
        assertThat(read("js/views/overview.js")).contains("fallbackCircuits({ noRetry503: true })");
        assertThat(read("js/api.js")).contains("export const fallbackCircuits = (opts) => request('/fallback-circuits', null, opts)");
        // 409 (suspended or disabled meanwhile) or 404: the state shown is stale, so it is read again
        assertThat(read("js/views/circuit.js")).contains("if (e.status === 409 || e.status === 404) loadCircuit();");
    }

    @Test
    void domHelperUsesTextContentOnly() {
        String dom = read("js/dom.js");
        assertThat(dom).contains("textContent");
        assertThat(dom).doesNotContain("innerHTML");
    }

    @Test
    void assetUrlsAreAbsoluteUnderAdmin() {
        // /admin is forwarded to index.html without a redirect, so a relative URL would resolve outside /admin/**.
        var m = Pattern.compile("\\s(?:href|src)=\"([^\"]*)\"").matcher(read("index.html"));
        int found = 0;
        while (m.find()) {
            String url = m.group(1);
            if (url.startsWith("#")) continue;
            assertThat(url).startsWith("/admin/");
            found++;
        }
        assertThat(found).isGreaterThanOrEqualTo(2);
    }

    @Test
    void stableElementIdsExist() {
        String all = ALL.stream().map(StaticAdminPageTest::read).collect(Collectors.joining("\n"));
        for (String id : List.of("key", "signin", "signout", "app", "window", "tiles", "backlog", "search-user",
                "search-go", "results", "timeline", "show-payload", "payload")) {
            assertThat(all).as("id " + id).containsAnyOf("id=\"" + id + "\"", "id: '" + id + "'", "id: first ? '" + id + "'");
        }
    }
}
