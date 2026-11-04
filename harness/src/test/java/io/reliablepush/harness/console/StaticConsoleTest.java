package io.reliablepush.harness.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Static checks of the console sources: no HTML injection sinks, no external requests, exact banner. */
class StaticConsoleTest {

    static final Path ROOT = Path.of("src/main/resources/static/console");
    static final Pattern SINKS = Pattern.compile(
            "innerHTML|outerHTML|insertAdjacentHTML|document\\.write|\\beval\\s*\\(|new Function\\s*\\(|srcdoc");
    static final Pattern EXTERNAL = Pattern.compile("(?:src|href)\\s*=\\s*[\"']https?:|@import|url\\(\\s*[\"']?(?:https?:)?//"
            + "|from\\s+[\"']https?:|import\\s*\\(\\s*[\"']https?:|fetch\\(\\s*[\"']https?:");

    static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(ROOT)) {
            return s.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void noHtmlInjectionSinksInAnySource() throws Exception {
        assertThat(sources()).hasSizeGreaterThanOrEqualTo(10);
        for (Path p : sources()) {
            assertThat(Files.readString(p, StandardCharsets.UTF_8)).as(p.toString()).doesNotContainPattern(SINKS);
        }
    }

    @Test
    void noExternalRequests() throws Exception {
        for (Path p : sources()) {
            assertThat(Files.readString(p, StandardCharsets.UTF_8)).as(p.toString()).doesNotContainPattern(EXTERNAL);
        }
    }

    @Test
    void indexHasExactBannerAndStatusRole() throws Exception {
        String html = Files.readString(ROOT.resolve("index.html"), StandardCharsets.UTF_8);

        assertThat(html).contains("Test harness — simulated data, not for production");
        assertThat(html).containsPattern("role=\"status\"[^>]*>[^<]*Test harness — simulated data, not for production<");
        assertThat(html).contains("<script type=\"module\" src=\"app.js\">");
        assertThat(html).contains("<link rel=\"stylesheet\" href=\"console.css\">");
    }
}
