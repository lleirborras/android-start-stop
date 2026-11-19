package com.furgo.nostartstop;

import java.util.Locale;
import java.util.TimeZone;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class ShareStatusTest {
    private static final int RING = ShareStatus.RING, OK = ShareStatus.OK, WAITING = ShareStatus.WAITING,
            FAILED = ShareStatus.FAILED;
    private static final long SENT_MS = ShareStatus.SENT_MS;
    /** 14:32 UTC on 1970-01-01. */
    private static final long AT_1432 = (14 * 60 + 32) * 60_000L;
    private static final long NONE = -1;

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Locale.setDefault(Locale.US);
        same("sent shows for", SENT_MS, 8000);

        // Off comes before everything else.
        is("off", of(false, false, 0, 0, false, -1, 0, 0, 0, AT_1432), "Diagnostics off", false, RING, false, 0, NONE);
        is("off over all", of(false, true, 500, 1240, true, 740, 3, 500, AT_1432, AT_1432 + 1),
                "Diagnostics off", false, RING, false, 0, NONE);

        // Sending.
        is("sending, nothing counted", of(true, true, 0, 0, false, -1, 0, 0, 0, AT_1432),
                "Sending…", true, OK, true, 0, NONE);
        is("sending, none waiting", of(true, true, 0, 0, false, 0, 0, 0, 0, AT_1432),
                "Sending…", true, OK, true, 0, NONE);
        is("sending one line", of(true, true, 0, 1, false, 1, 1, 0, 0, AT_1432),
                "Sending 1 line…", true, OK, true, 0, NONE);
        is("sending three lines", of(true, true, 0, 3, false, 3, 1, 0, 0, AT_1432),
                "Sending 3 lines…", true, OK, true, 0, NONE);
        is("sending a backlog", of(true, true, 500, 1240, false, 740, 3, 0, 0, AT_1432),
                "Sending: 500 of 1240 lines", true, OK, true, 500f / 1240, NONE);
        is("last request done", of(true, true, 3, 3, false, 0, 0, 0, 0, AT_1432),
                "Sending…", true, OK, true, 1, NONE);
        is("last request done, lines came in meanwhile", of(true, true, 3, 3, false, 2, 1, 0, 0, AT_1432),
                "Sending 2 lines…", true, OK, true, 1, NONE);
        is("more done than the total fills the rule only once", of(true, true, 5, 3, false, 0, 0, 0, 0, AT_1432),
                "Sending…", true, OK, true, 1, NONE);
        is("sending over failed and sent", of(true, true, 0, 3, true, 3, 2, 7, AT_1432, AT_1432 + 1),
                "Sending 3 lines…", true, OK, true, 0, NONE);

        // Failed, with lines waiting.
        is("failed", of(true, false, 0, 0, true, 1240, 3, 0, 0, AT_1432),
                "Send failed, 1240 lines from 3 days waiting", false, FAILED, false, 0, NONE);
        is("failed, one day", of(true, false, 0, 0, true, 2, 1, 0, 0, AT_1432),
                "Send failed, 2 lines waiting", false, FAILED, false, 0, NONE);
        is("failed, one line", of(true, false, 0, 0, true, 1, 1, 0, 0, AT_1432),
                "Send failed, 1 line waiting", false, FAILED, false, 0, NONE);
        is("failed over sent", of(true, false, 0, 0, true, 5, 1, 7, AT_1432, AT_1432 + 1),
                "Send failed, 5 lines waiting", false, FAILED, false, 0, NONE);
        is("totals of a finished send are ignored", of(true, false, 500, 1240, true, 740, 2, 0, 0, AT_1432),
                "Send failed, 740 lines from 2 days waiting", false, FAILED, false, 0, NONE);

        // Waiting.
        is("waiting", of(true, false, 0, 0, false, 3, 1, 0, 0, AT_1432),
                "3 lines waiting to send", false, WAITING, false, 0, NONE);
        is("one waiting", of(true, false, 0, 0, false, 1, 1, 0, 0, AT_1432),
                "1 line waiting to send", false, WAITING, false, 0, NONE);
        is("waiting from two days", of(true, false, 0, 0, false, 40, 2, 0, 0, AT_1432),
                "40 lines from 2 days waiting to send", false, WAITING, false, 0, NONE);
        is("waiting over sent", of(true, false, 0, 0, false, 3, 1, 7, AT_1432, AT_1432 + 1),
                "3 lines waiting to send", false, WAITING, false, 0, NONE);

        // Sent: named for 8 seconds, then "All sent".
        is("just sent", of(true, false, 0, 0, false, 0, 0, 3, AT_1432, AT_1432),
                "Sent 3 lines at 14:32", true, OK, false, 0, SENT_MS);
        is("sent one line a second ago", of(true, false, 0, 0, false, 0, 0, 1, AT_1432, AT_1432 + 1_000),
                "Sent 1 line at 14:32", true, OK, false, 0, SENT_MS - 1_000);
        is("sent, at the last millisecond", of(true, false, 0, 0, false, 0, 0, 3, AT_1432, AT_1432 + SENT_MS - 1),
                "Sent 3 lines at 14:32", true, OK, false, 0, 1);
        is("all sent", of(true, false, 0, 0, false, 0, 0, 3, AT_1432, AT_1432 + SENT_MS),
                "All sent at 14:32", false, OK, false, 0, NONE);
        is("all sent, clock set back", of(true, false, 0, 0, false, 0, 0, 3, AT_1432, AT_1432 - 1),
                "All sent at 14:32", false, OK, false, 0, NONE);
        is("failed with nothing waiting", of(true, false, 0, 0, true, 0, 0, 3, AT_1432, AT_1432 + SENT_MS),
                "All sent at 14:32", false, OK, false, 0, NONE);
        is("sent, lines not counted", of(true, false, 0, 0, false, -1, 0, 3, AT_1432, AT_1432 + SENT_MS),
                "All sent at 14:32", false, OK, false, 0, NONE);

        // On, nothing sent yet.
        is("on, not counted", of(true, false, 0, 0, false, -1, 0, 0, 0, AT_1432),
                "Diagnostics on", false, OK, false, 0, NONE);
        is("on, nothing waiting", of(true, false, 0, 0, false, 0, 0, 0, 0, AT_1432),
                "Diagnostics on, nothing waiting", false, OK, false, 0, NONE);
        is("failed, not counted", of(true, false, 0, 0, true, -1, 0, 0, 0, AT_1432),
                "Diagnostics on", false, OK, false, 0, NONE);

        System.out.println("ShareStatusTest passed");
    }

    private static ShareStatus of(boolean on, boolean sending, int done, int total, boolean failed, int waiting,
            int days, int lastSent, long lastSentAt, long now) {
        return ShareStatus.of(on, sending, done, total, failed, waiting, days, lastSent, lastSentAt, now);
    }

    private static void is(String name, ShareStatus got, String text, boolean bright, int dot, boolean pulse,
            float fill, long recheckMs) {
        String g = got.text + "|" + got.bright + "|" + got.dot + "|" + got.pulse + "|" + got.fill + "|" + got.recheckMs;
        String e = text + "|" + bright + "|" + dot + "|" + pulse + "|" + fill + "|" + recheckMs;
        if (!e.equals(g)) throw new AssertionError(name + ": expected '" + e + "' but got '" + g + "'");
    }

    private static void same(String name, long got, long expected) {
        if (got != expected) throw new AssertionError(name + ": expected " + expected + " but got " + got);
    }
}
