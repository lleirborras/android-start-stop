package com.furgo.nostartstop;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class LogBudgetTest {
    public static void main(String[] args) {
        LogBudget b = new LogBudget(2);
        long t = 5_000;
        check("1st", b.take(t));
        check("2nd", b.take(t + 1));
        check("3rd is over the cap", !b.take(t + 2));
        check("still capped just before 24 h", !b.take(t + LogBudget.WINDOW_MS - 1));
        check("new window at 24 h", b.take(t + LogBudget.WINDOW_MS));
        check("2nd in the new window", b.take(t + LogBudget.WINDOW_MS + 1));
        check("capped again", !b.take(t + LogBudget.WINDOW_MS + 2));
        check("clock went back: new window", b.take(1_000));

        LogBudget zero = new LogBudget(0);
        check("zero cap never logs", !zero.take(0));

        System.out.println("LogBudgetTest passed");
    }

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
    }
}
