package com.furgo.nostartstop;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class ManualSwitchTest {
    private static final int IDLE = ManualSwitch.IDLE, AWAIT_FRAME = ManualSwitch.AWAIT_FRAME,
            AWAIT_ECHO = ManualSwitch.AWAIT_ECHO, HOLD = ManualSwitch.HOLD;
    private static final int NOTHING = ManualSwitch.NOTHING, NO_LINK = ManualSwitch.NO_LINK,
            QUERY = ManualSwitch.QUERY, SEND = ManualSwitch.SEND, CONFIRMED = ManualSwitch.CONFIRMED,
            NO_FRAME = ManualSwitch.NO_FRAME, NO_ANSWER = ManualSwitch.NO_ANSWER, SENT = ManualSwitch.SENT;
    private static final long HOLD_MS = ManualSwitch.HOLD_MS;

    public static void main(String[] args) {
        same("answer limit", ManualSwitch.ANSWER_MS, 3000);
        same("hold", HOLD_MS, 2000);

        // Idle: frames and a stray timer do nothing.
        ManualSwitch m = new ManualSwitch();
        same("starts idle", m.phase(), IDLE);
        check("idle is not pending", !m.pending());
        same("idle: frame", m.frame(false), NOTHING);
        same("idle: echo", m.frame(true), NOTHING);
        same("idle: timer", m.timeout(), NOTHING);
        same("idle: still idle", m.phase(), IDLE);
        check("idle: nothing sent, no hold to resume", !m.resume(1_000));
        same("idle: resume leaves it idle", m.phase(), IDLE);

        // A tap that sends nothing.
        same("tap while the automatic switch is sending", m.tap(true, false), NOTHING);
        same("ignored tap leaves it idle", m.phase(), IDLE);
        same("tap with no link", m.tap(false, true), NO_LINK);
        same("no link leaves it idle", m.phase(), IDLE);
        same("automatic switch comes before no link", m.tap(true, true), NOTHING);

        // Tap, frame, command with bit 1, echo, hold.
        same("tap", m.tap(false, false), QUERY);
        same("tap waits for a frame", m.phase(), AWAIT_FRAME);
        check("waiting for a frame is pending", m.pending());
        same("second tap ignored", m.tap(false, false), NOTHING);
        same("tap with no link ignored while pending", m.tap(false, true), NOTHING);
        same("second tap changes nothing", m.phase(), AWAIT_FRAME);
        same("any frame is the awaited one", m.frame(false), SEND);
        same("idle until the command went out", m.phase(), IDLE);
        same("bit 1 waits for the echo", m.sent(1, 10_000), AWAIT_ECHO);
        same("phase after bit 1", m.phase(), AWAIT_ECHO);
        same("tap while waiting for the echo", m.tap(false, false), NOTHING);
        same("frame without the echo", m.frame(false), NOTHING);
        same("still waiting for the echo", m.phase(), AWAIT_ECHO);
        same("echo confirms", m.frame(true), CONFIRMED);
        same("hold after the echo", m.phase(), HOLD);
        same("hold counts from the send", m.holdLeft(10_500), HOLD_MS - 500);
        same("hold left at its end", m.holdLeft(10_000 + HOLD_MS), 0);
        same("hold left is never negative", m.holdLeft(10_000 + HOLD_MS + 1), 0);
        same("tap during the hold", m.tap(false, false), NOTHING);
        same("echo during the hold", m.frame(true), NOTHING);
        same("hold not ended by a frame", m.phase(), HOLD);
        same("end of the hold", m.timeout(), SENT);
        same("idle after the hold", m.phase(), IDLE);
        same("tap after the hold", m.tap(false, false), QUERY);

        // An echoed frame is the awaited one too.
        same("frame with the echo set", m.frame(true), SEND);

        // The command could not be sent, or the automatic switch took over: sent() is not called.
        same("idle when nothing was sent", m.phase(), IDLE);
        same("tap again", m.tap(false, false), QUERY);

        // No frame within the limit.
        same("no frame", m.timeout(), NO_FRAME);
        same("idle after no frame", m.phase(), IDLE);

        // Bit 0: the echo cannot confirm it, so the hold starts at once.
        m = new ManualSwitch();
        m.tap(false, false);
        m.frame(false);
        same("bit 0 holds at once", m.sent(0, 20_000), HOLD);
        same("phase after bit 0", m.phase(), HOLD);
        same("full hold", m.holdLeft(20_000), HOLD_MS);
        same("end of the hold after bit 0", m.timeout(), SENT);

        // No echo within the limit.
        m = new ManualSwitch();
        m.tap(false, false);
        m.frame(false);
        m.sent(1, 30_000);
        same("no echo", m.timeout(), NO_ANSWER);
        same("idle after no echo", m.phase(), IDLE);
        same("late echo", m.frame(true), NOTHING);

        // Leaving the screen ends any phase; coming back within the hold resumes it.
        m = new ManualSwitch();
        m.tap(false, false);
        m.pause();
        same("pause while waiting for a frame", m.phase(), IDLE);
        check("nothing sent yet: no hold", !m.resume(100));
        m.tap(false, false);
        m.frame(false);
        m.sent(1, 40_000);
        m.pause();
        same("pause while waiting for the echo", m.phase(), IDLE);
        check("back within the hold", m.resume(40_000 + HOLD_MS - 1));
        same("hold resumed", m.phase(), HOLD);
        same("rest of the hold", m.holdLeft(40_000 + HOLD_MS - 1), 1);
        same("resumed hold ends", m.timeout(), SENT);
        m.pause();
        check("back at the end of the hold", !m.resume(40_000 + HOLD_MS));
        same("no hold after its end", m.phase(), IDLE);
        check("back long after", !m.resume(90_000));

        System.out.println("ManualSwitchTest passed");
    }

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
    }

    private static void same(String name, long got, long expected) {
        if (got != expected) throw new AssertionError(name + ": expected " + expected + " but got " + got);
    }
}
