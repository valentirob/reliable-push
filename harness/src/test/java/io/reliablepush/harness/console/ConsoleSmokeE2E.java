package io.reliablepush.harness.console;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Drives the real console in headless Chromium against a running stack (scripts/demo.sh, or scripts/e2e.sh which runs
 * this test before tearing its stack down). Run with {@code ./mvnw -B -pl harness -am verify -Pconsole-smoke}; the base
 * URL is {@code -Dconsole.url} (default http://127.0.0.1:8090). Fast clock expected (1 product minute = 1 s).
 */
@Tag("console")
class ConsoleSmokeE2E {

    private static final String APP = "shop";
    /** The accessible name of the app select also contains its selected option ("Appshop" or "App shop"). */
    private static final Pattern APP_LABEL = Pattern.compile("^App");
    private static final Pattern TRACKING_ID = Pattern.compile("Tracking id: ([0-9a-f-]{36})");

    private static Playwright playwright;
    private static Browser browser;
    private BrowserContext context;

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
    void registerDeviceSendPushSeeDeliveredAndFallback() {
        String url = System.getProperty("console.url", "http://127.0.0.1:8090");
        String user = "ui-" + UUID.randomUUID().toString().substring(0, 8);
        context = browser.newContext();
        try {
            Page page = context.newPage();
            List<String> problems = new CopyOnWriteArrayList<>();
            page.onPageError(e -> problems.add("page error: " + e));
            page.onConsoleMessage(m -> {
                if ("error".equals(m.type())) problems.add("console error: " + m.text());
            });
            page.setDefaultTimeout(5_000);
            page.navigate(url);

            // banner
            assertThat(page.getByText("Test harness — simulated data, not for production")).isVisible();

            // Devices: Android v1 device for a fresh user
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Devices").setExact(true)).click();
            Locator form = page.locator("form").filter(new Locator.FilterOptions().setHasText("Register a simulated device"));
            form.getByLabel(APP_LABEL).selectOption(APP);
            form.getByLabel("User id").fill(user);
            form.getByLabel("Platform").selectOption("ANDROID");
            form.getByLabel("Android mode").selectOption("V1");
            form.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Register device")).click();
            assertThat(page.getByRole(AriaRole.ROW).filter(new Locator.FilterOptions().setHasText(user)))
                    .containsText("ANDROID V1");

            // Send push (deliver:1s) -> DELIVERED in Live tracking within 10 s
            String delivered = sendPush(page, user, "deliver:1s");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Live tracking")).click();
            selectApp(page);
            Locator deliveredRow = page.getByRole(AriaRole.ROW).filter(new Locator.FilterOptions().setHasText(delivered.substring(0, 8)));
            assertThat(deliveredRow.locator(".badge")).hasText("DELIVERED", new com.microsoft.playwright.assertions.LocatorAssertions.HasTextOptions().setTimeout(10_000));

            // Fallback inbox: the scenario runner resets the receiver after every scenario; the UI must show it answering OK
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Fallback inbox")).click();
            selectApp(page);
            assertThat(page.getByRole(AriaRole.STATUS).filter(new Locator.FilterOptions().setHasText("Receiver mode: OK")))
                    .isVisible();

            // Send push (never) -> the fallback webhook shows up in the inbox, signature valid, within 20 s
            String never = sendPush(page, user, "never");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Fallback inbox")).click();
            selectApp(page);
            Locator hook = page.getByRole(AriaRole.ROW).filter(new Locator.FilterOptions().setHasText(never));
            assertThat(hook).hasCount(1, new com.microsoft.playwright.assertions.LocatorAssertions.HasCountOptions().setTimeout(20_000));
            assertThat(hook).containsText("valid");
            assertThat(hook).not().containsText("INVALID");
            org.assertj.core.api.Assertions.assertThat(problems).as("JavaScript errors in the console").isEmpty();
        } finally {
            context.close();
        }
    }

    private static void selectApp(Page page) {
        page.getByLabel(APP_LABEL).selectOption(APP);
    }

    /** Sends a push to the user's device with the given behaviour (5 product minutes = 5 s timeout); returns its tracking id. */
    private static String sendPush(Page page, String user, String behaviour) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Send push")).first().click();
        Locator form = page.locator("form").filter(new Locator.FilterOptions().setHasText("Per-device behaviour"));
        form.getByLabel(APP_LABEL).selectOption(APP);
        form.getByLabel("User id").fill(user);
        form.getByLabel("User id").dispatchEvent("change");
        form.getByLabel("Timeout (minutes)").fill("5");
        // the per-device behaviour select appears once the devices of the user are loaded
        form.getByLabel("Behaviour").selectOption(behaviour);
        form.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Send push")).click();
        Locator sent = page.getByText("Tracking id:");
        assertThat(sent).isVisible();
        Matcher m = TRACKING_ID.matcher(sent.innerText());
        if (!m.find()) throw new AssertionError("no tracking id in: " + sent.innerText());
        return m.group(1);
    }
}
