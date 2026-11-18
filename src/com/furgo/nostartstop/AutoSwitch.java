package com.furgo.nostartstop;

/**
 * Decides when the pending switch of an ignition cycle is sent: once a battery reading received after arming shows the
 * engine charging, and still does 5 s after arming. Readings may only come when the voltage changes, so one is enough.
 * Without readings nothing is sent. Times are elapsedRealtime milliseconds.
 */
final class AutoSwitch {
    static final int RUNNING_CENTIVOLTS = 1300;
    static final long SETTLE_MS = 5_000;
    static final long GIVE_UP_MS = 3 * 60_000;

    static final int WAIT = 0, FIRE = 1, GIVE_UP = 2;

    private boolean armed;
    private long armedAt;
    private int newest;

    void arm(long now) {
        armed = true;
        armedAt = now;
        newest = -1;
    }

    /**
     * An engine start: restarts a pending switch, or arms a new one. Returns true when nothing was pending (it fired,
     * gave up or was never armed), so the crank starts a new cycle.
     */
    boolean onCrank(long now) {
        boolean fresh = !armed;
        arm(now);
        return fresh;
    }

    /** Returns true if a switch was pending. */
    boolean disarm() {
        boolean was = armed;
        armed = false;
        return was;
    }

    boolean armed() {
        return armed;
    }

    /** The newest reading received since arming, or -1. */
    int newest() {
        return newest;
    }

    /** centivolts below 0 means an invalid reading, which is ignored. */
    int onBattery(int centivolts, long now) {
        if (!armed || centivolts < 0) return WAIT;
        newest = centivolts;
        return check(now);
    }

    int onTick(long now) {
        return armed ? check(now) : WAIT;
    }

    private int check(long now) {
        if (now - armedAt >= GIVE_UP_MS) {
            armed = false;
            return GIVE_UP;
        }
        if (newest < RUNNING_CENTIVOLTS || now - armedAt < SETTLE_MS) return WAIT;
        armed = false;
        return FIRE;
    }
}
