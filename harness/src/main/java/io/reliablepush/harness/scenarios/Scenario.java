package io.reliablepush.harness.scenarios;

import java.util.UUID;

/**
 * One end-to-end scenario run against the real backend through the harness components. Implementations are
 * stateless Spring beans (ordered with {@code @Order}); everything they need comes from the {@link ScenarioContext}.
 * A scenario fails by calling {@link ScenarioContext#fail}; any other exception is a failure too.
 */
public interface Scenario {

    /** Unique, stable, kebab-case name (used in reports, REST paths and {@code e2e.sh --scenario}). */
    String name();

    String description();

    /** True when the scenario needs {@code docker compose kill} between steps: driven by {@link External}. */
    boolean externalControl();

    void run(ScenarioContext ctx) throws Exception;

    /**
     * A scenario that a script drives in two steps around an external action (killing and restarting the worker
     * containers): {@link #prepare} sets it up and returns the trackingId to watch, {@link #verify} asserts the end
     * state. {@link #run} is not used.
     */
    interface External extends Scenario {

        @Override
        default boolean externalControl() {
            return true;
        }

        @Override
        default void run(ScenarioContext ctx) {
            throw new UnsupportedOperationException(name() + " is driven by prepare/verify (scripts/e2e.sh)");
        }

        UUID prepare(ScenarioContext ctx) throws Exception;

        void verify(ScenarioContext ctx, UUID trackingId) throws Exception;
    }
}
