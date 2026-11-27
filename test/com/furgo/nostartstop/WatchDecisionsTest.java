package com.furgo.nostartstop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class WatchDecisionsTest {
    private static final long SETTLE = AutoSwitch.SETTLE_MS, GIVE_UP = AutoSwitch.GIVE_UP_MS, HOUR = 3_600_000L;
    private static final String[] EVENTS = {"OFF_SEEN", "IGNITION_ON_CYCLE", "CRANK_CYCLE", "FIRED"};
    private static final String[] NOTHING = {};
    private static final String[] FIRE = {"cancel", "log: engine running (13.40 V), switching", "event FIRED", "send"};

    /**
     * Records what the decisions do, in order. Stands in for Prefs too: the ignition off flag follows WakeRules and a
     * new cycle has no switch yet, as in Prefs.newWake and Prefs.ignitionEvent. It outlives a WatchDecisions, as the
     * preferences outlive the process.
     */
    private static final class Shell implements WatchDecisions.Shell {
        final List<String> did = new ArrayList<>();
        long now;
        boolean flag;
        boolean offNow;
        long lastSwitch = -1;
        boolean busy;

        @Override
        public long now() {
            return now;
        }

        @Override
        public boolean ignitionOff() {
            return flag;
        }

        @Override
        public long lastSwitchElapsed() {
            return lastSwitch;
        }

        @Override
        public boolean switchBusy() {
            return busy;
        }

        @Override
        public void ignitionOffNow(boolean off) {
            offNow = off;
            did.add("offNow " + off);
        }

        @Override
        public void newWake(String source, int event) {
            flag = WakeRules.ignitionOffAfter(flag, event);
            lastSwitch = -1;
            did.add("wake " + source + " " + EVENTS[event]);
        }

        @Override
        public void ignitionEvent(int event) {
            flag = WakeRules.ignitionOffAfter(flag, event);
            did.add("event " + EVENTS[event]);
        }

        @Override
        public void assumedEnd(String why) {
            did.add("assumed end " + why);
        }

        @Override
        public void assumedEngineStart(boolean switchedDuringCrank) {
            did.add("assumed engine start " + switchedDuringCrank);
        }

        @Override
        public void autoLog(String line) {
            did.add("log: " + line);
        }

        @Override
        public void cancelTicks() {
            did.add("cancel");
        }

        @Override
        public void tickNow() {
            did.add("tick");
        }

        @Override
        public void tickIn(long ms) {
            did.add("tick+" + ms);
        }

        @Override
        public void sendSwitch() {
            did.add("send");
        }

        /** A switch command went out, by the boot service or a tap: what Prefs.recordSwitch keeps of it. */
        void switchSent(long at) {
            lastSwitch = at;
            flag = WakeRules.ignitionOffAfter(flag, WakeRules.FIRED, offNow);
        }
    }

    private static Shell s;
    private static WatchDecisions d;
    /** How far the clock is past the time of a power state, a reading or a tick when it is handled. */
    private static long skew;

    public static void main(String[] args) {
        bootThenCrankThenFire();
        bootAfterIgnitionOff();
        ignitionOffThenOnByTheApp();
        startsThatAreNotAnIgnitionOn();
        startWithinTheOffHold();
        giveUp();
        secondCrankInOneCycle();
        engineStartAfterASwitch();
        anotherSwitchStillBeingSent();
        ignitionOffWhileWaitingForAnotherSwitch();
        crankWhileWaitingForAnotherSwitch();
        headUnitReportsIgnitionAndRpm();
        rpmAfterAGuess();
        stallAndRestart();
        rpmStartWithoutASwitch();
        System.out.println("WatchDecisionsTest passed");
    }

    private static void bootThenCrankThenFire() {
        fresh();
        start("boot without an ignition off before it", PowerWatch.AT_BOOT, 0, NOTHING);
        check("nothing pending", !d.armed());
        state("6 answering the open", 6, 1_000, "offNow false", "tick");
        tick("that tick", 1_000, NOTHING);
        battery("engine already running, nothing pending", 1340, 2_000, NOTHING);
        state("9", 9, 10_000, "offNow false");
        battery("reading right after the 9", 1340, 10_003, NOTHING);
        state("6 ends the crank: new cycle", 6, 15_000, armed("engine start", "offNow false", "log: crank seen",
                "wake engine start CRANK_CYCLE"));
        check("pending", d.armed());
        same("armed when the crank end was handled", d.armedAt(), 15_000 + skew);
        tick("before the settle delay", 15_000 + SETTLE - 1, NOTHING);
        tick("the reading from the crank fires", 15_000 + SETTLE, FIRE);
        check("no longer pending", !d.armed());
        s.switchSent(15_000 + SETTLE + 100);
        tick("the give-up tick", 15_000 + GIVE_UP, NOTHING);
        battery("more readings", 1420, 15_000 + GIVE_UP + 1, NOTHING);

        // The link was reopened during a crank: the 6 that answers is not its end.
        fresh();
        state("9", 9, 0, "offNow false");
        d.linkClosed();
        state("6 answering the reopen", 6, 1_000, "offNow false", "tick");
        check("not armed", !d.armed());
    }

    private static void bootAfterIgnitionOff() {
        fresh();
        s.flag = true;
        start("boot after an ignition off", PowerWatch.AT_BOOT, 500, armed("ignition on",
                "wake ignition on IGNITION_ON_CYCLE"));
        check("the flag stays until the switch is sent", s.flag);
        same("armed at the start", d.armedAt(), 500);
        battery("engine off", 1250, 1_000, NOTHING);
        tick("no give-up after an ignition on", 500 + GIVE_UP, NOTHING);
        battery("engine runs an hour later", 1340, HOUR, FIRE);
        check("firing clears the flag", !s.flag);

        // The settle delay counts from the clock at the start, against the time of the reading or tick.
        fresh();
        s.flag = true;
        start("boot after an ignition off", PowerWatch.AT_BOOT, 500, armed("ignition on",
                "wake ignition on IGNITION_ON_CYCLE"));
        battery("engine running, just before the settle delay", 1340, 500 + SETTLE - 1, NOTHING);
        tick("at the settle delay", 500 + SETTLE, FIRE);
    }

    private static void ignitionOffThenOnByTheApp() {
        fresh();
        start("first start", PowerWatch.BY_APP, 0, NOTHING);
        state("ignition off", 1, 50_000, "offNow true", "event OFF_SEEN", "assumed end ignition off", "cancel");
        check("flag set", s.flag);

        // The head unit stopped the app; at ignition on it opens it again.
        d = new WatchDecisions(s);
        start("the app starts the service", PowerWatch.BY_APP, 0, armed("ignition on",
                "wake ignition on IGNITION_ON_CYCLE"));
        start("a second start while pending", PowerWatch.BY_APP, 2_000, NOTHING);
        state("6 answering the open", 6, 2_500, "offNow false", "tick");
        battery("engine running", 1340, SETTLE, FIRE);
        s.switchSent(SETTLE + 100);
        start("a start later in the drive", PowerWatch.BY_APP, 60_000, NOTHING);
        start("a restart by the system later in the drive", PowerWatch.RESTARTED, 70_000, NOTHING);

        // Ignition off while the switch is pending drops it.
        fresh();
        s.flag = true;
        start("ignition on", PowerWatch.AFTER_UPDATE, 0, armed("ignition on", "wake ignition on IGNITION_ON_CYCLE"));
        state("ignition off before the engine ran", 1, 10_000, "offNow true", "event OFF_SEEN",
                "assumed end ignition off", "cancel", "log: ignition off, nothing sent");
        battery("a late reading", 1340, 11_000, NOTHING);
        tick("a late tick", 20_000, NOTHING);
    }

    private static void startsThatAreNotAnIgnitionOn() {
        fresh();
        s.flag = true;
        start("by a broadcast", PowerWatch.BY_BROADCAST, 0, NOTHING);
        start("by the listener", PowerWatch.BY_LISTENER, 1_000, NOTHING);
        start("without a reason", null, 2_000, NOTHING);
        check("nothing pending", !d.armed());
        check("flag untouched", s.flag);
        battery("engine running, nothing pending", 1340, 3_000, NOTHING);

        // Such a service arms on the next crank it sees.
        state("9", 9, 10_000, "offNow false");
        state("6", 6, 14_000, armed("engine start", "offNow false", "log: crank seen",
                "wake engine start CRANK_CYCLE"));
        check("the crank cycle clears the flag", !s.flag);
        start("a start by the app after that", PowerWatch.BY_APP, 15_000, NOTHING);
        battery("fires once", 1340, 14_000 + SETTLE, FIRE);
    }

    private static void startWithinTheOffHold() {
        fresh();
        state("ignition off", 1, 100_000, "offNow true", "event OFF_SEEN", "assumed end ignition off", "cancel");
        start("3 s after the ignition off seen here", PowerWatch.BY_APP, 103_000, NOTHING);
        start("restarted just inside the hold", PowerWatch.RESTARTED, 100_000 + PowerWatch.OFF_HOLD_MS - 1, NOTHING);
        state("6 answering an open", 6, 110_000, "offNow false", "tick");
        start("a 6 does not end the hold", PowerWatch.BY_APP, 111_000, NOTHING);
        check("nothing pending", !d.armed());
        start("the process outlived the sleep", PowerWatch.BY_APP, 100_000 + PowerWatch.OFF_HOLD_MS,
                armed("ignition on", "wake ignition on IGNITION_ON_CYCLE"));
    }

    private static void giveUp() {
        fresh();
        state("9", 9, 0, "offNow false");
        state("6", 6, 4_000, armed("engine start", "offNow false", "log: crank seen",
                "wake engine start CRANK_CYCLE"));
        battery("engine did not start", 1200, 5_000, NOTHING);
        tick("settle tick", 4_000 + SETTLE, NOTHING);
        tick("just before 3 min", 4_000 + GIVE_UP - 1, NOTHING);
        tick("3 min after the crank end", 4_000 + GIVE_UP, "cancel",
                "log: engine not seen running within 3 min, nothing sent");
        check("no longer pending", !d.armed());
        battery("engine running after that", 1400, 4_000 + GIVE_UP + 1, NOTHING);
    }

    private static void secondCrankInOneCycle() {
        // Ignition on, then the engine start: one cycle, one switch.
        fresh();
        s.flag = true;
        start("ignition on", PowerWatch.BY_APP, 0, armed("ignition on", "wake ignition on IGNITION_ON_CYCLE"));
        state("9", 9, 60_000, "offNow false");
        battery("reading during the crank holds", 1340, 61_000, NOTHING);
        tick("tick during the crank holds", 62_000, NOTHING);
        state("6: same cycle", 6, 64_000, armed("engine start, same cycle", "offNow false", "log: crank seen",
                "event CRANK_CYCLE", "assumed engine start false"));
        check("the crank clears the flag", !s.flag);
        tick("fires once", 64_000 + SETTLE, FIRE);
        s.switchSent(64_000 + SETTLE + 100);
        tick("give-up tick", 64_000 + GIVE_UP, NOTHING);
        battery("more readings", 1350, 64_000 + GIVE_UP + 1, NOTHING);
        state("another 6", 6, 300_000, "offNow false", "tick");
        tick("that tick", 300_000, NOTHING);

        // Two crank ends less than 5 s apart are one engine start.
        fresh();
        state("9", 9, 0, "offNow false");
        state("6", 6, 3_000, armed("engine start", "offNow false", "log: crank seen",
                "wake engine start CRANK_CYCLE"));
        state("9 again", 9, 4_000, "offNow false");
        battery("reading during the second crank holds", 1340, 5_000, NOTHING);
        state("6 again, 3 s after the first: only lifts the hold", 6, 6_000, "offNow false", "tick");
        tick("that tick, before the settle delay of the first", 6_000, NOTHING);
        tick("fires once", 3_000 + SETTLE, FIRE);
        tick("not twice", 3_000 + SETTLE + 1, NOTHING);

        // A tap during the crank already switched for this engine start.
        fresh();
        s.flag = true;
        start("ignition on", PowerWatch.BY_APP, 0, armed("ignition on", "wake ignition on IGNITION_ON_CYCLE"));
        state("9", 9, 10_000, "offNow false");
        s.switchSent(11_000);
        state("6", 6, 13_000, armed("engine start, same cycle", "offNow false", "log: crank seen",
                "event CRANK_CYCLE", "assumed engine start true"));
    }

    private static void engineStartAfterASwitch() {
        fresh();
        state("9", 9, 0, "offNow false");
        state("6", 6, 4_000, armed("engine start", "offNow false", "log: crank seen",
                "wake engine start CRANK_CYCLE"));
        battery("fires", 1340, 4_000 + SETTLE, FIRE);
        s.switchSent(4_000 + SETTLE + 100);
        state("the engine is started again", 9, 100_000, "offNow false");
        state("6: new cycle", 6, 103_000, armed("engine start", "offNow false", "log: crank seen",
                "wake engine start CRANK_CYCLE"));
        battery("fires again", 1340, 103_000 + SETTLE, FIRE);
    }

    private static void anotherSwitchStillBeingSent() {
        fresh();
        state("9", 9, 0, "offNow false");
        state("6", 6, 4_000, armed("engine start", "offNow false", "log: crank seen",
                "wake engine start CRANK_CYCLE"));
        s.busy = true;
        battery("due while a switch is being sent", 1340, 9_000, "cancel",
                "log: another switch is still being sent, waiting", "tick+" + WatchDecisions.BUSY_RETRY_MS);
        check("still pending", d.armed());
        tick("still busy: no second log line", 11_000, "cancel", "tick+" + WatchDecisions.BUSY_RETRY_MS);
        s.busy = false;
        tick("free again", 13_000, FIRE);

        // The 30 s count on the clock, from the clock at the first busy fire, not on the times of the ticks.
        fresh();
        state("9", 9, 0, "offNow false");
        state("6", 6, 4_000, armed("engine start", "offNow false", "log: crank seen",
                "wake engine start CRANK_CYCLE"));
        s.busy = true;
        battery("due while a switch is being sent", 1340, 9_000, "cancel",
                "log: another switch is still being sent, waiting", "tick+" + WatchDecisions.BUSY_RETRY_MS);
        long since = 9_000 + skew;
        skew = 0;
        tick("clock just before 30 s", since + AutoSwitch.BUSY_MS - 1, "cancel",
                "tick+" + WatchDecisions.BUSY_RETRY_MS);
        skew = 50;
        tick("clock at 30 s, tick time before", since + AutoSwitch.BUSY_MS - 50, "cancel",
                "log: another switch was still being sent after 30 s, nothing sent");
        check("no longer pending", !d.armed());
        s.busy = false;
        tick("nothing after that", since + AutoSwitch.BUSY_MS, NOTHING);
    }

    private static void ignitionOffWhileWaitingForAnotherSwitch() {
        fresh();
        state("9", 9, 0, "offNow false");
        state("6", 6, 4_000, armed("engine start", "offNow false", "log: crank seen",
                "wake engine start CRANK_CYCLE"));
        s.busy = true;
        battery("due while a switch is being sent", 1340, 9_000, "cancel",
                "log: another switch is still being sent, waiting", "tick+" + WatchDecisions.BUSY_RETRY_MS);
        state("ignition off", 1, 10_000, "offNow true", "event OFF_SEEN", "assumed end ignition off", "cancel",
                "log: ignition off, nothing sent");
        tick("the retry tick", 11_000, NOTHING);

        // The next engine start waits its own 30 s and says so again.
        state("9", 9, 20_000, "offNow false");
        state("6", 6, 24_000, armed("engine start", "offNow false", "log: crank seen",
                "wake engine start CRANK_CYCLE"));
        battery("due, the other switch still being sent", 1340, 24_000 + SETTLE, "cancel",
                "log: another switch is still being sent, waiting", "tick+" + WatchDecisions.BUSY_RETRY_MS);
        tick("30 s after the first wait began", 9_000 + AutoSwitch.BUSY_MS + 100, "cancel",
                "tick+" + WatchDecisions.BUSY_RETRY_MS);
        tick("30 s after the second began", 24_000 + SETTLE + AutoSwitch.BUSY_MS, "cancel",
                "log: another switch was still being sent after 30 s, nothing sent");
    }

    private static void crankWhileWaitingForAnotherSwitch() {
        fresh();
        state("9", 9, 0, "offNow false");
        state("6", 6, 4_000, armed("engine start", "offNow false", "log: crank seen",
                "wake engine start CRANK_CYCLE"));
        s.busy = true;
        battery("due while a switch is being sent", 1340, 9_000, "cancel",
                "log: another switch is still being sent, waiting", "tick+" + WatchDecisions.BUSY_RETRY_MS);
        state("9", 9, 12_000, "offNow false");
        state("6: same cycle, armed again", 6, 15_000, armed("engine start, same cycle", "offNow false",
                "log: crank seen", "event CRANK_CYCLE", "assumed engine start false"));
        battery("due again: the wait starts afresh and is logged again", 1340, 15_000 + SETTLE, "cancel",
                "log: another switch is still being sent, waiting", "tick+" + WatchDecisions.BUSY_RETRY_MS);
        tick("30 s after the first wait began", 9_000 + AutoSwitch.BUSY_MS + 100, "cancel",
                "tick+" + WatchDecisions.BUSY_RETRY_MS);
        s.busy = false;
        tick("free again", 9_000 + AutoSwitch.BUSY_MS + 2_100, FIRE);
    }

    /** Beta FYT and Microntek units: ignition from the head unit, engine running from rpm or a guess. */
    private static void headUnitReportsIgnitionAndRpm() {
        fresh();
        s.now = 1_000;
        d.onIgnitionOn("ignition on reported by the head unit", true);
        did("ignition on without an off before it", "offNow false");
        check("nothing pending", !d.armed());
        state("ignition off", 1, 2_000, "offNow true", "event OFF_SEEN", "assumed end ignition off", "cancel");
        s.now = 3_000;
        d.onIgnitionOn("ignition on reported by the head unit", true);
        did("ignition on right after the off still counts", armed("ignition on reported by the head unit",
                "offNow false", "wake ignition on reported by the head unit IGNITION_ON_CYCLE"));
        s.now = 4_000;
        d.onIgnitionOn("ignition on reported by the head unit", true);
        did("a second ignition on while pending", "offNow false");
        s.now = 3_000 + SETTLE;
        d.onRunning("rpm 820", s.now);
        did("rpm fires", "cancel", "log: engine running (rpm 820), switching", "event FIRED", "send");

        // Microntek without the guess: a cycle starts, but nothing will say the engine runs.
        fresh();
        s.flag = true;
        s.now = 1_000;
        d.onIgnitionOn("ignition on reported by the head unit", false);
        did("no engine signal", "offNow false", "wake ignition on reported by the head unit IGNITION_ON_CYCLE",
                "log: no engine signal on this head unit, nothing sent; turn on Beta: guess the engine start to switch "
                        + "anyway");
        s.now = 2_000;
        d.onIgnitionOn("ignition on reported by the head unit", false);
        did("said once per cycle", "offNow false");
        tick("no tick fires it", 1_000 + SETTLE, NOTHING);
        state("ignition off", 1, 10_000, "offNow true", "event OFF_SEEN", "assumed end ignition off", "cancel",
                "log: ignition off, nothing sent");
        s.now = 20_000;
        d.onIgnitionOn("ignition on reported by the head unit", false);
        did("said again in the next cycle", "offNow false",
                "wake ignition on reported by the head unit IGNITION_ON_CYCLE",
                "log: no engine signal on this head unit, nothing sent; turn on Beta: guess the engine start to switch "
                        + "anyway");
    }

    /** A guess fired before the engine ran; the crank undid the switch, and the real rpm that follows re-arms. */
    private static void rpmAfterAGuess() {
        fresh();
        s.flag = true;
        s.now = 1_000;
        d.onIgnitionOn("ignition on reported by the head unit", true);
        did("ignition on", armed("ignition on reported by the head unit", "offNow false",
                "wake ignition on reported by the head unit IGNITION_ON_CYCLE"));
        running("the guess fires", "assumed, 15 s after ignition on", 16_000, fire("assumed, 15 s after ignition on"));
        s.switchSent(16_100);
        engineStart("rpm after the crank: new cycle", "rpm 800", 30_000, armed("engine start",
                "wake engine start CRANK_CYCLE"));
        check("pending again", d.armed());
        same("armed when the rpm was handled", d.armedAt(), 30_000 + skew);
        tick("before the settle delay", 30_000 + SETTLE - 1, NOTHING);
        tick("fires again on the rpm", 30_000 + SETTLE, fire("rpm 800"));
        s.switchSent(30_000 + SETTLE + 100);
        tick("the give-up tick", 30_000 + GIVE_UP, NOTHING);
    }

    /** The engine stalls and is restarted without an ignition off: the restart turned stop-start on again. */
    private static void stallAndRestart() {
        fresh();
        s.flag = true;
        s.now = 1_000;
        d.onIgnitionOn("ignition on reported by the head unit", true);
        s.did.clear();
        engineStart("first start: no switch yet, so the same cycle", "rpm 800", 20_000, fire("rpm 800"));
        s.switchSent(20_100);
        engineStart("restart after the stall: new cycle", "rpm 900", 60_000, armed("engine start",
                "wake engine start CRANK_CYCLE"));
        tick("fires on the restart", 60_000 + SETTLE, fire("rpm 900"));
        s.switchSent(60_000 + SETTLE + 100);
        running("rpm keeps coming", "rpm 1500", 70_000, NOTHING);
    }

    private static void rpmStartWithoutASwitch() {
        fresh();
        engineStart("not armed, no switch in the cycle", "rpm 800", 5_000, NOTHING);
        check("still nothing pending", !d.armed());

        // Pending, but nothing sent yet: the rpm only counts as the engine running.
        fresh();
        s.flag = true;
        s.now = 1_000;
        d.onIgnitionOn("ignition on reported by the head unit", true);
        s.did.clear();
        engineStart("before the settle delay", "rpm 800", 2_000, NOTHING);
        tick("at the settle delay", 1_000 + SETTLE, fire("rpm 800"));

        // A switch sent after the rpm was heard does not make that rpm a new start.
        fresh();
        s.switchSent(5_000 + skew);
        engineStart("switch at the time of the rpm", "rpm 800", 5_000 + skew, NOTHING);
    }

    private static String[] fire(String why) {
        return new String[]{"cancel", "log: engine running (" + why + "), switching", "event FIRED", "send"};
    }

    private static void running(String name, String why, long at, String... expected) {
        s.now = at + skew;
        d.onRunning(why, at);
        did(name, expected);
    }

    private static void engineStart(String name, String why, long at, String... expected) {
        s.now = at + skew;
        d.onEngineStart(why, at);
        did(name, expected);
    }

    private static void fresh() {
        s = new Shell();
        d = new WatchDecisions(s);
        skew = 7;
    }

    /** What arming does, after the given steps. */
    private static String[] armed(String why, String... before) {
        List<String> all = new ArrayList<>(Arrays.asList(before));
        all.addAll(Arrays.asList("cancel", "tick+" + SETTLE, "tick+" + GIVE_UP,
                "log: " + why + ", waiting for the engine to run"));
        return all.toArray(new String[0]);
    }

    /** A start has no time of its own: it reads the clock. */
    private static void start(String name, String how, long clock, String... expected) {
        s.now = clock;
        d.onStart(how);
        did(name, expected);
    }

    private static void state(String name, int state, long at, String... expected) {
        s.now = at + skew;
        d.onPowerState(state, at);
        did(name, expected);
    }

    private static void battery(String name, int centivolts, long at, String... expected) {
        s.now = at + skew;
        d.onBattery(centivolts, at);
        did(name, expected);
    }

    private static void tick(String name, long at, String... expected) {
        s.now = at + skew;
        d.onTick(at);
        did(name, expected);
    }

    private static void did(String name, String... expected) {
        List<String> want = Arrays.asList(expected);
        if (!s.did.equals(want)) throw new AssertionError(name + ": expected " + want + " but got " + s.did);
        s.did.clear();
    }

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
    }

    private static void same(String name, long got, long expected) {
        if (got != expected) throw new AssertionError(name + ": expected " + expected + " but got " + got);
    }
}
