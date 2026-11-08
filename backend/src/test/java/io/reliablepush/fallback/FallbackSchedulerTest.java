package io.reliablepush.fallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.reliablepush.fallback.circuit.Circuit;
import io.reliablepush.fallback.circuit.CircuitBreaker;
import io.reliablepush.fallback.circuit.CircuitState;
import io.reliablepush.fallback.circuit.Suppressor;
import io.reliablepush.notifications.ClaimedNotification;
import io.reliablepush.notifications.FallbackPolicy;
import io.reliablepush.notifications.NotificationRepository;
import io.reliablepush.notifications.NotificationState;
import io.reliablepush.shared.logging.EventCapture;
import io.reliablepush.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * The scheduler without a database: the stop flag (no claim after {@code requestStop()}, rows in flight are finished)
 * and the order of a tick (evaluate, suppress, claim without the suppressed apps; fail closed when the check fails).
 */
class FallbackSchedulerTest {

    private static final Instant NOW = Instant.parse("2100-01-01T00:00:00Z");
    private static final Duration LONG = Duration.ofSeconds(30);

    private static final List<String> APPS = List.of("shop", "other");
    private static final Duration POLL = Duration.ofSeconds(5);

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final FallbackDispatcher dispatcher = mock(FallbackDispatcher.class);
    private final CircuitBreaker breaker = mock(CircuitBreaker.class);
    private final Suppressor suppressor = mock(Suppressor.class);

    private FallbackScheduler scheduler() {
        return scheduler(false);
    }

    private FallbackScheduler scheduler(boolean suspended) {
        // batch 1 / concurrency 1: a claim that returns its row comes back "full", so the loop would claim again
        return scheduler(suspended, Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    private FallbackScheduler scheduler(boolean suspended, Clock clock) {
        return new FallbackScheduler(notifications, dispatcher, breaker, suppressor, APPS, suspended, clock,
                Duration.ofMinutes(2), POLL, 1, 1);
    }

    private static Map<String, Circuit> circuits() {
        return Map.of("shop", new Circuit("shop", CircuitState.OPEN, NOW, null, null, 0),
                "other", Circuit.closed("other", Instant.EPOCH));
    }

    private static ClaimedNotification row() {
        return new ClaimedNotification(UUID.randomUUID(), "shop", "u", FallbackPolicy.NOT_DELIVERED, "{}", NOW, 0,
                NotificationState.PENDING, false, NOW.plusSeconds(120));
    }

    @BeforeEach
    void closedCircuits() {
        when(breaker.evaluate(any(), any())).thenReturn(Map.of());
        when(suppressor.suppress(any(), anyBoolean(), any())).thenReturn(Set.of());
    }

    @Test
    void noClaimAfterStopRequested() {
        FallbackScheduler scheduler = scheduler();

        scheduler.requestStop();
        int claimed = scheduler.tick();

        assertThat(claimed).isZero();
        verify(notifications, never()).claimDue(any(), any(), anyInt(), any());
        assertThat(scheduler.awaitIdle(LONG)).isTrue();
    }

    @Test
    void stopDuringTickFinishesRowsInFlight() throws Exception {
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch processed = new CountDownLatch(1);
        when(notifications.claimDue(any(), any(), anyInt(), any())).thenReturn(List.of(row()));
        doAnswer(invocation -> {
            inFlight.countDown();
            release.await();
            processed.countDown();
            return null;
        }).when(dispatcher).process(any());
        FallbackScheduler scheduler = scheduler();

        CompletableFuture<Integer> tick = CompletableFuture.supplyAsync(scheduler::tick);
        assertThat(inFlight.await(30, TimeUnit.SECONDS)).isTrue();
        scheduler.requestStop();

        // the tick is held by the row in flight: it is neither idle nor finished while the call is open
        assertThat(scheduler.awaitIdle(Duration.ofMillis(100))).isFalse();
        assertThat(tick).isNotDone();
        assertThat(scheduler.rowsInFlight()).isEqualTo(1);

        release.countDown();

        assertThat(tick.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(processed.getCount()).isZero(); // the row ran to its end, it was not abandoned
        assertThat(scheduler.awaitIdle(LONG)).isTrue();
        assertThat(scheduler.rowsInFlight()).isZero();
        verify(notifications, times(1)).claimDue(any(), any(), anyInt(), any()); // no second claim although the first was full
    }

    @Test
    void awaitIdleWaitsForEveryTickWhenTicksOverlap() throws Exception {
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // the first claim (tick A) gets a row, every later claim (tick B, then A's next claim) finds nothing
        when(notifications.claimDue(any(), any(), anyInt(), any())).thenReturn(List.of(row())).thenReturn(List.of());
        doAnswer(invocation -> {
            inFlight.countDown();
            release.await();
            return null;
        }).when(dispatcher).process(any());
        FallbackScheduler scheduler = scheduler();

        CompletableFuture<Integer> tickA = CompletableFuture.supplyAsync(scheduler::tick);
        assertThat(inFlight.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.tick()).isZero(); // tick B starts and ends while A still runs

        // A is still running: the scheduler is not idle although the last tick to end was B
        assertThat(scheduler.awaitIdle(Duration.ofMillis(100))).isFalse();

        release.countDown();
        assertThat(tickA.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(scheduler.awaitIdle(LONG)).isTrue();
    }

    @Test
    void tickEvaluatesThenSuppressesThenClaimsWithoutTheSuppressedApps() {
        when(breaker.evaluate(eq(APPS), any())).thenReturn(circuits());
        when(suppressor.suppress(circuits(), false, NOW)).thenReturn(Set.of("shop"));
        when(notifications.claimDue(any(), any(), anyInt(), any())).thenReturn(List.of());

        assertThat(scheduler().tick()).isZero();

        InOrder order = inOrder(breaker, suppressor, notifications);
        order.verify(breaker).evaluate(APPS, NOW);
        order.verify(suppressor).suppress(circuits(), false, NOW);
        order.verify(notifications).claimDue(eq(NOW), eq(NOW.plusSeconds(120)), eq(1), eq(Set.of("shop")));
    }

    @Test
    void claimTakesOnlyTheRowsDueAtTheEvaluationInstant() {
        MutableClock clock = new MutableClock(NOW);
        // the check waits 1 s for the detector lock: rows coming due meanwhile were not evaluated
        when(breaker.evaluate(APPS, NOW)).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(1));
            return circuits();
        });
        when(suppressor.suppress(circuits(), false, NOW)).thenReturn(Set.of("shop"));
        when(notifications.claimDue(any(), any(), anyInt(), any())).thenReturn(List.of());

        scheduler(false, clock).tick();

        // due cut-off: the evaluation instant (what the circuits saw); lease: from the real now
        verify(notifications).claimDue(eq(NOW), eq(NOW.plusSeconds(121)), eq(1), eq(Set.of("shop")));
    }

    @Test
    void suspendedReachesTheSuppressor() {
        when(breaker.evaluate(eq(APPS), any())).thenReturn(circuits());
        when(suppressor.suppress(circuits(), true, NOW)).thenReturn(Set.copyOf(APPS));
        when(notifications.claimDue(any(), any(), anyInt(), any())).thenReturn(List.of());

        scheduler(true).tick();

        verify(notifications).claimDue(any(), any(), anyInt(), eq(Set.copyOf(APPS)));
    }

    @Test
    void failedCircuitCheckClaimsNothing() {
        when(breaker.evaluate(eq(APPS), any())).thenThrow(new CannotGetJdbcConnectionException("down"));

        try (EventCapture capture = new EventCapture();
                EventCapture classLog = new EventCapture(FallbackScheduler.class.getName())) {
            assertThat(scheduler().tick()).isZero();

            assertThat(capture.events()).singleElement().satisfies(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(EventCapture.data(e))
                        .containsEntry("event.action", "fallback.circuit_check_failed")
                        .containsEntry("reason", "CannotGetJdbcConnectionException");
            });
            // the stack trace follows at ERROR from the class logger
            assertThat(classLog.events()).singleElement().satisfies(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getThrown()).isInstanceOf(CannotGetJdbcConnectionException.class);
            });
        }
        verify(suppressor, never()).suppress(any(), anyBoolean(), any());
        verify(notifications, never()).claimDue(any(), any(), anyInt(), any());
    }

    @Test
    void failedSuppressionClaimsNothing() {
        when(breaker.evaluate(eq(APPS), any())).thenReturn(circuits());
        when(suppressor.suppress(any(), anyBoolean(), any())).thenThrow(new IllegalStateException("boom"));

        try (EventCapture capture = new EventCapture()) {
            assertThat(scheduler().tick()).isZero();

            assertThat(capture.events()).singleElement().satisfies(e -> assertThat(EventCapture.data(e))
                    .containsEntry("event.action", "fallback.circuit_check_failed")
                    .containsEntry("reason", "IllegalStateException"));
        }
        verify(notifications, never()).claimDue(any(), any(), anyInt(), any());
    }

    @Test
    void longDrainChecksTheCircuitsAgainAfterOnePollInterval() {
        MutableClock clock = new MutableClock(NOW);
        when(breaker.evaluate(eq(APPS), any())).thenReturn(circuits());
        when(suppressor.suppress(eq(circuits()), eq(false), any())).thenReturn(Set.of(), Set.of("shop"));
        when(notifications.claimDue(any(), any(), anyInt(), any()))
                .thenReturn(List.of(row()), List.of(row()), List.of());
        doAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(3)); // each webhook call takes 3 s
            return null;
        }).when(dispatcher).process(any());

        assertThat(scheduler(false, clock).tick()).isEqualTo(2);

        // claims at 0 s (checked), 3 s (last check 3 s ago: none), 6 s (6 s >= poll interval: checked again); each
        // claim takes only the rows due by the last check, with a lease from the real now
        verify(breaker).evaluate(APPS, NOW);
        verify(breaker).evaluate(APPS, NOW.plusSeconds(6));
        verify(suppressor).suppress(circuits(), false, NOW);
        verify(suppressor).suppress(circuits(), false, NOW.plusSeconds(6));
        InOrder claims = inOrder(notifications);
        claims.verify(notifications).claimDue(eq(NOW), eq(NOW.plusSeconds(120)), anyInt(), eq(Set.of()));
        claims.verify(notifications).claimDue(eq(NOW), eq(NOW.plusSeconds(123)), anyInt(), eq(Set.of()));
        claims.verify(notifications).claimDue(eq(NOW.plusSeconds(6)), eq(NOW.plusSeconds(126)), anyInt(),
                eq(Set.of("shop")));
    }

    @Test
    void failedCheckDuringADrainStopsClaiming() {
        MutableClock clock = new MutableClock(NOW);
        when(breaker.evaluate(eq(APPS), any()))
                .thenReturn(circuits())
                .thenThrow(new CannotGetJdbcConnectionException("down"));
        when(notifications.claimDue(any(), any(), anyInt(), any())).thenReturn(List.of(row()));
        doAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(6));
            return null;
        }).when(dispatcher).process(any());

        try (EventCapture capture = new EventCapture()) {
            assertThat(scheduler(false, clock).tick()).isEqualTo(1); // the row claimed before still ran

            assertThat(capture.events()).singleElement().satisfies(e -> assertThat(EventCapture.data(e))
                    .containsEntry("event.action", "fallback.circuit_check_failed"));
        }
        verify(notifications, times(1)).claimDue(any(), any(), anyInt(), any());
        verify(dispatcher, times(1)).process(any());
    }

    @Test
    void stopRequestedDuringTheRecheckOfALongDrainClaimsNothingMore() {
        MutableClock clock = new MutableClock(NOW);
        FallbackScheduler scheduler = scheduler(false, clock);
        when(breaker.evaluate(eq(APPS), any())).thenReturn(circuits()).thenAnswer(invocation -> {
            scheduler.requestStop(); // the shutdown arrives while the second check runs
            return circuits();
        });
        when(notifications.claimDue(any(), any(), anyInt(), any())).thenReturn(List.of(row()));
        doAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(6));
            return null;
        }).when(dispatcher).process(any());

        assertThat(scheduler.tick()).isEqualTo(1);

        verify(notifications, times(1)).claimDue(any(), any(), anyInt(), any());
    }

    @Test
    void noCircuitCheckAfterStopRequested() {
        FallbackScheduler scheduler = scheduler();
        scheduler.requestStop();

        scheduler.tick();

        verify(breaker, never()).evaluate(any(), any());
    }

    @Test
    void awaitIdleReturnsAtOnceWithoutTick() {
        assertThat(scheduler().awaitIdle(Duration.ofMillis(1))).isTrue();
    }
}
