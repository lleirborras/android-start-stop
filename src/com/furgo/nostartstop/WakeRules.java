package com.furgo.nostartstop;

/**
 * Ignition cycles. The car turns stop-start on at every engine start, so the automatic switch is sent at most once per
 * cycle: an ignition on or an engine start, numbered within the current boot.
 */
final class WakeRules {
    private WakeRules() {
    }

    /** FIRED is any switch sent, by the app or by a tap. */
    static final int OFF_SEEN = 0, IGNITION_ON_CYCLE = 1, CRANK_CYCLE = 2, FIRED = 3;

    /**
     * The persisted "ignition off seen" flag after an event. A crank cycle or a sent switch clears it, so a short
     * ignition off and on that did not stop the app cannot turn a later restart of the app in the middle of a drive
     * into an ignition on. A cycle started by the flag keeps it until it fires: the service may have been restarted
     * while the head unit was still off, and a later start must still count.
     */
    static boolean ignitionOffAfter(boolean flag, int event) {
        return ignitionOffAfter(flag, event, false);
    }

    /** offNow: the last power state seen was ignition off; a switch sent then keeps the flag for the next ignition on. */
    static boolean ignitionOffAfter(boolean flag, int event, boolean offNow) {
        if (event == OFF_SEEN) return true;
        if (event == FIRED && offNow) return flag;
        if (event == CRANK_CYCLE || event == FIRED) return false;
        return flag;
    }

    static int nextWake(boolean sameBoot, int count) {
        return sameBoot ? count + 1 : 1;
    }

    static String cycle(String boot, int wake) {
        return boot + ", wake " + wake;
    }
}
