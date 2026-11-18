package com.furgo.nostartstop;

/**
 * Ignition cycles. The car turns stop-start on at every engine start, so the automatic switch is sent at most once per
 * cycle: an ignition on or an engine start, numbered within the current boot.
 */
final class WakeRules {
    private WakeRules() {
    }

    /**
     * The persisted "ignition off seen" flag after an event. Any new cycle clears it, so a short ignition off and on
     * that did not stop the app cannot turn a later restart of the app in the middle of a drive into an ignition on.
     */
    static boolean ignitionOffAfter(boolean flag, boolean ignitionOff, boolean cycleStarted) {
        if (ignitionOff) return true;
        return !cycleStarted && flag;
    }

    static int nextWake(boolean sameBoot, int count) {
        return sameBoot ? count + 1 : 1;
    }

    static String cycle(String boot, int wake) {
        return boot + ", wake " + wake;
    }
}
