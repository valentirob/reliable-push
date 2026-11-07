package io.reliablepush.notifications;

import java.util.Optional;

/** Lets another module veto a retention run; absent means retention always runs. */
public interface RetentionGuard {

    /**
     * The reason the retention job deletes nothing this run (logged as {@code retention.skipped} {@code reason}), or
     * empty when it may purge.
     */
    Optional<String> veto();
}
