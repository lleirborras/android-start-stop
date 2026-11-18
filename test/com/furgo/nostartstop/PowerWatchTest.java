package com.furgo.nostartstop;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class PowerWatchTest {
    private static final int N = PowerWatch.NONE, OFF = PowerWatch.IGNITION_OFF, CRANK = PowerWatch.CRANK_END;

    public static void main(String[] args) {
        PowerWatch p = new PowerWatch();
        same("6 when the link opens means nothing", p.onState(6, 1_000), N);
        same("1 is ignition off", p.onState(1, 2_000), OFF);
        same("5 is not ignition off", p.onState(5, 3_000), N);
        same("8 means nothing", p.onState(8, 3_500), N);

        // A crank: 9, then 6 about 5 s later. 9 itself is not ignition off.
        same("9 is not ignition off", p.onState(9, 10_000), N);
        same("repeated 9", p.onState(9, 11_000), N);
        same("6 after 9 ends the crank", p.onState(6, 16_000), CRANK);
        same("another 6 is nothing", p.onState(6, 17_000), N);

        // A second crank less than 10 s after the first crank end is ignored; at 10 s it counts.
        same("9", p.onState(9, 20_000), N);
        same("crank end 9 s after the last one", p.onState(6, 25_999), N);
        same("9", p.onState(9, 26_000), N);
        same("ignored crank does not move the dedupe window", p.onState(6, 36_000), CRANK);
        p = new PowerWatch();
        p.onState(9, 0);
        same("first crank", p.onState(6, 5_000), CRANK);
        p.onState(9, 10_000);
        same("second crank 10 s after the first end", p.onState(6, 15_000), CRANK);
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

        // Service start: an ignition on only after ignition off was seen, and not when a broadcast started it.
        check("start after ignition off", PowerWatch.startIsIgnitionOn(true, false));
        check("start without ignition off", !PowerWatch.startIsIgnitionOn(false, false));
        check("broadcast start after ignition off", !PowerWatch.startIsIgnitionOn(true, true));
        check("broadcast start without ignition off", !PowerWatch.startIsIgnitionOn(false, true));

        System.out.println("PowerWatchTest passed");
    }

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
    }

    private static void same(String name, int got, int expected) {
        if (got != expected) throw new AssertionError(name + ": expected " + expected + " but got " + got);
    }
}
