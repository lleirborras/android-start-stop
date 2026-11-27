package com.furgo.nostartstop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class BootFlowTest {
    private static final String[] TIMERS = {"open", "send", "no-echo", "stop"};
    private static final String[] STARTED = {"new link", "log: service started, facts", "running true", "post open"};
    private static final String[] OPENED = {"open", "query", "post send 5000"};
    private static final String[] FINISHED = {"running false", "end foreground", "cancel all"};
    private static final String[] STOPPED = {"close", "end service"};

    /** Records what the flow does, and stands in for the handler, the clock, the stored state and the CAN box. */
    private static final class Fake implements BootFlow.Shell {
        final BootFlow flow = new BootFlow(this);
        final List<String> did = new ArrayList<String>();
        final List<long[]> queue = new ArrayList<long[]>();
        long now = 1_000;
        String cycle = "b#1";
        String mark;
        String switchCycle;
        boolean opens = true;
        boolean ready = true;
        int bit = 1;

        @Override
        public long now() {
            return now;
        }

        @Override
        public String currentCycle() {
            return cycle;
        }

        @Override
        public boolean bootSwitchDone(String cycle) {
            return cycle.equals(mark);
        }

        @Override
        public long lastSwitchAt(String cycle) {
            return cycle.equals(switchCycle) ? 1_700_000_000_000L : 0;
        }

        @Override
        public void markBootSwitch(String cycle) {
            mark = cycle;
            did.add("mark " + cycle);
        }

        @Override
        public void setBootSwitchFailed(String cycle) {
            did.add("failed " + cycle);
        }

        @Override
        public void recordSwitch(String cycle) {
            switchCycle = cycle;
            did.add("record " + cycle);
        }

        @Override
        public String deviceFacts() {
            return "facts";
        }

        @Override
        public void onLog(String line) {
            did.add("log: " + line);
        }

        @Override
        public void newLink() {
            did.add("new link");
        }

        @Override
        public boolean openLink() {
            did.add("open");
            return opens;
        }

        @Override
        public void query() {
            did.add("query");
        }

        @Override
        public boolean linkReady() {
            return ready;
        }

        @Override
        public int toggle() {
            did.add("toggle");
            return bit;
        }

        @Override
        public void closeLink() {
            did.add("close");
        }

        @Override
        public void post(int timer) {
            did.add("post " + TIMERS[timer]);
            queue.add(new long[]{timer, now});
        }

        @Override
        public void postDelayed(int timer, long ms) {
            did.add("post " + TIMERS[timer] + " " + ms);
            queue.add(new long[]{timer, now + ms});
        }

        @Override
        public void cancel(int timer) {
            did.add("cancel " + TIMERS[timer]);
            for (int i = queue.size() - 1; i >= 0; i--) if (queue.get(i)[0] == timer) queue.remove(i);
        }

        @Override
        public void cancelAll() {
            did.add("cancel all");
            queue.clear();
        }

        @Override
        public void running(boolean on) {
            did.add("running " + on);
        }

        @Override
        public void endForeground() {
            did.add("end foreground");
        }

        @Override
        public void endService() {
            did.add("end service");
        }

        /** Lets the time pass and runs the timers that come due, like the handler would. */
        void pass(long ms) {
            long until = now + ms;
            while (true) {
                int next = -1;
                for (int i = 0; i < queue.size(); i++) {
                    if (queue.get(i)[1] <= until && (next < 0 || queue.get(i)[1] < queue.get(next)[1])) next = i;
                }
                if (next < 0) break;
                long[] due = queue.remove(next);
                now = Math.max(now, due[1]);
                flow.onTimer((int) due[0]);
            }
            now = until;
        }

        /** Checks everything done since the last check, in order. */
        void did(String name, String[]... expected) {
            List<String> all = new ArrayList<String>();
            for (String[] part : expected) all.addAll(Arrays.asList(part));
            if (!did.equals(all)) throw new AssertionError(name + ": expected " + all + " but got " + did);
            did.clear();
        }

        int count(String what) {
            int n = 0;
            for (String s : did) if (s.equals(what)) n++;
            return n;
        }
    }

    private static String[] a(String... lines) {
        return lines;
    }

    private static Fake started() {
        Fake f = new Fake();
        f.flow.start();
        f.did("start", STARTED);
        f.pass(0);
        f.did("link opened", OPENED);
        return f;
    }

    /** Sent at 1 000, done at once with the stop due 6 s after the send, and 1 s passed since. */
    private static Fake sentAndDone() {
        Fake f = started();
        f.flow.onEcho(false);
        f.flow.onEcho(true);
        f.did("sent and done", a("log: status received, echo bit 0", "cancel send", "mark b#1", "toggle", "record b#1",
                "post no-echo 3000", "log: echo bit changed to 1, done"), FINISHED, a("post stop 6000"));
        f.pass(1_000);
        return f;
    }

    public static void main(String[] args) {
        // A normal send: status frame, mark, switch, echo, then the link stays open 6 s from the send.
        Fake f = started();
        f.pass(200);
        f.flow.onEcho(false);
        f.did("status frame: the mark is written before the send", a("log: status received, echo bit 0",
                "cancel send", "mark b#1", "toggle", "record b#1", "post no-echo 3000"));
        f.pass(500);
        f.flow.onEcho(false);
        f.did("echo still clear: keeps waiting");
        f.pass(600);
        f.flow.onEcho(true);
        f.did("echo set: done", a("log: echo bit changed to 1, done"), FINISHED, a("post stop 4900"));
        f.flow.onEcho(true);
        f.did("frames after done are ignored");
        f.pass(4_899);
        f.did("link still open");
        f.pass(1);
        f.did("stopped 6 s after the send", STOPPED);
        f.pass(60_000);
        f.did("no timer left, the status wait and the echo wait included");

        // No status frame: the switch is sent after 5 s anyway. No echo: done after 3 s more.
        f = started();
        f.pass(4_999);
        f.did("still waiting for the status frame");
        f.pass(1);
        f.did("sent without a status frame", a("cancel send", "mark b#1", "toggle", "record b#1", "post no-echo 3000"));
        f.flow.onEcho(false);
        f.did("a late status frame does not send again");
        f.pass(3_000);
        f.did("no echo", a("log: echo bit did not change within 3 s, done"), FINISHED, a("post stop 3000"));
        f.pass(3_000);
        f.did("stopped", STOPPED);

        // Bit 0 cannot be confirmed by the echo.
        f = started();
        f.bit = 0;
        f.flow.onEcho(true);
        f.did("bit 0", a("log: status received, echo bit 1", "cancel send", "mark b#1", "toggle", "record b#1",
                "log: sent with bit 0, which the echo cannot confirm, done"), FINISHED, a("post stop 6000"));
        f.pass(6_000);
        f.did("stopped", STOPPED);

        // The command could not be written: the mark stays, the failure is stored, the service stops at once.
        f = started();
        f.bit = -1;
        f.flow.onEcho(false);
        f.did("send failed", a("log: status received, echo bit 0", "cancel send", "mark b#1", "toggle", "failed b#1",
                "log: switch failed, nothing sent"), FINISHED, STOPPED);
        check("send failed: still marked", "b#1".equals(f.mark));

        // A request while a flow is active is dropped, whatever the cycle.
        f = started();
        f.flow.start();
        f.did("same cycle, waiting for the status frame", a("log: already running, start request dropped"));
        f.cycle = "b#2";
        f.flow.start();
        f.did("newer cycle, waiting for the status frame", a("log: already running, start request dropped"));
        f.cycle = "b#1";
        f.flow.onEcho(false);
        f.did("the first request goes on", a("log: status received, echo bit 0", "cancel send", "mark b#1", "toggle",
                "record b#1", "post no-echo 3000"));
        f.cycle = "b#2";
        f.flow.start();
        f.did("newer cycle, waiting for the echo", a("log: already running, start request dropped"));
        f.cycle = "b#1";
        f.flow.onEcho(true);
        f.did("done", a("log: echo bit changed to 1, done"), FINISHED, a("post stop 6000"));

        // After finish(), while the link is kept open: the same cycle is dropped, a newer one starts over.
        f.pass(1_000);
        f.flow.start();
        f.did("same cycle after finish", a("log: already running, start request dropped"));
        f.cycle = "b#2";
        f.flow.start();
        f.did("newer cycle after finish", a("cancel stop", "close"), STARTED);
        f.pass(0);
        f.did("link opened again", OPENED);
        f.pass(5_000);
        f.did("no stop left over from the first cycle; the second one sends",
                a("cancel send", "mark b#2", "toggle", "record b#2", "post no-echo 3000"));
        f.flow.onEcho(true);
        f.did("done again", a("log: echo bit changed to 1, done"), FINISHED, a("post stop 6000"));
        f.pass(6_000);
        f.did("stopped", STOPPED);

        // Already switched in this cycle: nothing is sent, the link is never opened.
        f = new Fake();
        f.mark = "b#1";
        f.flow.start();
        f.did("marked", a("new link", "log: service started, facts",
                "log: already switched during this ignition cycle, nothing sent"), FINISHED, STOPPED);
        f = new Fake();
        f.switchCycle = "b#1";
        f.flow.start();
        f.did("switched from the screen", a("new link", "log: service started, facts", "mark b#1",
                "log: already switched from the screen during this ignition cycle, nothing sent"), FINISHED, STOPPED);
        f = new Fake();
        f.mark = "b#0";
        f.switchCycle = "b#0";
        f.flow.start();
        f.did("a switch in an earlier cycle does not count", STARTED);

        // A tap on the screen while the service waits for the status frame.
        f = started();
        f.switchCycle = "b#1";
        f.flow.onEcho(false);
        f.did("tapped meanwhile", a("log: status received, echo bit 0", "cancel send", "mark b#1",
                "log: already switched from the screen during this ignition cycle, nothing sent"), FINISHED, STOPPED);

        // A new cycle started while the service waits: nothing is sent and nothing is marked.
        f = started();
        f.cycle = "b#2";
        f.pass(5_000);
        f.did("cycle changed", a("cancel send", "log: cycle changed, nothing sent"), FINISHED, STOPPED);
        check("cycle changed: not marked", f.mark == null);

        // The link does not open: retried every 2 s, given up after 30 s.
        f = new Fake();
        f.opens = false;
        f.flow.start();
        f.did("start", STARTED);
        f.pass(0);
        f.did("first try", a("open", "post open 2000"));
        f.pass(1_999);
        f.did("before the retry");
        f.pass(1);
        f.did("retry", a("open", "post open 2000"));
        f.pass(27_999);
        same("tries before the timeout", f.count("open"), 13);
        same("each one posts the next", f.count("post open 2000"), 13);
        f.did.clear();
        f.pass(1);
        f.did("timeout", a("open", "log: no link to the car after 30 s, nothing sent", "failed b#1"), FINISHED, STOPPED);
        check("timeout: not marked", f.mark == null);
        f.pass(60_000);
        f.did("no more tries");

        // The link opens on a retry.
        f = new Fake();
        f.opens = false;
        f.flow.start();
        f.pass(0);
        f.opens = true;
        f.did.clear();
        f.pass(2_000);
        f.did("opened on the second try", OPENED);

        // A newer cycle starts over within 6 s of the first send and sends nothing itself: it stops at once, the wait
        // after the first cycle's send is not carried over.
        f = sentAndDone();
        f.cycle = "b#2";
        f.mark = "b#2";
        f.flow.start();
        f.did("restart, already switched", a("cancel stop", "close", "new link", "log: service started, facts",
                "log: already switched during this ignition cycle, nothing sent"), FINISHED, STOPPED);
        f.pass(60_000);
        f.did("restart, already switched: nothing left");
        f = sentAndDone();
        f.cycle = "b#2";
        f.switchCycle = "b#2";
        f.flow.start();
        f.did("restart, switched from the screen", a("cancel stop", "close", "new link", "log: service started, facts",
                "mark b#2", "log: already switched from the screen during this ignition cycle, nothing sent"),
                FINISHED, STOPPED);
        f = sentAndDone();
        f.cycle = "b#2";
        f.bit = -1;
        f.flow.start();
        f.did("restart", a("cancel stop", "close"), STARTED);
        f.pass(0);
        f.did("restart: link opened", OPENED);
        f.flow.onEcho(false);
        f.did("restart, send failed", a("log: status received, echo bit 0", "cancel send", "mark b#2", "toggle",
                "failed b#2", "log: switch failed, nothing sent"), FINISHED, STOPPED);
        f.pass(60_000);
        f.did("restart, send failed: nothing left");

        // After the stop ran, until the service is destroyed: the same cycle is dropped, a newer one starts over.
        f = sentAndDone();
        f.pass(5_000);
        f.did("stopped", STOPPED);
        f.flow.start();
        f.did("same cycle after the stop", a("log: already running, start request dropped"));
        f.pass(60_000);
        f.did("same cycle after the stop: nothing left");
        f.cycle = "b#2";
        f.flow.start();
        f.did("newer cycle after the stop", a("cancel stop", "close"), STARTED);
        f.pass(0);
        f.did("newer cycle after the stop: link opened", OPENED);
        f.flow.onEcho(false);
        f.did("newer cycle after the stop: sent", a("log: status received, echo bit 0", "cancel send", "mark b#2",
                "toggle", "record b#2", "post no-echo 3000"));
        f.pass(3_000);
        f.did("newer cycle after the stop: no echo", a("log: echo bit did not change within 3 s, done"), FINISHED,
                a("post stop 3000"));
        f.pass(3_000);
        f.did("newer cycle after the stop: stopped", STOPPED);

        // The beta FYT link is open but does not know the car yet: the send waits, unmarked, until it is ready.
        f = started();
        f.ready = false;
        f.pass(5_000);
        f.did("not ready at the send", a("cancel send", "log: link not ready yet, waiting", "post send 2000"));
        check("not ready: not marked", f.mark == null);
        f.pass(2_000);
        f.did("still not ready: no second line", a("cancel send", "post send 2000"));
        f.flow.onEcho(false);
        f.did("a status frame meanwhile tries again", a("log: status received, echo bit 0", "cancel send",
                "post send 2000"));
        f.ready = true;
        f.pass(2_000);
        f.did("ready: sent", a("cancel send", "mark b#1", "toggle", "record b#1", "post no-echo 3000"));

        // Never ready: given up 30 s after the start, unmarked, like a link that does not open.
        f = started();
        f.ready = false;
        f.pass(5_000);
        f.did.clear();
        f.pass(23_999);
        same("retries before the timeout", f.count("post send 2000"), 11);
        f.did.clear();
        f.pass(1);
        f.did("29 s after the start: once more", a("cancel send", "post send 2000"));
        f.pass(2_000);
        f.did("not ready after 30 s", a("cancel send", "log: link not ready after 30 s, nothing sent", "failed b#1"),
                FINISHED, STOPPED);
        check("never ready: not marked", f.mark == null);
        f.pass(60_000);
        f.did("never ready: nothing left");

        // A tap from the screen while waiting for the link still counts.
        f = started();
        f.ready = false;
        f.pass(5_000);
        f.did.clear();
        f.switchCycle = "b#1";
        f.pass(2_000);
        f.did("tapped while not ready", a("cancel send", "mark b#1",
                "log: already switched from the screen during this ignition cycle, nothing sent"), FINISHED, STOPPED);

        // The same after a flow that sent nothing and stopped at once.
        f = started();
        f.bit = -1;
        f.flow.onEcho(false);
        f.did.clear();
        f.flow.start();
        f.did("same cycle after a failed send", a("log: already running, start request dropped"));
        f.cycle = "b#2";
        f.bit = 1;
        f.flow.start();
        f.did("newer cycle after a failed send", a("cancel stop", "close"), STARTED);

        System.out.println("BootFlowTest passed");
    }

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
    }

    private static void same(String name, int got, int expected) {
        if (got != expected) throw new AssertionError(name + ": expected " + expected + " but got " + got);
    }
}
