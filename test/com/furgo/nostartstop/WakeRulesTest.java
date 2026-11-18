package com.furgo.nostartstop;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class WakeRulesTest {
    public static void main(String[] args) {
        check("first wake of a boot", WakeRules.isNewWake(false, 0, 5_000));
        check("other boot, even right after", WakeRules.isNewWake(false, 5_000, 5_001));
        check("same boot, 1 s later is the same wake", !WakeRules.isNewWake(true, 10_000, 11_000));
        check("same boot, 59.999 s later is the same wake", !WakeRules.isNewWake(true, 10_000, 69_999));
        check("same boot, 60 s later is a new wake", WakeRules.isNewWake(true, 10_000, 70_000));
        check("same boot, hours later", WakeRules.isNewWake(true, 10_000, 3_600_000));
        check("clock went back: a reboot with the same id", WakeRules.isNewWake(true, 70_000, 10_000));

        same("first wake number", WakeRules.nextWake(false, 7), 1);
        same("next wake number", WakeRules.nextWake(true, 7), 8);

        check("cycle differs per wake", !WakeRules.cycle("count 7", 1).equals(WakeRules.cycle("count 7", 2)));
        check("cycle differs per boot", !WakeRules.cycle("count 7", 1).equals(WakeRules.cycle("count 8", 1)));
        check("cycle never equals the bare boot id", !WakeRules.cycle("count 7", 0).equals("count 7"));
        check("cycle wake 1 vs boot count 71", !WakeRules.cycle("count 7", 1).equals(WakeRules.cycle("count 71", 0)));

        for (int s = -1; s <= 10; s++) {
            check("sleep state " + s, WakeRules.isSleepState(s) == (s == 1 || s == 5 || s == 9));
        }
        check("awake is not a sleep state", !WakeRules.isSleepState(WakeRules.POWER_AWAKE));

        System.out.println("WakeRulesTest passed");
    }

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
    }

    private static void same(String name, int got, int expected) {
        if (got != expected) throw new AssertionError(name + ": expected " + expected + " but got " + got);
    }
}
