package com.furgo.nostartstop;

/**
 * Decides when the pending switch of a new ignition cycle is sent: once the battery shows the engine charging, or after a
 * short wait when the head unit gives no battery readings at all. Times are elapsedRealtime milliseconds.
 */
final class AutoSwitch {
    static final int RUNNING_CENTIVOLTS = 1320;
    static final int RUNNING_READINGS = 3;
    static final long FALLBACK_MS = 15_000;
    static final long GIVE_UP_MS = 10 * 60_000;

    static final int WAIT = 0, FIRE = 1, FIRE_FALLBACK = 2, GIVE_UP = 3;

    private boolean armed;
    private long armedAt;
    private boolean sawBattery;
    private int high;

    void arm(long now) {
        armed = true;
        armedAt = now;
        sawBattery = false;
        high = 0;
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

    /** centivolts below 0 means an invalid reading, which is ignored. */
    int onBattery(int centivolts, long now) {
        if (!armed || centivolts < 0) return WAIT;
        if (now - armedAt >= GIVE_UP_MS) {
            armed = false;
            return GIVE_UP;
        }
        sawBattery = true;
        high = centivolts >= RUNNING_CENTIVOLTS ? high + 1 : 0;
        if (high < RUNNING_READINGS) return WAIT;
        armed = false;
        return FIRE;
    }

    int onTick(long now) {
        if (!armed) return WAIT;
        if (now - armedAt >= GIVE_UP_MS) {
            armed = false;
            return GIVE_UP;
        }
        if (sawBattery || now - armedAt < FALLBACK_MS) return WAIT;
        armed = false;
        return FIRE_FALLBACK;
    }
}
