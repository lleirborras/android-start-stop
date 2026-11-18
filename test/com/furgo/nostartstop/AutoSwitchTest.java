package com.furgo.nostartstop;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class AutoSwitchTest {
    private static final int W = AutoSwitch.WAIT, F = AutoSwitch.FIRE, FB = AutoSwitch.FIRE_FALLBACK,
            G = AutoSwitch.GIVE_UP, R = AutoSwitch.ALREADY_RUNNING;
    private static final boolean BOOT = true, WAKE = false;

    public static void main(String[] args) {
        AutoSwitch a = new AutoSwitch();
        same("not armed: battery", a.onBattery(1400, 1_000), W);
        same("not armed: tick", a.onTick(100_000, true), W);
        check("not armed: disarm reports nothing pending", !a.disarm());

        // Boot: level trigger, three readings in a row at or above 13.2 V.
        a.arm(0, BOOT);
        same("boot 1st high", a.onBattery(1390, 1_000), W);
        same("boot 2nd high", a.onBattery(1320, 2_000), W);
        same("boot 3rd high fires", a.onBattery(1400, 3_000), F);
        same("no second fire", a.onBattery(1400, 4_000), W);
        same("no tick after firing", a.onTick(AutoSwitch.GIVE_UP_MS, true), W);

        // Boot: a reading below 13.2 V restarts the count.
        a.arm(0, BOOT);
        same("high", a.onBattery(1400, 1_000), W);
        same("high", a.onBattery(1400, 2_000), W);
        same("13.19 V resets", a.onBattery(1319, 3_000), W);
        same("high", a.onBattery(1400, 4_000), W);
        same("high", a.onBattery(1400, 5_000), W);
        same("3rd in a row fires", a.onBattery(1400, 6_000), F);

        // Wake: edge trigger, low first, then three high.
        a.arm(0, WAKE);
        same("wake low", a.onBattery(1220, 1_000), W);
        same("wake high", a.onBattery(1400, 2_000), W);
        same("wake high", a.onBattery(1400, 3_000), W);
        same("wake 3rd high fires", a.onBattery(1400, 4_000), F);

        // Wake: first reading already high means the engine ran before the wake.
        a.arm(0, WAKE);
        same("invalid reading is ignored", a.onBattery(-1, 500), W);
        same("first reading high", a.onBattery(1400, 1_000), R);
        same("nothing after that", a.onBattery(1200, 2_000), W);
        same("nothing after that, tick", a.onTick(AutoSwitch.GIVE_UP_MS, true), W);

        // Wake: readings between 13.0 and 13.2 V are neither low nor high; highs before a low do not count.
        a.arm(0, WAKE);
        same("13.10 V first", a.onBattery(1310, 1_000), W);
        same("high before low", a.onBattery(1400, 2_000), W);
        same("high before low", a.onBattery(1400, 3_000), W);
        same("high before low", a.onBattery(1400, 4_000), W);
        same("12.99 V is low", a.onBattery(1299, 5_000), W);
        same("high", a.onBattery(1400, 6_000), W);
        same("high", a.onBattery(1400, 7_000), W);
        same("3rd high after low fires", a.onBattery(1400, 8_000), F);

        // Wake: 13.00 V is not low.
        a.arm(0, WAKE);
        same("13.00 V", a.onBattery(1300, 1_000), W);
        same("high", a.onBattery(1400, 2_000), W);
        same("high", a.onBattery(1400, 3_000), W);
        same("high, still no low", a.onBattery(1400, 4_000), W);

        // Give up 3 minutes after arming, with readings or without.
        a.arm(0, BOOT);
        same("engine off", a.onBattery(1200, 1_000), W);
        same("still waiting just before", a.onTick(AutoSwitch.GIVE_UP_MS - 1, false), W);
        same("give up at 3 min", a.onTick(AutoSwitch.GIVE_UP_MS, false), G);
        same("nothing after giving up", a.onBattery(1400, AutoSwitch.GIVE_UP_MS + 1), W);
        a.arm(0, WAKE);
        same("late high", a.onBattery(1200, 1_000), W);
        same("late high", a.onBattery(1400, AutoSwitch.GIVE_UP_MS - 2), W);
        same("late high", a.onBattery(1400, AutoSwitch.GIVE_UP_MS - 1), W);
        same("late high past the limit gives up", a.onBattery(1400, AutoSwitch.GIVE_UP_MS), G);

        // Fallback: only after a boot, only when the power messages were refused.
        a.arm(1_000, BOOT);
        same("boot, refused, before 15 s", a.onTick(1_000 + AutoSwitch.FALLBACK_MS - 1, true), W);
        same("boot, refused, at 15 s", a.onTick(1_000 + AutoSwitch.FALLBACK_MS, true), FB);
        same("fallback fires once", a.onTick(1_000 + AutoSwitch.FALLBACK_MS + 1, true), W);
        a.arm(0, BOOT);
        same("boot, link open but no readings", a.onTick(AutoSwitch.FALLBACK_MS, false), W);
        same("refused later, past 15 s", a.onTick(40_000, true), FB);
        a.arm(0, WAKE);
        same("wake, refused: no fallback", a.onTick(AutoSwitch.FALLBACK_MS, true), W);
        same("wake, refused: gives up", a.onTick(AutoSwitch.GIVE_UP_MS, true), G);
        a.arm(0, BOOT);
        a.onBattery(1200, 1_000);
        same("boot with readings: no fallback", a.onTick(AutoSwitch.FALLBACK_MS, true), W);
        a.arm(0, BOOT);
        same("very late tick gives up instead of falling back", a.onTick(AutoSwitch.GIVE_UP_MS + 5_000, true), G);

        // Sleep disarms.
        a.arm(0, WAKE);
        a.onBattery(1200, 1_000);
        check("disarm reports pending", a.disarm());
        same("disarmed: battery", a.onBattery(1400, 2_000), W);
        same("disarmed: tick", a.onTick(AutoSwitch.FALLBACK_MS, true), W);

        // Re-arming starts fresh.
        a.arm(0, BOOT);
        a.onBattery(1400, 1_000);
        a.onBattery(1400, 2_000);
        a.arm(100_000, BOOT);
        same("count restarts after re-arm", a.onBattery(1400, 101_000), W);
        same("count restarts after re-arm", a.onBattery(1400, 102_000), W);
        same("count restarts after re-arm", a.onBattery(1400, 103_000), F);
        a.arm(0, WAKE);
        a.onBattery(1200, 1_000);
        a.arm(100_000, WAKE);
        same("low is forgotten after re-arm", a.onBattery(1400, 101_000), R);

        System.out.println("AutoSwitchTest passed");
    }

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
    }

    private static void same(String name, int got, int expected) {
        if (got != expected) throw new AssertionError(name + ": expected " + expected + " but got " + got);
    }
}
