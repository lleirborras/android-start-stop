package com.furgo.nostartstop;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class AutoSwitchTest {
    private static final int W = AutoSwitch.WAIT, F = AutoSwitch.FIRE, FB = AutoSwitch.FIRE_FALLBACK,
            G = AutoSwitch.GIVE_UP;

    public static void main(String[] args) {
        AutoSwitch a = new AutoSwitch();
        same("not armed: battery", a.onBattery(1400, 1_000), W);
        same("not armed: tick", a.onTick(100_000), W);
        check("not armed: disarm reports nothing pending", !a.disarm());

        // Engine running: three readings in a row at or above 13.2 V.
        a.arm(0);
        same("1st high", a.onBattery(1390, 1_000), W);
        same("2nd high", a.onBattery(1320, 2_000), W);
        same("3rd high fires", a.onBattery(1400, 3_000), F);
        check("disarmed after firing", !a.armed());
        same("no second fire", a.onBattery(1400, 4_000), W);
        same("no fallback after firing", a.onTick(20_000), W);

        // A low reading restarts the count; 13.19 V is not running.
        a.arm(0);
        same("high", a.onBattery(1400, 1_000), W);
        same("high", a.onBattery(1400, 2_000), W);
        same("low resets", a.onBattery(1319, 3_000), W);
        same("high", a.onBattery(1400, 4_000), W);
        same("high", a.onBattery(1400, 5_000), W);
        same("3rd in a row fires", a.onBattery(1400, 6_000), F);

        // Invalid readings neither count nor reset, and do not count as battery data.
        a.arm(0);
        same("invalid", a.onBattery(-1, 1_000), W);
        same("fallback still applies", a.onTick(AutoSwitch.FALLBACK_MS), FB);

        // Engine off: battery data but never high, give up after 10 minutes.
        a.arm(0);
        same("12.0 V", a.onBattery(1200, 1_000), W);
        same("no fallback with battery data", a.onTick(AutoSwitch.FALLBACK_MS), W);
        same("still waiting at 9:59", a.onTick(AutoSwitch.GIVE_UP_MS - 1), W);
        same("give up at 10 min", a.onTick(AutoSwitch.GIVE_UP_MS), G);
        same("nothing after giving up", a.onBattery(1400, AutoSwitch.GIVE_UP_MS + 1), W);

        // High readings that come too late do not fire.
        a.arm(0);
        same("late high", a.onBattery(1400, AutoSwitch.GIVE_UP_MS - 2), W);
        same("late high", a.onBattery(1400, AutoSwitch.GIVE_UP_MS - 1), W);
        same("late high past the limit", a.onBattery(1400, AutoSwitch.GIVE_UP_MS), G);

        // No battery data: fall back at 15 s, not before.
        a.arm(1_000);
        same("before 15 s", a.onTick(1_000 + AutoSwitch.FALLBACK_MS - 1), W);
        same("at 15 s", a.onTick(1_000 + AutoSwitch.FALLBACK_MS), FB);
        same("fallback fires once", a.onTick(1_000 + AutoSwitch.FALLBACK_MS + 1), W);

        // A tick that comes very late (the head unit slept) gives up instead of falling back.
        a.arm(0);
        same("late tick", a.onTick(AutoSwitch.GIVE_UP_MS + 5_000), G);

        // Sleep disarms.
        a.arm(0);
        a.onBattery(1400, 1_000);
        check("disarm reports pending", a.disarm());
        same("disarmed: battery", a.onBattery(1400, 2_000), W);
        same("disarmed: tick", a.onTick(AutoSwitch.FALLBACK_MS), W);

        // Re-arming starts fresh.
        a.arm(0);
        a.onBattery(1400, 1_000);
        a.onBattery(1400, 2_000);
        a.arm(100_000);
        same("count restarts after re-arm", a.onBattery(1400, 101_000), W);

        System.out.println("AutoSwitchTest passed");
    }

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
    }

    private static void same(String name, int got, int expected) {
        if (got != expected) throw new AssertionError(name + ": expected " + expected + " but got " + got);
    }
}
