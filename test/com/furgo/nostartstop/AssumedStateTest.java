package com.furgo.nostartstop;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class AssumedStateTest {
    private static final int U = AssumedState.UNKNOWN, ON = AssumedState.ON, OFF = AssumedState.OFF;
    private static final String C1 = "count 7, wake 1", C2 = "count 7, wake 2";

    public static void main(String[] args) {
        AssumedState m = new AssumedState();
        same("nothing seen yet", m.state(C1, true), U);
        check("a switch while unknown is not counted", !m.switchSent(C1, C1, true));
        check("a report while unknown is refused", !m.report(C1, true));
        same("still unknown", m.state(C1, true), U);

        // Ignition on, automatic switch, tap, report.
        m.newCycle(C1, 1, "ignition on", false, 0);
        same("new cycle: on", m.state(C1, true), ON);
        check("switch counted", m.switchSent(C1, C1, true));
        same("one switch: off", m.state(C1, true), OFF);
        check("tap counted", m.switchSent(C1, C1, true));
        same("two switches: on", m.state(C1, true), ON);
        check("report counted", m.report(C1, true));
        same("report flips: off", m.state(C1, true), OFF);
        check("a switch for another cycle is not counted", !m.switchSent(C2, C1, true));
        same("unchanged", m.state(C1, true), OFF);

        // Kept across a reload of the stored fields.
        same("reloaded", reload(m).state(C1, true), OFF);

        // Ignition off: unknown until the next cycle, which starts on.
        check("ignition off ends a known state", m.end(C1, true));
        same("ignition off: unknown", m.state(C1, true), U);
        check("a second ignition off changes nothing", !m.end(C1, true));
        check("a switch after ignition off is not counted", !m.switchSent(C1, C1, true));
        check("a report after ignition off is refused", !m.report(C1, true));
        same("still unknown after reload", reload(m).state(C1, true), U);
        m.newCycle(C2, 2, "ignition on", false, 100_000);
        same("next cycle clears it: on", m.state(C2, true), ON);
        same("the old cycle is not the current one", m.state(C1, true), U);

        // A reboot: the current cycle is wake 0 of a new boot, which no cycle start stores.
        same("wake 0 after a reboot", m.state("count 8, wake 0", true), U);

        // The automatic switch off: unknown, and a cycle it ended stays ended when it is turned on again.
        same("setting off: unknown", m.state(C2, false), U);
        check("a tap with the setting off is not counted", !m.switchSent(C2, C2, false));
        check("turning it off ends a known state", m.end(C2, true));
        same("setting on again mid-cycle: still unknown", m.state(C2, true), U);
        check("ending it while unknown changes nothing", !m.end(C2, false));

        // Same-cycle engine start: on again; a tap during the crank survives, earlier switches and reports do not.
        m.newCycle(C1, 1, "ignition on", false, 0);
        m.switchSent(C1, C1, true);
        m.report(C1, true);
        same("before the crank: on (switch + report)", m.state(C1, true), ON);
        m.sameCycleEngineStart(C1, 1, false, 50_000);
        same("engine start, nothing during the crank: on", m.state(C1, true), ON);
        same("counts restart", m.switches + m.reports, 0);
        m.newCycle(C1, 1, "ignition on", false, 0);
        m.switchSent(C1, C1, true);
        m.sameCycleEngineStart(C1, 1, true, 50_000);
        same("tap during the crank: off", m.state(C1, true), OFF);
        same("that tap is the one switch", m.switches, 1);
        m.end(C1, true);
        m.sameCycleEngineStart(C1, 3, false, 60_000);
        same("an engine start after ignition off is known again", m.state(C1, true), ON);
        same("wake refreshed", m.wake, 3);

        text("name", AssumedState.name(OFF), "off");
        text("change", AssumedState.change(OFF, "switch sent, automatic"), "assumed: off (switch sent, automatic)");
        text("change unknown", AssumedState.change(U, "automatic switch off"),
                "assumed: unknown (automatic switch off)");

        // Report lines.
        AssumedState r = new AssumedState();
        r.newCycle(C1, 5, "ignition on", false, 0);
        r.switchSent(C1, C1, true);
        text("full report", r.reportLine(C1, true, 312_400, 11_500, true, 1485, 310_000, 6, 312_000),
                "report: car shows ON, app assumed OFF, wake 5 (ignition on), 1 switch sent, 0 reports, "
                        + "cycle 312 s old, last switch 300 s ago (automatic), battery 14.85 V, power state 6");
        text("old values get their age", r.reportLine(C1, true, 312_400, -1, false, 1485, 400, 6, 302_399),
                "report: car shows ON, app assumed OFF, wake 5 (ignition on), 1 switch sent, 0 reports, "
                        + "cycle 312 s old, battery 14.85 V 312 s ago, power state 6 10 s ago");
        text("10 s exactly is still fresh", r.reportLine(C1, true, 20_000, -1, false, 1200, 10_000, -1, -1),
                "report: car shows ON, app assumed OFF, wake 5 (ignition on), 1 switch sent, 0 reports, "
                        + "cycle 20 s old, battery 12.00 V");
        r.sameCycleEngineStart(C1, 5, true, 100_000);
        r.report(C1, true);
        text("after a same-cycle engine start", r.reportLine(C1, true, 130_000, 98_000, false, -1, -1, 9, 129_000),
                "report: car shows OFF, app assumed ON, wake 5 (engine start, same cycle), 1 switch sent, 1 report, "
                        + "engine start 30 s ago, last switch 32 s ago (tap), power state 9");

        System.out.println("AssumedStateTest passed");
    }

    /** What Prefs does: store the plain fields, read them back into a new instance. */
    private static AssumedState reload(AssumedState m) {
        AssumedState r = new AssumedState();
        r.cycle = m.cycle;
        r.ended = m.ended;
        r.wake = m.wake;
        r.source = m.source;
        r.startAt = m.startAt;
        r.engineStart = m.engineStart;
        r.switches = m.switches;
        r.reports = m.reports;
        return r;
    }

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
    }

    private static void same(String name, int got, int expected) {
        if (got != expected) throw new AssertionError(name + ": expected " + expected + " but got " + got);
    }

    private static void text(String name, String got, String expected) {
        if (!got.equals(expected)) throw new AssertionError(name + ": expected\n" + expected + "\nbut got\n" + got);
    }
}
