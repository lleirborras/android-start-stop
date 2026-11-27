package com.furgo.nostartstop;

import java.util.Locale;
import java.util.TimeZone;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class FeedbackTest {
    private static final int KEEP = Feedback.KEEP, MUTED = Feedback.MUTED, WARN = Feedback.WARN,
            RESULT = Feedback.RESULT;
    /** 08:41 UTC on 1970-01-01. */
    private static final long AT_0841 = (8 * 60 + 41) * 60_000L;
    private static final String NOTE = "Set by hand. Nothing sent to the car.", RESULT_TEXT = "No answer from the car.";

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Locale.setDefault(Locale.US);

        same("clock", Feedback.clock(AT_0841), "08:41");
        same("clock is 24 h", Feedback.clock(AT_0841 + 12 * 3_600_000L), "20:41");

        // The note comes before everything else.
        is("note over all", Feedback.of(NOTE, true, RESULT_TEXT, true, true, true, AT_0841, true), NOTE, WARN);
        is("note alone", Feedback.of(NOTE, false, "", false, false, false, 0, false), NOTE, WARN);

        // Then the automatic switch being sent.
        is("automatic switch over a result", Feedback.of(null, true, RESULT_TEXT, true, true, true, AT_0841, true),
                "Switching…", MUTED);
        is("automatic switch alone", Feedback.of(null, true, "", false, false, false, 0, false), "Switching…", MUTED);

        // A result hides everything below it.
        is("result over the rest", Feedback.of(null, false, RESULT_TEXT, true, true, true, AT_0841, true),
                RESULT_TEXT, RESULT);
        is("result alone", Feedback.of(null, false, RESULT_TEXT, false, false, false, 0, false), RESULT_TEXT, RESULT);

        // Without a result: a pending tap blanks the line and leaves its colour.
        is("pending", Feedback.of(null, false, "", true, false, false, 0, false), "", KEEP);
        is("pending over the rest", Feedback.of(null, false, "", true, true, true, AT_0841, true), "", KEEP);

        is("no link", Feedback.of(null, false, "", false, true, false, 0, false),
                "This head unit did not let the app reach the car.", MUTED);
        is("no link over the rest", Feedback.of(null, false, "", false, true, true, AT_0841, true),
                "This head unit did not let the app reach the car.", MUTED);

        is("car not supported", Feedback.of(null, false, "", false, true, true, true, AT_0841, true),
                "This car or CAN box is not supported. Nothing sent.", MUTED);
        is("result over car not supported", Feedback.of(null, false, RESULT_TEXT, false, true, true, false, 0, false),
                RESULT_TEXT, RESULT);

        is("car setup needed", Feedback.of(null, false, "", false, true, true, false, true, AT_0841, true),
                "Confirm your car before the app sends anything.", MUTED);
        is("note over car setup", Feedback.of(NOTE, false, "", false, true, true, false, false, 0, false), NOTE, WARN);
        is("result over car setup", Feedback.of(null, false, RESULT_TEXT, false, true, true, false, false, 0, false),
                RESULT_TEXT, RESULT);

        is("automatic switch failed", Feedback.of(null, false, "", false, false, true, 0, false),
                "Automatic switch failed. Tap to switch.", WARN);
        is("failure over the last switch", Feedback.of(null, false, "", false, false, true, AT_0841, true),
                "Automatic switch failed. Tap to switch.", WARN);

        is("last switch, by tap", Feedback.of(null, false, "", false, false, false, AT_0841, false),
                "Sent at 08:41", MUTED);
        is("last switch, automatic", Feedback.of(null, false, "", false, false, false, AT_0841, true),
                "Sent at 08:41 (automatic)", MUTED);

        // Nothing to say: the empty result. "automatic" alone is not a switch.
        is("nothing", Feedback.of(null, false, "", false, false, false, 0, false), "", RESULT);
        is("no switch in this cycle", Feedback.of(null, false, "", false, false, false, 0, true), "", RESULT);

        same("sent", Feedback.sent(AT_0841), "Sent at 08:41");

        final int ON = AssumedState.ON, OFF = AssumedState.OFF, UNKNOWN = AssumedState.UNKNOWN;
        // Read from the car comes first.
        same("from the car, on", Feedback.hint(ON, true, false, true, false, false),
                "Read from the car. Tap Switch to turn it off.");
        same("from the car, on, busy", Feedback.hint(ON, true, false, false, true, true), "Read from the car.");
        same("from the car, off", Feedback.hint(OFF, true, false, true, true, true), "Read from the car.");
        // Then a state implied by a switch sent while the model was unknown.
        same("sent, not followed", Feedback.hint(OFF, false, true, true, false, false),
                "Not followed while Switch off at engine start is off.");
        same("sent, followed", Feedback.hint(OFF, false, true, true, true, false),
                "From the switch sent. Check the cluster.");
        same("assumed on", Feedback.hint(ON, false, false, true, true, true), "Assumed. Tap Switch to turn it off.");
        same("assumed on, busy", Feedback.hint(ON, false, false, false, true, true),
                "Assumed. Check the instrument cluster.");
        same("assumed off", Feedback.hint(OFF, false, false, true, true, true),
                "Assumed. Check the instrument cluster.");
        same("unknown, not followed", Feedback.hint(UNKNOWN, false, false, true, false, false),
                "Not followed while Switch off at engine start is off.");
        same("unknown, cannot follow", Feedback.hint(UNKNOWN, false, false, true, true, false),
                "Cannot follow the ignition. Check the cluster.");
        same("unknown", Feedback.hint(UNKNOWN, false, false, true, true, true),
                "Unknown since ignition off. Check the cluster, then tap Started or Stopped.");

        System.out.println("FeedbackTest passed");
    }

    private static void is(String name, Feedback got, String text, int color) {
        same(name, got.text, text);
        if (got.color != color) throw new AssertionError(name + ": expected colour " + color + " but got " + got.color);
    }

    private static void same(String name, String got, String expected) {
        if (!expected.equals(got)) throw new AssertionError(name + ": expected '" + expected + "' but got '" + got + "'");
    }
}
