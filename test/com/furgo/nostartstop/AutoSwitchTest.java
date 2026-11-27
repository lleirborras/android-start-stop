package com.furgo.nostartstop;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class AutoSwitchTest {
    private static final int W = AutoSwitch.WAIT, F = AutoSwitch.FIRE, G = AutoSwitch.GIVE_UP;
    private static final long SETTLE = AutoSwitch.SETTLE_MS, GIVE_UP = AutoSwitch.GIVE_UP_MS, HOUR = 3_600_000L,
            NONE = -1;

    public static void main(String[] args) {
        AutoSwitch a = new AutoSwitch();
        same("not armed: battery", a.onBattery(1400, 1_000), W);
        same("not armed: tick", a.onTick(100_000), W);
        check("not armed: disarm reports nothing pending", !a.disarm());

        // Engine running reported another way (rpm, or a guess): counts like a charging reading.
        AutoSwitch r = new AutoSwitch();
        same("running while not armed", r.onRunning("rpm 800", 1_000), W);
        r.arm(2_000, true);
        same("signal before arming does not count", r.onTick(2_000 + SETTLE), W);
        same("signal before 5 s waits", r.onRunning("rpm 800", 3_000), W);
        same("tick at 5 s fires on the signal", r.onTick(2_000 + SETTLE), F);
        check("why names the signal", "rpm 800".equals(r.runningWhy()));
        r.arm(10_000, true);
        same("signal after 5 s fires at once", r.onRunning("assumed", 10_000 + SETTLE), F);
        r.arm(20_000, true);
        same("battery fires", r.onBattery(1350, 20_000 + SETTLE), F);
        check("why names the battery after it", "13.50 V".equals(r.runningWhy()));
        r.arm(30_000, true);
        r.onCrankStart(30_000);
        same("signal during a crank waits", r.onRunning("rpm 900", 31_000), W);

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
        // Times restart at 0 in each case; a fresh instance keeps readings from earlier cases out.
        a = new AutoSwitch();
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
        check("crank while pending, nothing sent: same cycle", !a.onCrank(3_000, NONE));
        same("re-armed: 5 s counts from the crank, old reading gone", a.onTick(3_000 + SETTLE), W);
        same("reading after the crank fires", a.onBattery(1338, 3_000 + SETTLE + 1), F);
        long firedAt = 3_000 + SETTLE + 1;
        crank(a, 25_000);
        check("crank after a fire: new cycle", a.onCrank(30_000, firedAt));
        same("new reading fires again", a.onBattery(1338, 30_000 + SETTLE), F);
        a.arm(0, true);
        a.onTick(GIVE_UP);
        check("crank after giving up: new cycle", a.onCrank(GIVE_UP + 1, NONE));
        check("crank on a fresh watch: new cycle", new AutoSwitch().onCrank(0, NONE));

        // A tap before the engine start was undone by it: the crank starts a new cycle and fires.
        a.arm(0, false);
        crank(a, 8_000);
        check("crank while pending after a tap: new cycle", a.onCrank(10_000, 5_000));
        same("and it fires after the crank", a.onBattery(1340, 10_000 + SETTLE), F);
        // A tap during the crank already switched for this engine start: no new cycle.
        AutoSwitch b = new AutoSwitch();
        crank(b, 8_000);
        check("tap during the crank, nothing pending: same cycle", !b.onCrank(10_000, 9_000));
        b = new AutoSwitch();
        b.arm(0, false);
        crank(b, 8_000);
        check("tap during the crank, pending: same cycle", !b.onCrank(10_000, 9_000));
        check("tap during the crank is reported", b.switchedDuringCrank());
        b = new AutoSwitch();
        b.arm(0, false);
        crank(b, 8_000);
        b.onCrank(10_000, 5_000);
        check("tap before the crank is not during it", !b.switchedDuringCrank());
        b = new AutoSwitch();
        b.arm(0, false);
        crank(b, 8_000);
        b.onCrank(10_000, NONE);
        check("no tap", !b.switchedDuringCrank());

        // Armed by an ignition on: no 3-minute limit, the head unit may still be off for hours.
        a.arm(0, false);
        same("12.0 V", a.onBattery(1200, 1_000), W);
        same("no give-up at 3 min", a.onTick(GIVE_UP), W);
        same("no give-up hours later", a.onTick(5 * HOUR), W);
        same("13.4 V hours later fires", a.onBattery(1340, 5 * HOUR + 1), F);
        a.arm(0, false);
        check("ignition off still disarms it", a.disarm());
        a.arm(0, false);
        check("a crank while pending re-arms the same cycle", !a.onCrank(HOUR, NONE));
        same("from then on the 3-minute limit applies", a.onTick(HOUR + GIVE_UP), G);

        // Never fire while a crank is in progress.
        a.arm(0, false);
        a.onBattery(1400, 1_000);
        a.onCrankStart(10_000);
        same("during the crank: hold", a.onTick(11_000), W);
        same("reading during the crank: hold", a.onBattery(1400, 12_000), W);
        a.crankOver();
        same("crank over without an engine start: fires", a.onTick(13_000), F);
        a.arm(0, false);
        a.onCrankStart(10_000);
        a.disarm();
        a.arm(20_000, false);
        same("state 1 ends the hold", a.onBattery(1400, 20_000 + SETTLE), F);

        // Ignition on armed for a long time, 9, 13.4 V during the crank, 6, then the voltage stays constant:
        // exactly one fire, 5 s after the 6.
        a.arm(0, false);
        same("12.0 V", a.onBattery(1200, 1_000), W);
        a.onCrankStart(HOUR);
        same("13.4 V during the crank", a.onBattery(1340, HOUR + 2_000), W);
        same("still cranking", a.onTick(HOUR + 4_000), W);
        check("crank end: same cycle", !a.onCrank(HOUR + 5_000, NONE));
        same("before the settle delay", a.onTick(HOUR + 5_000 + SETTLE - 1), W);
        same("reading from the crank counts after the settle delay", a.onTick(HOUR + 5_000 + SETTLE), F);
        same("only once", a.onTick(HOUR + 5_000 + SETTLE + 1), W);
        same("only once, more readings", a.onBattery(1340, HOUR + 20_000), W);
        // The same, for a new crank cycle: the reading after the 9 counts.
        b = new AutoSwitch();
        b.onCrankStart(0);
        b.onBattery(1340, 2_000);
        check("fresh crank: new cycle", b.onCrank(5_000, NONE));
        same("reading from the crank counts", b.onTick(5_000 + SETTLE), F);
        // A reading from before the 9 does not.
        b = new AutoSwitch();
        b.onBattery(1340, 0);
        b.onCrankStart(1_000);
        b.onCrank(5_000, NONE);
        same("reading before the crank does not count", b.onTick(5_000 + SETTLE), W);

        // Another switch still being sent: wait, retry, give up after 30 s.
        a.arm(0, true);
        same("fires", a.onBattery(1340, SETTLE), F);
        check("busy: still pending", a.defer(SETTLE));
        check("pending again", a.armed());
        same("retry fires again", a.onTick(SETTLE + 2_000), F);
        check("busy 2 s later: still pending", a.defer(SETTLE + 2_000));
        same("retry", a.onTick(SETTLE + AutoSwitch.BUSY_MS), F);
        check("busy for 30 s: given up", !a.defer(SETTLE + AutoSwitch.BUSY_MS));
        check("no longer pending", !a.armed());
        a.arm(100_000, true);
        check("a new arm forgets the wait", !a.deferring());

        // A busy fire that is no longer due (the voltage dropped) gives a later busy fire its full 30 s.
        a = new AutoSwitch();
        a.arm(0, false);
        same("fires", a.onBattery(1340, SETTLE), F);
        check("busy", a.defer(SETTLE));
        same("voltage dropped: no longer due", a.onBattery(1200, SETTLE + 20_000), W);
        check("the wait is forgotten", !a.deferring());
        same("due again", a.onBattery(1340, SETTLE + 25_000), F);
        check("busy again", a.defer(SETTLE + 25_000));
        check("still pending 29 s into the new wait", a.defer(SETTLE + 25_000 + AutoSwitch.BUSY_MS - 1_000));

        // A 9 never followed by 6 or 1 holds the switch only for the crank time limit.
        long max = PowerWatch.MAX_CRANK_MS;
        a = new AutoSwitch();
        a.arm(0, false);
        a.onCrankStart(10_000);
        same("13.4 V during the crank: hold", a.onBattery(1340, 20_000), W);
        same("still within the limit", a.onTick(10_000 + max), W);
        same("past the limit the hold is dropped", a.onTick(10_000 + max + 1), F);
        // A 9 after the limit is a new crank start, and the crank end counts from it.
        a = new AutoSwitch();
        a.onCrankStart(0);
        a.onBattery(1340, 5_000);
        a.onCrankStart(max + 10_000);
        a.onCrank(max + 15_000, NONE);
        same("reading before the second 9 does not count", a.onTick(max + 15_000 + SETTLE), W);
        same("reading after the second 9 counts", a.onBattery(1340, max + 15_000 + SETTLE + 1), F);
        a = new AutoSwitch();
        a.onCrankStart(0);
        a.onCrankStart(max);
        a.onBattery(1340, 2_000);
        a.onCrank(max + 5_000, NONE);
        same("a repeated 9 within the limit keeps the first 9 as the start", a.onTick(max + 5_000 + SETTLE), F);

        System.out.println("AutoSwitchTest passed");
    }

    private static void crank(AutoSwitch a, long at) {
        a.onCrankStart(at);
    }

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
    }

    private static void same(String name, int got, int expected) {
        if (got != expected) throw new AssertionError(name + ": expected " + expected + " but got " + got);
    }
}
