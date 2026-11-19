package com.furgo.nostartstop;

/**
 * The phases of a tapped switch. A tap first waits for a fresh status frame to pick the bit, then for the echo of the
 * command. After any send the button stays busy for HOLD_MS, so a bounce or double tap cannot flip stop-start back.
 * The caller runs the timers: ANSWER_MS for a frame or an echo, {@link #holdLeft} for the hold, each ending in
 * {@link #timeout}. Times are elapsedRealtime milliseconds.
 */
final class ManualSwitch {
    static final int IDLE = 0, AWAIT_FRAME = 1, AWAIT_ECHO = 2, HOLD = 3;
    static final long ANSWER_MS = 3000, HOLD_MS = 2000;

    /** What the caller does after an event. */
    static final int NOTHING = 0, NO_LINK = 1, QUERY = 2, SEND = 3, CONFIRMED = 4,
            NO_FRAME = 5, NO_ANSWER = 6, SENT = 7;

    private int phase = IDLE;
    private long sentAt;

    int phase() {
        return phase;
    }

    boolean pending() {
        return phase != IDLE;
    }

    /** QUERY: ask for a status frame and wait ANSWER_MS for it. NO_LINK: tell the user. NOTHING: the tap is ignored. */
    int tap(boolean autoSwitching, boolean linkDown) {
        // A second command while one is pending would flip stop-start straight back.
        if (phase != IDLE || autoSwitching) return NOTHING;
        if (linkDown) return NO_LINK;
        phase = AWAIT_FRAME;
        return QUERY;
    }

    /**
     * A status frame arrived. SEND: it is the awaited one, so the command goes out now and {@link #sent} follows if it
     * did. CONFIRMED: it echoes the command, and the hold begins.
     */
    int frame(boolean echo) {
        if (phase == AWAIT_FRAME) {
            phase = IDLE;
            return SEND;
        }
        if (phase == AWAIT_ECHO && echo) {
            phase = HOLD;
            return CONFIRMED;
        }
        return NOTHING;
    }

    /** The command with this bit went out. Returns the new phase: HOLD, or AWAIT_ECHO to wait ANSWER_MS for the echo. */
    int sent(int bit, long now) {
        sentAt = now;
        // A cleared echo looks the same as no answer, so it cannot confirm this command.
        phase = bit == 0 ? HOLD : AWAIT_ECHO;
        return phase;
    }

    long holdLeft(long now) {
        return Math.max(0, sentAt + HOLD_MS - now);
    }

    /** The timer of the current phase ran out. NO_FRAME, NO_ANSWER or SENT (the hold is over); NOTHING when idle. */
    int timeout() {
        int was = phase;
        phase = IDLE;
        return was == AWAIT_FRAME ? NO_FRAME : was == AWAIT_ECHO ? NO_ANSWER : was == HOLD ? SENT : NOTHING;
    }

    /** The screen is left: its timers are gone. */
    void pause() {
        phase = IDLE;
    }

    /** The screen is back. Returns true when a hold cut short by {@link #pause} goes on. */
    boolean resume(long now) {
        if (sentAt == 0 || now >= sentAt + HOLD_MS) return false;
        phase = HOLD;
        return true;
    }
}
