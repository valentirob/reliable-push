package io.reliablepush.harness.scenarios;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Backend secrets the harness can see in its own environment (the E2E/demo stack hands the harness the same
 * {@code secrets.env} as the backend), for the log-hygiene scan: every device-token signing private key PEM
 * ({@code RELIABLEPUSH_DEVICETOKEN_SIGNINGKEYS_<n>_PRIVATEKEYPEM}, or the variable its {@code ..._PRIVATEKEYPEMREF=env:<NAME>}
 * points to) and the database password ({@code SPRING_DATASOURCE_PASSWORD}). Each PEM is listed as written, with
 * escaped {@code \n} decoded, and as its base64 body without header, footer or line breaks. Absent or unresolvable
 * values (for example {@code file:} references) are skipped silently. Never log or return the result.
 */
final class EnvironmentSecrets {

    private static final Pattern PRIVATE_KEY_PEM = Pattern.compile("RELIABLEPUSH_DEVICETOKEN_SIGNINGKEYS_\\d+_PRIVATEKEYPEM");
    private static final Pattern PRIVATE_KEY_REF = Pattern.compile("RELIABLEPUSH_DEVICETOKEN_SIGNINGKEYS_\\d+_PRIVATEKEYPEMREF");
    private static final Pattern PEM_ARMOR = Pattern.compile("-----(BEGIN|END) [A-Z ]+-----");
    static final String DATABASE_PASSWORD = "SPRING_DATASOURCE_PASSWORD";

    private EnvironmentSecrets() {}

    static List<String> fromProcessEnvironment() {
        try {
            return from(System.getenv());
        } catch (SecurityException e) {
            return List.of();
        }
    }

    static List<String> from(Map<String, String> env) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            String pem = null;
            if (PRIVATE_KEY_PEM.matcher(e.getKey()).matches()) {
                pem = e.getValue();
            } else if (PRIVATE_KEY_REF.matcher(e.getKey()).matches() && e.getValue() != null
                    && e.getValue().startsWith("env:")) {
                pem = env.get(e.getValue().substring("env:".length()));
            }
            addPem(out, pem);
        }
        String db = env.get(DATABASE_PASSWORD);
        if (db != null && !db.isBlank()) {
            out.add(db);
        }
        return new ArrayList<>(out);
    }

    private static void addPem(Set<String> out, String pem) {
        if (pem == null || pem.isBlank()) {
            return;
        }
        String decoded = pem.replace("\\n", "\n");
        out.add(pem);
        out.add(decoded);
        out.add(decoded.strip());
        String body = PEM_ARMOR.matcher(decoded).replaceAll("").replaceAll("\\s", "");
        if (!body.isEmpty()) {
            out.add(body);
        }
    }
}
