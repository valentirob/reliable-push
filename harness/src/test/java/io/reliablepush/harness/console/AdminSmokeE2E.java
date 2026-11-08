package io.reliablepush.harness.console;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Drives the ops dashboard ({@code /admin/}) in headless Chromium against a running stack, after the {@code admin-dashboard}
 * scenario created its data (scripts/e2e.sh runs the catalogue first). Base URL {@code -Dadmin.url} (default
 * http://127.0.0.1:8080), admin key from the environment variable {@code HARNESS_ADMINKEY} (never printed). The scenario's
 * user id has a slash and a space, so the search, the hash route and the detail page must encode it correctly.
 */
@Tag("console")
class AdminSmokeE2E {

    /** Must equal AdminDashboard.USER_ID (package-private in the scenario catalogue of the main sources). */
    private static final String USER = "admin dash/e2e user";

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void launch() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void close() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @Test
    void signInSeeOverviewSearchUserOpenTimeline() {
        String url = System.getProperty("admin.url", "http://127.0.0.1:8080");
        String key = System.getenv("HARNESS_ADMINKEY");
        if (key == null || key.isBlank()) {
            throw new AssertionError("HARNESS_ADMINKEY is not set (scripts/e2e.sh exports it from harness/build/e2e/secrets.env)");
        }
        BrowserContext context = browser.newContext();
        try {
            Page page = context.newPage();
            List<String> problems = new CopyOnWriteArrayList<>();
            page.onPageError(e -> problems.add("page error: " + e));
            page.onConsoleMessage(m -> {
                if ("error".equals(m.type())) problems.add("console error: " + m.text());
            });
            page.setDefaultTimeout(10_000);
            page.navigate(url + "/admin/");

            // a pasted key often carries spaces: the sign-in trims it
            // set through evaluate, not fill: a failing fill would print the key in Playwright's call log
            page.locator("#key").evaluate("(el, v) => { el.value = v; }", "  " + key + "  ");
            page.locator("#signin").click();

            assertThat(page.locator("#tiles")).containsText("Registered");
            assertThat(page.locator("#tiles")).containsText("Fallbacks triggered");
            assertThat(page.locator("#backlog")).isVisible();

            page.getByRole(com.microsoft.playwright.options.AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("Search").setExact(true)).click();
            page.locator("#search-app").selectOption("shop");
            page.locator("#search-user").fill(USER);
            page.locator("#search-go").click();
            Locator rows = page.locator("#results tbody tr");
            assertThat(rows).hasCount(2);
            assertThat(rows.first()).containsText(USER);

            rows.first().locator("a[href^='#/n/']").click();
            assertThat(page.locator("#timeline")).isVisible();
            assertThat(page.locator("#timeline li").first()).isVisible();
            assertThat(page.locator("#show-payload")).containsText("Show payload");

            circuitBadgesAndHistory(context, url, key);

            org.assertj.core.api.Assertions.assertThat(problems).as("JavaScript errors in the page").isEmpty();
        } finally {
            context.close();
        }
    }

    private static final String OPEN_SINCE = "2026-11-08T10:00:00Z";

    /**
     * The fallback circuit view. The stack's circuits are CLOSED (the {@code fallback-circuit} scenario closes its app's
     * circuit again), so the CLOSED badge and the history view come from the real API; an OPEN circuit, the
     * {@code fallback-control} permission and the resume answer are staged by intercepting the page's own calls (the
     * harness admin key has only {@code payload}).
     */
    private static void circuitBadgesAndHistory(BrowserContext context, String url, String key) {
        Page plain = context.newPage();
        List<String> problems = new CopyOnWriteArrayList<>();
        plain.onPageError(e -> problems.add("page error: " + e));
        plain.onConsoleMessage(m -> {
            if ("error".equals(m.type())) problems.add("console error: " + m.text());
        });
        plain.setDefaultTimeout(10_000);
        plain.navigate(url + "/admin/");
        plain.locator("#key").evaluate("(el, v) => { el.value = v; }", key);
        plain.locator("#signin").click();
        // the real stack: CLOSED badge, a history view without events and without the resume button
        assertThat(plain.locator(".circuit-badge").first()).containsText("CLOSED");
        plain.locator(".circuit-badge a").first().click();
        assertThat(plain.locator("#circuit-events")).isVisible();
        assertThat(plain.locator("#resume")).hasCount(0);

        // staged: OPEN with 7 suppressed, the key may resume
        Page staged = context.newPage();
        staged.onPageError(e -> problems.add("page error: " + e));
        staged.onConsoleMessage(m -> {
            if ("error".equals(m.type())) problems.add("console error: " + m.text());
        });
        staged.setDefaultTimeout(10_000);
        boolean[] resumed = {false};
        staged.route("**/v1/admin/me", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                .setContentType("application/json")
                .setBody("{\"keyId\":\"e2e\",\"apps\":[\"shop\"],\"permissions\":[\"fallback-control\"]}")));
        staged.route("**/v1/admin/fallback-circuits**", route -> {
            String requestUrl = route.request().url();
            String body;
            if (requestUrl.contains(":resume")) {
                resumed[0] = true;
                body = "{\"appId\":\"shop\",\"state\":\"FORCED\",\"since\":\"" + OPEN_SINCE + "\",\"forcedUntil\":\"2026-11-08T11:00:00Z\","
                        + "\"forcedBy\":\"e2e\",\"suppressed\":7}";
            } else if (requestUrl.contains("/events")) {
                body = "{\"items\":[{\"id\":1,\"at\":\"" + OPEN_SINCE + "\",\"transition\":\"OPENED\",\"reason\":\"tracking_missing\","
                        + "\"due\":12,\"ratio\":0.9,\"suppressed\":null,\"adminKeyId\":null}],\"nextCursor\":null}";
            } else {
                String state = resumed[0] ? "FORCED" : "OPEN";
                String forced = resumed[0] ? "\"2026-11-08T11:00:00Z\"" : "null";
                body = "{\"suspended\":false,\"enabled\":true,\"items\":[{\"appId\":\"shop\",\"state\":\"" + state + "\",\"since\":\""
                        + OPEN_SINCE + "\",\"forcedUntil\":" + forced + ",\"forcedBy\":null,\"suppressed\":7}]}";
            }
            route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setContentType("application/json").setBody(body));
        });
        staged.navigate(url + "/admin/");
        staged.locator("#key").evaluate("(el, v) => { el.value = v; }", key);
        staged.locator("#signin").click();
        assertThat(staged.locator(".circuit-badge").first()).containsText("OPEN since");
        assertThat(staged.locator(".circuit-badge").first()).containsText("7 suppressed");
        staged.locator(".circuit-badge a").first().click();
        assertThat(staged.locator("#circuit-events tbody tr")).hasCount(1);
        assertThat(staged.locator("#circuit-events")).containsText("tracking_missing");
        staged.onDialog(dialog -> dialog.accept());
        staged.locator("#resume").click();
        assertThat(staged.locator("#circuit-state")).containsText("FORCED");
        assertThat(staged.locator("#resume")).hasCount(0);

        org.assertj.core.api.Assertions.assertThat(problems).as("JavaScript errors in the circuit pages").isEmpty();
    }
}
