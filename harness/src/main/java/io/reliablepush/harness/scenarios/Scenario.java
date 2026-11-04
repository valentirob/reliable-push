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
     * state. {@link #run} is not used. A successful prepare reserves the runner (other runs get 409) until verify or
     * 120 s.
     *
     * <p>Protocol of {@code scripts/e2e.sh} for the worker-crash scenarios:
     * <ol>
     *   <li>stop {@code worker-b} before prepare, so no second worker can claim or re-claim the row;</li>
     *   <li>{@code POST /console/api/scenarios/<name>/prepare} → {@code {trackingId}}: the receiver will hang the next
     *       {@code shop} call for 8 s (one-shot; shorter than the 10 s webhook http-timeout and the 25 s restart
     *       delay), its mode stays OK;</li>
     *   <li>poll {@code GET /console/api/inbox/in-flight?appId=shop} until {@code trackingIds} contains the id;</li>
     *   <li>{@code docker compose kill worker-a} while that call hangs;</li>
     *   <li>wait 3 s ({@code worker-crash-inside-window}) or 25 s ({@code worker-crash-outside-window}), then start
     *       both workers;</li>
     *   <li>{@code POST /console/api/scenarios/<name>/verify} with {@code {trackingId}}.</li>
     * </ol>
     * Inside the window the re-claimed row is re-sent and the receiver answers 200 at once (FALLBACK_TRIGGERED, two
     * calls with one Idempotency-Key); outside it the row expires and the only call is the hung one.
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
