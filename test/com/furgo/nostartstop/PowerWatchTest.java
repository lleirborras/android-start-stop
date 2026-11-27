package com.furgo.nostartstop;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class PowerWatchTest {
    private static final int N = PowerWatch.NONE, OFF = PowerWatch.IGNITION_OFF, CRANK = PowerWatch.CRANK_END;

    public static void main(String[] args) {
        PowerWatch p = new PowerWatch();
        same("6 when the link opens means nothing", p.onState(6, 1_000), N);
        same("1 is ignition off", p.onState(1, 2_000), OFF);
        same("5 is the wake", p.onState(5, 3_000), PowerWatch.WAKING);
        check("the wake ends the hold after ignition off", !p.offSeenHere(3_000));
        same("8 means nothing", p.onState(8, 3_500), N);
        same("4, going to sleep, is ignition off", p.onState(4, 3_600), OFF);
        check("4 starts the hold", p.offSeenHere(3_600));
        same("5 after it is the wake", p.onState(5, 3_700), PowerWatch.WAKING);

        // A crank: 9, then 6 about 5 s later. 9 itself is not ignition off.
        same("9 is not ignition off", p.onState(9, 10_000), N);
        same("repeated 9", p.onState(9, 11_000), N);
        same("6 after 9 ends the crank", p.onState(6, 16_000), CRANK);
        same("another 6 is nothing", p.onState(6, 17_000), N);

        // A second crank end less than 5 s after the first one is ignored; at 5 s it counts.
        same("9", p.onState(9, 18_000), N);
        same("crank end 4.999 s after the last one", p.onState(6, 20_999), N);
        same("9", p.onState(9, 21_000), N);
        same("ignored crank does not move the dedupe window", p.onState(6, 21_500), CRANK);
        p = new PowerWatch();
        p.onState(9, 0);
        same("first crank", p.onState(6, 5_000), CRANK);
        p.onState(9, 7_000);
        same("second crank 5 s after the first end", p.onState(6, 10_000), CRANK);
        p.onState(9, 20_000);
        same("driver starts again within 30 s", p.onState(6, 25_000), CRANK);

        // A 6 long after the 9 is not the end of that crank.
        p = new PowerWatch();
        p.onState(9, 0);
        same("6 more than 30 s after 9", p.onState(6, PowerWatch.MAX_CRANK_MS + 1), N);

        // Ignition off or a reopened link forgets the crank.
        p = new PowerWatch();
        p.onState(9, 0);
        same("1 during the crank", p.onState(1, 1_000), OFF);
        same("6 after that", p.onState(6, 2_000), N);
        p.onState(9, 10_000);
        p.linkClosed();
        same("6 answering the reopen", p.onState(6, 11_000), N);

        // Service start: an ignition on only after ignition off was seen, for a known reason, while nothing is pending
        // and this service has not itself seen the ignition go off.
        for (String how : new String[]{PowerWatch.BY_APP, PowerWatch.AT_BOOT, PowerWatch.AFTER_UPDATE,
                PowerWatch.RESTARTED}) {
            check("start " + how + " after ignition off", PowerWatch.startIsIgnitionOn(true, how, false, false));
            check("start " + how + " without ignition off", !PowerWatch.startIsIgnitionOn(false, how, false, false));
            check("start " + how + " while pending", !PowerWatch.startIsIgnitionOn(true, how, true, false));
            check("start " + how + " after state 1 in this service",
                    !PowerWatch.startIsIgnitionOn(true, how, false, true));
        }
        check("broadcast start", !PowerWatch.startIsIgnitionOn(true, PowerWatch.BY_BROADCAST, false, false));
        check("listener start", !PowerWatch.startIsIgnitionOn(true, PowerWatch.BY_LISTENER, false, false));
        check("start without a reason", !PowerWatch.startIsIgnitionOn(true, null, false, false));
        check("start with an unknown reason", !PowerWatch.startIsIgnitionOn(true, "by someone", false, false));

        // The service's own ignition off blocks a start only until the app would have been stopped, or until a crank.
        p = new PowerWatch();
        check("no ignition off seen yet", !p.offSeenHere(0));
        p.onState(1, 100_000);
        check("start right after state 1", p.offSeenHere(100_000));
        check("start 3 s after state 1", p.offSeenHere(103_000));
        check("start just inside the hold", p.offSeenHere(100_000 + PowerWatch.OFF_HOLD_MS - 1));
        p.onState(6, 110_000);
        check("a 6 does not end the hold", p.offSeenHere(111_000));
        check("start after the head unit slept", !p.offSeenHere(100_000 + PowerWatch.OFF_HOLD_MS));
        check("process outlived the sleep: the start is an ignition on", PowerWatch.startIsIgnitionOn(true,
                PowerWatch.BY_APP, false, p.offSeenHere(100_000 + 8 * 3_600_000L)));
        p.onState(1, 200_000);
        check("a second ignition off holds again", p.offSeenHere(201_000));
        p.onState(9, 202_000);
        check("a crank ends the hold", !p.offSeenHere(203_000));

        System.out.println("PowerWatchTest passed");
    }

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
    }

    private static void same(String name, int got, int expected) {
        if (got != expected) throw new AssertionError(name + ": expected " + expected + " but got " + got);
    }
}
