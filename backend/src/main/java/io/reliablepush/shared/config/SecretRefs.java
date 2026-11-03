package io.reliablepush.shared.config;

import java.util.regex.Pattern;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

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
        String value = readRaw(environment, name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    property + " references environment variable " + name + " which is not set");
        }
        return new Secret(value);
    }

    /**
     * Reads the value verbatim from the property sources. {@code Environment.getProperty} would resolve nested
     * {@code ${...}} placeholders inside the value and, on failure, quote the whole value in its exception; a secret may
     * legitimately contain {@code ${}. Any failure is rethrown without cause or value.
     */
    private static String readRaw(Environment environment, String name) {
        try {
            if (environment instanceof ConfigurableEnvironment configurable) {
                for (PropertySource<?> source : configurable.getPropertySources()) {
                    Object value = source.getProperty(name);
                    if (value != null) {
                        return value.toString();
                    }
                }
                return null;
            }
            return environment.getProperty(name);
        } catch (RuntimeException e) {
            throw new IllegalStateException("environment variable " + name + " could not be read");
        }
    }
}
