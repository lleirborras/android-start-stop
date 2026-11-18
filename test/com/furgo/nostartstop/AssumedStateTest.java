package com.furgo.nostartstop;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class AssumedStateTest {
    private static final int U = AssumedState.UNKNOWN, ON = AssumedState.ON, OFF = AssumedState.OFF;

    public static void main(String[] args) {
        same("no cycle seen", AssumedState.state(false, false, 0), U);
        same("no cycle seen, flips do not matter", AssumedState.state(false, false, 1), U);
        same("new cycle: on", AssumedState.state(true, false, 0), ON);
        same("one switch: off", AssumedState.state(true, false, 1), OFF);
        same("switch and a tap: on", AssumedState.state(true, false, 2), ON);
        same("switch, tap, report: off", AssumedState.state(true, false, 3), OFF);
        same("ignition off: unknown", AssumedState.state(true, true, 0), U);
        same("ignition off after a switch: unknown", AssumedState.state(true, true, 1), U);

        text("name on", AssumedState.name(ON), "on");
        text("name off", AssumedState.name(OFF), "off");
        text("name unknown", AssumedState.name(U), "unknown");
        text("change", AssumedState.change(OFF, "switch sent, automatic"), "assumed: off (switch sent, automatic)");
        text("change unknown", AssumedState.change(U, "ignition off"), "assumed: unknown (ignition off)");

        text("full report", AssumedState.report(OFF, 5, "ignition on", 1, 0, 312_400, 300_900, true, 1485, 6),
                "report: car shows ON, app assumed OFF, wake 5 (ignition on), 1 switch sent, 0 reports, "
                        + "cycle 312 s old, last switch 300 s ago (automatic), battery 14.85 V, power state 6");
        text("report with unknown fields left out", AssumedState.report(ON, 2, "engine start", 2, 1, 40_000, -1,
                false, -1, -1),
                "report: car shows OFF, app assumed ON, wake 2 (engine start), 2 switches sent, 1 report, "
                        + "cycle 40 s old");
        text("report after a tap", AssumedState.report(ON, 3, null, 2, 3, -1, 5_000, false, 1200, 9),
                "report: car shows OFF, app assumed ON, wake 3, 2 switches sent, 3 reports, "
                        + "last switch 5 s ago (tap), battery 12.00 V, power state 9");

        System.out.println("AssumedStateTest passed");
    }

    private static void same(String name, int got, int expected) {
        if (got != expected) throw new AssertionError(name + ": expected " + expected + " but got " + got);
    }

    private static void text(String name, String got, String expected) {
        if (!got.equals(expected)) throw new AssertionError(name + ": expected\n" + expected + "\nbut got\n" + got);
    }
}
