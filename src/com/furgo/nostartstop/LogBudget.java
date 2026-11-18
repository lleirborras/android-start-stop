package com.furgo.nostartstop;

/** At most max log lines per 24 hours of elapsedRealtime, since the process may run for days. */
final class LogBudget {
    static final long WINDOW_MS = 24 * 60 * 60_000L;

    private final int max;
    private boolean started;
    private long windowStart;
    private int used;

    LogBudget(int max) {
        this.max = max;
    }

    synchronized boolean take(long now) {
        if (!started || now < windowStart || now - windowStart >= WINDOW_MS) {
            started = true;
            windowStart = now;
            used = 0;
        }
        if (used >= max) return false;
        used++;
        return true;
    }
}
