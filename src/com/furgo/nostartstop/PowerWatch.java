package com.furgo.nostartstop;

/**
 * Reads the head unit's power state (message 514 arg1) as measured on the van: 1 at ignition off, 9 while the engine
 * cranks, then 6 a few seconds later. The unit also answers 6 every time the link is opened, so 6 alone means nothing.
 */
final class PowerWatch {
    static final int STATE_OFF = 1;
    static final int STATE_AWAKE = 6;
    static final int STATE_CRANK = 9;
    static final int NONE = 0, IGNITION_OFF = 1, CRANK_END = 2;
    /** A 6 this long after the last 9 is not the end of that crank. */
    static final long MAX_CRANK_MS = 30_000;
    static final long CRANK_DEDUPE_MS = 10_000;

    private boolean cranking;
    private long crankAt;
    private boolean crankEnded;
    private long crankEndAt;

    int onState(int state, long now) {
        if (state == STATE_OFF) {
            cranking = false;
            return IGNITION_OFF;
        }
        if (state == STATE_CRANK) {
            cranking = true;
            crankAt = now;
            return NONE;
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
     * Whether a start of the watch service is an ignition on: the last state seen was ignition off. The head unit kills
     * the app soon after ignition off and starts it again at ignition on. A start by a broadcast does not count, since
     * any app can send one.
     */
    static boolean startIsIgnitionOn(boolean ignitionOffSeen, boolean byBroadcast) {
        return ignitionOffSeen && !byBroadcast;
    }
}
