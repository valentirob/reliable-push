package io.reliablepush.notifications;

/** Lets another module veto a retention run; absent means retention always runs. */
public interface RetentionGuard {

    /** {@code false}: the retention job deletes nothing this run. */
    boolean allowPurge();
}
