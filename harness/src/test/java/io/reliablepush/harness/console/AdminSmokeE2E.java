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

            org.assertj.core.api.Assertions.assertThat(problems).as("JavaScript errors in the page").isEmpty();
        } finally {
            context.close();
        }
    }
}
