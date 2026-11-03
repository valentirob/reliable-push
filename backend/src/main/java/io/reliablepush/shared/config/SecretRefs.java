package io.reliablepush.shared.config;

import java.util.regex.Pattern;
import org.springframework.core.env.Environment;

/**
 * Resolves {@code *-ref} configuration values. Only {@code env:NAME} is supported: the value is read from the Spring
 * {@link Environment} (OS environment variables, system properties, or test properties), so secrets stay out of YAML.
 * A value without the {@code env:} prefix is rejected at startup; error messages never echo the rejected value
 * (it may be an inline secret) and never print a resolved secret.
 */
public final class SecretRefs {

    private static final String PREFIX = "env:";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.\\-]+");

    private SecretRefs() {}

    /** Checks the form {@code env:NAME} without resolving it; returns NAME. {@code property} names the offending key. */
    public static String requireEnvRef(String property, String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException(property + " is required and must have the form env:NAME");
        }
        if (!ref.startsWith(PREFIX) || !NAME.matcher(ref.substring(PREFIX.length())).matches()) {
            throw new IllegalStateException(property
                    + " must have the form env:NAME (secrets and keys are never configured inline); the value was rejected");
        }
        return ref.substring(PREFIX.length());
    }

    /** Resolves {@code env:NAME} to its value; fails if the variable is not set or blank. */
    public static Secret resolve(Environment environment, String property, String ref) {
        String name = requireEnvRef(property, ref);
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    property + " references environment variable " + name + " which is not set");
        }
        return new Secret(value);
    }
}
