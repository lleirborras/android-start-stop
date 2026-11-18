package com.furgo.nostartstop;

/**
 * Decides when the pending switch of a new ignition cycle is sent. Times are elapsedRealtime milliseconds.
 * <ul>
 * <li>After a boot: three battery readings in a row at or above 13.2 V. The head unit boots slowly, so the engine
 * normally runs already.</li>
 * <li>After a wake from sleep: the engine must be seen starting, the first reading below 13.0 V and then three at
 * or above 13.2 V. A first reading of 13.0 V or more means the engine ran before the wake, and nothing is sent.</li>
 * <li>Without readings nothing is sent, except after a boot when the head unit refused the power messages and they
 * never opened since: then the switch goes 15 s after the boot, as before there was a watch.</li>
 * </ul>
 */
final class AutoSwitch {
    static final int RUNNING_CENTIVOLTS = 1320;
    static final int STOPPED_CENTIVOLTS = 1300;
    static final int RUNNING_READINGS = 3;
    static final long FALLBACK_MS = 15_000;
    static final long GIVE_UP_MS = 3 * 60_000;

    static final int WAIT = 0, FIRE = 1, FIRE_FALLBACK = 2, GIVE_UP = 3, ALREADY_RUNNING = 4;

    private boolean armed;
    private boolean bootCycle;
    private long armedAt;
    private boolean sawReading;
    private boolean sawLow;
    private boolean linkOpened;
    private int high;

    void arm(long now, boolean afterBoot) {
        armed = true;
        bootCycle = afterBoot;
        armedAt = now;
        sawReading = false;
        sawLow = false;
        linkOpened = false;
        high = 0;
    }

    /** The power messages opened while armed, so a later failure to reopen them is no reason for the fallback. */
    void linkOpened() {
        linkOpened = true;
    }

    /** Returns true if a switch was pending. */
    boolean disarm() {
        boolean was = armed;
        armed = false;
        return was;
    }

    /** centivolts below 0 means an invalid reading, which is ignored. */
    int onBattery(int centivolts, long now) {
        if (!armed || centivolts < 0) return WAIT;
        if (now - armedAt >= GIVE_UP_MS) {
            armed = false;
            return GIVE_UP;
        }
        sawReading = true;
        if (!bootCycle && !sawLow) {
            if (centivolts < STOPPED_CENTIVOLTS) {
                sawLow = true;
                return WAIT;
            }
            armed = false;
            return ALREADY_RUNNING;
        }
        high = centivolts >= RUNNING_CENTIVOLTS ? high + 1 : 0;
        if (high < RUNNING_READINGS) return WAIT;
        armed = false;
        return FIRE;
    }

    /** powerRefused: the head unit would not open the power messages, so no readings can come. */
    int onTick(long now, boolean powerRefused) {
        if (!armed) return WAIT;
        if (now - armedAt >= GIVE_UP_MS) {
            armed = false;
            return GIVE_UP;
        }
        if (!bootCycle || !powerRefused || linkOpened || sawReading || now - armedAt < FALLBACK_MS) return WAIT;
        armed = false;
        return FIRE_FALLBACK;
    }
}
