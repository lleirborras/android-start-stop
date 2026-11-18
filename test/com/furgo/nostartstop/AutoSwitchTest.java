package com.furgo.nostartstop;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class AutoSwitchTest {
    private static final int W = AutoSwitch.WAIT, F = AutoSwitch.FIRE, G = AutoSwitch.GIVE_UP;
    private static final long SETTLE = AutoSwitch.SETTLE_MS, GIVE_UP = AutoSwitch.GIVE_UP_MS;

    public static void main(String[] args) {
        AutoSwitch a = new AutoSwitch();
        same("not armed: battery", a.onBattery(1400, 1_000), W);
        same("not armed: tick", a.onTick(100_000), W);
        check("not armed: disarm reports nothing pending", !a.disarm());

        // One reading of 13.0 V or more after arming fires once 5 s have passed.
        a.arm(0, true);
        same("13.38 V before 5 s waits", a.onBattery(1338, 1_000), W);
        same("tick before 5 s", a.onTick(SETTLE - 1), W);
        same("tick at 5 s fires", a.onTick(SETTLE), F);
        same("no second fire", a.onBattery(1400, SETTLE + 1_000), W);
        same("no tick after firing", a.onTick(GIVE_UP), W);

        a.arm(0, true);
        same("reading after 5 s fires at once", a.onBattery(1300, SETTLE + 1), F);

        // A reading from before arming does not count: nothing until a new one arrives.
        a.arm(0, true);
        a.onBattery(1400, 1_000);
        a.arm(10_000, true);
        same("reading before the re-arm does not count", a.onTick(10_000 + SETTLE), W);
        same("new reading counts", a.onBattery(1400, 10_000 + SETTLE + 1), F);

        // The newest reading decides: a drop below 13.0 V before the 5 s mark does not fire.
        a.arm(0, true);
        same("high", a.onBattery(1338, 1_000), W);
        same("12.99 V", a.onBattery(1299, 3_000), W);
        same("tick at 5 s with the newest reading low", a.onTick(SETTLE), W);
        same("high again fires", a.onBattery(1340, 20_000), F);

        // Engine off: no reading at 13.0 V, give up 3 minutes after arming.
        a.arm(0, true);
        same("12.0 V", a.onBattery(1200, 1_000), W);
        same("just before 3 min", a.onTick(GIVE_UP - 1), W);
        same("give up at 3 min", a.onTick(GIVE_UP), G);
        same("nothing after giving up", a.onBattery(1400, GIVE_UP + 1), W);
        a.arm(0, true);
        same("no readings at all: no fallback", a.onTick(SETTLE), W);
        same("no readings at all: give up", a.onTick(GIVE_UP), G);
        a.arm(0, true);
        same("high reading past 3 min gives up", a.onBattery(1400, GIVE_UP), G);

        // Ignition off disarms.
        a.arm(0, true);
        a.onBattery(1400, 1_000);
        check("disarm reports pending", a.disarm());
        same("disarmed: tick", a.onTick(SETTLE), W);
        same("disarmed: battery", a.onBattery(1400, SETTLE + 1), W);

        // A crank restarts a pending switch without a new cycle, and starts a new cycle once it fired or gave up.
        a.arm(0, true);
        a.onBattery(1400, 1_000);
        check("crank while pending, nothing sent: same cycle", !a.onCrank(3_000, false));
        same("re-armed: 5 s counts from the crank, old reading gone", a.onTick(3_000 + SETTLE), W);
        same("reading after the crank fires", a.onBattery(1338, 3_000 + SETTLE + 1), F);
        check("crank after a fire: new cycle", a.onCrank(30_000, true));
        same("new reading fires again", a.onBattery(1338, 30_000 + SETTLE), F);
        a.arm(0, true);
        a.onTick(GIVE_UP);
        check("crank after giving up: new cycle", a.onCrank(GIVE_UP + 1, false));
        check("crank on a fresh watch: new cycle", new AutoSwitch().onCrank(0, false));

        // A switch already sent in the cycle (a tap) was undone by the engine start: the crank starts a new cycle.
        a.arm(0, false);
        check("crank while pending after a tap: new cycle", a.onCrank(10_000, true));
        same("and it fires after the crank", a.onBattery(1340, 10_000 + SETTLE), F);

        // Armed by an ignition on: no 3-minute limit, the head unit may still be off for hours.
        a.arm(0, false);
        same("12.0 V", a.onBattery(1200, 1_000), W);
        same("no give-up at 3 min", a.onTick(GIVE_UP), W);
        same("no give-up hours later", a.onTick(5 * 3_600_000L), W);
        same("13.4 V hours later fires", a.onBattery(1340, 5 * 3_600_000L + 1), F);
        a.arm(0, false);
        check("ignition off still disarms it", a.disarm());
        a.arm(0, false);
        check("a crank while pending re-arms the same cycle", !a.onCrank(3_600_000L, false));
        same("from then on the 3-minute limit applies", a.onTick(3_600_000L + GIVE_UP), G);

        System.out.println("AutoSwitchTest passed");
    }

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
    }

    private static void same(String name, int got, int expected) {
        if (got != expected) throw new AssertionError(name + ": expected " + expected + " but got " + got);
    }
}
