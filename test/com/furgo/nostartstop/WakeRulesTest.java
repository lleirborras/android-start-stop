package com.furgo.nostartstop;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class WakeRulesTest {
    public static void main(String[] args) {
        check("ignition off sets the flag", WakeRules.ignitionOffAfter(false, true, false));
        check("ignition off keeps it set", WakeRules.ignitionOffAfter(true, true, false));
        check("a new cycle clears it", !WakeRules.ignitionOffAfter(true, false, true));
        check("a new cycle without the flag leaves it clear", !WakeRules.ignitionOffAfter(false, false, true));
        check("nothing else changes it: set", WakeRules.ignitionOffAfter(true, false, false));
        check("nothing else changes it: clear", !WakeRules.ignitionOffAfter(false, false, false));

        same("first wake number", WakeRules.nextWake(false, 7), 1);
        same("next wake number", WakeRules.nextWake(true, 7), 8);

        check("cycle differs per wake", !WakeRules.cycle("count 7", 1).equals(WakeRules.cycle("count 7", 2)));
        check("cycle differs per boot", !WakeRules.cycle("count 7", 1).equals(WakeRules.cycle("count 8", 1)));
        check("cycle never equals the bare boot id", !WakeRules.cycle("count 7", 0).equals("count 7"));
        check("cycle wake 1 vs boot count 71", !WakeRules.cycle("count 7", 1).equals(WakeRules.cycle("count 71", 0)));

        System.out.println("WakeRulesTest passed");
    }

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
    }

    private static void same(String name, int got, int expected) {
        if (got != expected) throw new AssertionError(name + ": expected " + expected + " but got " + got);
    }
}
