package io.reliablepush.harness.scenarios;

/** Thrown by {@link ScenarioContext#fail}: the scenario's assertion did not hold. Carries no stack-worthy cause. */
public final class ScenarioFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ScenarioResult.Failure failure;

    ScenarioFailure(ScenarioResult.Failure failure) {
        super("expected " + failure.expected() + ", actual " + failure.actual(), null, false, false);
        this.failure = failure;
    }

    public ScenarioResult.Failure failure() {
        return failure;
    }
}
