package com.furgo.nostartstop;

/**
 * When the head unit counts as woken up. The car turns stop-start on at every engine start, so the automatic switch is
 * sent at most once per ignition cycle: one wake of the head unit, numbered within the current boot.
 */
final class WakeRules {
    /** Wake signals closer together than this in the same boot are one wake, for example boot plus ACC on. */
    static final long DEDUPE_MS = 60_000;
    /** Message 514 arg1 when the head unit is awake again, from decompiled vendor code. */
    static final int POWER_AWAKE = 6;

    private WakeRules() {
    }

    static boolean isNewWake(boolean sameBoot, long lastWakeAt, long now) {
        return !sameBoot || now < lastWakeAt || now - lastWakeAt >= DEDUPE_MS;
    }

    static int nextWake(boolean sameBoot, int count) {
        return sameBoot ? count + 1 : 1;
    }

    static String cycle(String boot, int wake) {
        return boot + ", wake " + wake;
    }

    /** Message 514 arg1: 1 going to sleep, 5 and 9 off or asleep. */
    static boolean isSleepState(int state) {
        return state == 1 || state == 5 || state == 9;
    }
}
