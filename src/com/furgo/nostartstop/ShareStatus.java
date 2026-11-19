package com.furgo.nostartstop;

/** The line under the log: what diagnostics sharing is doing right now. */
final class ShareStatus {
    /** How long "Sent N lines" shows before it turns into "All sent". */
    static final long SENT_MS = 8000;

    /** The dot: RING is the empty ring of sharing off. */
    static final int RING = 0, OK = 1, WAITING = 2, FAILED = 3;

    final String text;
    /** The text in the bright colour instead of the muted one. */
    final boolean bright;
    final int dot;
    final boolean pulse;
    /** The part of the rule above the line that is filled, 0 to 1. */
    final float fill;
    /** Milliseconds after which the line changes by itself, or -1. */
    final long recheckMs;

    private ShareStatus(String text, boolean bright, int dot, boolean pulse, float fill, long recheckMs) {
        this.text = text;
        this.bright = bright;
        this.dot = dot;
        this.pulse = pulse;
        this.fill = fill;
        this.recheckMs = recheckMs;
    }

    /**
     * on is the Share diagnostics switch; the rest are the values of the same names in {@link Telemetry}, with waiting
     * below 0 before the lines were counted. now and lastSentAt are wall-clock milliseconds.
     */
    static ShareStatus of(boolean on, boolean sending, int sendDone, int sendTotal, boolean failed, int waiting,
            int waitingDays, int lastSent, long lastSentAt, long now) {
        int n = waiting;
        long sentAt = lastSentAt;
        String s;
        boolean bright = false;
        int dot = OK;
        boolean pulse = false;
        float fill = 0;
        long recheckMs = -1;
        if (!on) {
            s = "Diagnostics off";
            dot = RING;
        } else if (sending) {
            int done = sendDone, total = sendTotal;
            s = done > 0 && total > done ? "Sending: " + done + " of " + lines(total)
                    : n > 0 ? "Sending " + lines(n) + "…" : "Sending…";
            fill = total > 0 ? Math.min(1f, (float) done / total) : 0;
            bright = true;
            pulse = true;
        } else if (failed && n > 0) {
            s = "Send failed, " + stored(n, waitingDays) + " waiting";
            dot = FAILED;
        } else if (n > 0) {
            s = stored(n, waitingDays) + " waiting to send";
            dot = WAITING;
        } else if (sentAt > 0) {
            long age = now - sentAt;
            if (age >= 0 && age < SENT_MS) {
                s = "Sent " + lines(lastSent) + " at " + Feedback.clock(sentAt);
                bright = true;
                recheckMs = SENT_MS - age;
            } else {
                s = "All sent at " + Feedback.clock(sentAt);
            }
        } else {
            s = n < 0 ? "Diagnostics on" : "Diagnostics on, nothing waiting";
        }
        return new ShareStatus(s, bright, dot, pulse, fill, recheckMs);
    }

    /** For example "1240 lines from 3 days". */
    private static String stored(int n, int days) {
        return days > 1 ? lines(n) + " from " + days + " days" : lines(n);
    }

    private static String lines(int n) {
        return n == 1 ? "1 line" : n + " lines";
    }
}
