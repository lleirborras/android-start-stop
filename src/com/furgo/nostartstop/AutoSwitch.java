package com.furgo.nostartstop;

/**
 * Decides when the pending switch of an ignition cycle is sent: once a battery reading received after arming (after a
 * crank: after its first 9) shows the engine charging, 5 s have passed since arming, and no crank is in progress.
 * Readings may only come when the voltage changes, so one is enough. Without readings nothing is sent. Times are
 * elapsedRealtime milliseconds.
 */
final class AutoSwitch {
    static final int RUNNING_CENTIVOLTS = 1300;
    static final long SETTLE_MS = 5_000;
    static final long GIVE_UP_MS = 3 * 60_000;
    /** How long a switch waits for another one still being sent. */
    static final long BUSY_MS = 30_000;

    static final int WAIT = 0, FIRE = 1, GIVE_UP = 2;

    private boolean armed;
    private boolean timed;
    private long armedAt;
    private long countFrom;
    private int newest = -1;
    private long newestAt;
    private boolean cranking;
    private long crankStartAt;
    private long deferredSince = -1;

    /**
     * Arms the switch. Without timed it never gives up: after an ignition on noticed by a service start, which may
     * come while the head unit is still off and hours before the engine starts.
     */
    void arm(long now, boolean timed) {
        arm(now, timed, now);
    }

    private void arm(long now, boolean timed, long countFrom) {
        armed = true;
        this.timed = timed;
        armedAt = now;
        this.countFrom = countFrom;
        deferredSince = -1;
    }

    /**
     * State 9: the engine is cranking. Nothing fires until the crank ends, or until it is older than
     * {@link PowerWatch#MAX_CRANK_MS}; a 9 after that is a new crank.
     */
    void onCrankStart(long now) {
        if (cranking && !crankTimedOut(now)) return;
        cranking = true;
        crankStartAt = now;
    }

    private boolean crankTimedOut(long now) {
        return now - crankStartAt > PowerWatch.MAX_CRANK_MS;
    }

    /** A 6 or 1 that does not end the crank as an engine start: only lifts the hold. */
    void crankOver() {
        cranking = false;
    }

    /**
     * A crank ended: arms the switch with the 3-minute limit, counting readings from the crank's first 9. Returns true
     * when the crank starts a new cycle: a switch was sent in the current cycle before this crank (the engine start
     * undid it), or nothing was pending and no switch came during the crank. switchAt is the elapsed time of the last
     * switch in the current cycle, or below 0 if there was none.
     */
    boolean onCrank(long now, long switchAt) {
        long start = cranking ? crankStartAt : now;
        cranking = false;
        boolean before = switchAt >= 0 && switchAt < start;
        boolean during = switchAt >= start;
        boolean fresh = before || !armed && !during;
        arm(now, true, start);
        return fresh;
    }

    /** Returns true if a switch was pending. Also forgets a crank in progress: state 1 ends it. */
    boolean disarm() {
        boolean was = armed;
        armed = false;
        cranking = false;
        return was;
    }

    boolean armed() {
        return armed;
    }

    /** The newest valid reading, or -1. */
    int newest() {
        return newest;
    }

    /**
     * The switch fired while another one was still being sent: keeps it pending to try again. Returns false once it
     * waited {@link #BUSY_MS}, and then it is no longer pending.
     */
    boolean defer(long now) {
        if (deferredSince < 0) deferredSince = now;
        armed = now - deferredSince < BUSY_MS;
        if (!armed) deferredSince = -1;
        return armed;
    }

    boolean deferring() {
        return deferredSince >= 0;
    }

    /** centivolts below 0 means an invalid reading, which is ignored. Readings are kept even while not armed. */
    int onBattery(int centivolts, long now) {
        if (centivolts < 0) return WAIT;
        newest = centivolts;
        newestAt = now;
        return armed ? check(now) : WAIT;
    }

    int onTick(long now) {
        return armed ? check(now) : WAIT;
    }

    private int check(long now) {
        if (timed && now - armedAt >= GIVE_UP_MS) {
            armed = false;
            return GIVE_UP;
        }
        if (cranking && crankTimedOut(now)) cranking = false;
        if (cranking || newest < RUNNING_CENTIVOLTS || newestAt < countFrom || now - armedAt < SETTLE_MS) {
            // A busy fire that is no longer due starts its 30 s afresh the next time.
            deferredSince = -1;
            return WAIT;
        }
        armed = false;
        return FIRE;
    }
}
