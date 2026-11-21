package com.furgo.nostartstop;

import java.util.Arrays;
import java.util.List;

/**
 * Reads the head unit's power state (message 514 arg1) as measured on the van: 1 at ignition off, 4 as it goes to
 * sleep, 5 as it wakes at ignition on, 9 while the engine cranks, then 6 a few seconds later. The unit also answers 6
 * every time the link is opened, so 6 alone means nothing. A crank during the wake is not reported as a 9.
 */
final class PowerWatch {
    static final int STATE_OFF = 1;
    static final int STATE_WAKING = 5;
    static final int STATE_AWAKE = 6;
    static final int STATE_CRANK = 9;
    static final int NONE = 0, IGNITION_OFF = 1, CRANK_END = 2, WAKING = 3;
    /** A 6 this long after the last 9 is not the end of that crank. */
    static final long MAX_CRANK_MS = 30_000;
    static final long CRANK_DEDUPE_MS = 5_000;
    /** How long after ignition off a start still means the app is about to be stopped. A guess, not measured. */
    static final long OFF_HOLD_MS = 60_000;

    /** Why the watch service was started; also used in its log line. */
    static final String BY_APP = "by the app", AT_BOOT = "at boot", AFTER_UPDATE = "after update",
            RESTARTED = "restarted by the system", BY_BROADCAST = "by a broadcast",
            BY_LISTENER = "by the notification listener";
    // Not BY_LISTENER: Android binds the listener again seconds after the head unit stops the app at ignition off,
    // which would look like an ignition on. A service started that way arms on the head unit's wake (5) or on the next crank it sees.
    private static final List<String> WAKE_REASONS = Arrays.asList(BY_APP, AT_BOOT, AFTER_UPDATE, RESTARTED);

    private boolean cranking;
    private long crankAt;
    private boolean crankEnded;
    private long crankEndAt;
    private boolean offSeen;
    private long offAt;

    int onState(int state, long now) {
        if (state == STATE_OFF) {
            cranking = false;
            offSeen = true;
            offAt = now;
            return IGNITION_OFF;
        }
        if (state == STATE_CRANK) {
            offSeen = false;
            cranking = true;
            crankAt = now;
            return NONE;
        }
        if (state == STATE_WAKING) {
            offSeen = false;
            return WAKING;
        }
        if (state != STATE_AWAKE || !cranking) return NONE;
        cranking = false;
        if (now < crankAt || now - crankAt > MAX_CRANK_MS) return NONE;
        if (crankEnded && now - crankEndAt < CRANK_DEDUPE_MS) return NONE;
        crankEnded = true;
        crankEndAt = now;
        return CRANK_END;
    }

    /** The link was closed: the 6 that answers the reopen must not end a crank seen before. */
    void linkClosed() {
        cranking = false;
    }

    /**
     * Whether this watch saw the ignition go off and the app is still about to be stopped. Not after a crank or a wake, and not
     * once OFF_HOLD_MS have passed: a process that outlives the head unit's sleep is started again at ignition on.
     * A 6 does not end it, since the unit answers 6 to any open of the link.
     */
    boolean offSeenHere(long now) {
        return offSeen && now >= offAt && now - offAt < OFF_HOLD_MS;
    }

    /**
     * Whether a start of the watch service is an ignition on: the last state seen was ignition off. The head unit kills
     * the app soon after ignition off and starts it again at ignition on. Only the known reasons count: a broadcast
     * start does not, since any app can send one. Neither does a start while a switch is pending, or one in a service
     * that saw the ignition go off itself moments ago: the app is about to be stopped.
     */
    static boolean startIsIgnitionOn(boolean ignitionOffSeen, String how, boolean pending, boolean offSeenHere) {
        return ignitionOffSeen && WAKE_REASONS.contains(how) && !pending && !offSeenHere;
    }
}
