package com.furgo.nostartstop;

/**
 * What {@link WatchService} does with a service start, a power state, a battery reading and a tick: when a new
 * ignition cycle starts, when the pending switch is armed, dropped or sent. Plain Java; everything it reads or does
 * outside itself goes through {@link Shell}, in the order the calls are made.
 */
final class WatchDecisions {
    static final long BUSY_RETRY_MS = 2_000;

    /** The Android side. The reads are made at the moment they are needed, not ahead. */
    interface Shell {
        /** elapsedRealtime milliseconds. */
        long now();

        /** The persisted "ignition off seen" flag. */
        boolean ignitionOff();

        /** elapsedRealtime of the last switch sent in the current cycle, or -1. */
        long lastSwitchElapsed();

        /** Another switch is still being sent. */
        boolean switchBusy();

        void ignitionOffNow(boolean off);

        void newWake(String source, int event);

        void ignitionEvent(int event);

        void assumedEnd(String why);

        void assumedEngineStart(boolean switchedDuringCrank);

        void autoLog(String line);

        void cancelTicks();

        void tickNow();

        void tickIn(long ms);

        void sendSwitch();
    }

    private final Shell shell;
    private final AutoSwitch auto = new AutoSwitch();
    private final PowerWatch power = new PowerWatch();
    private long armedAt;

    WatchDecisions(Shell shell) {
        this.shell = shell;
    }

    /** The service was started; how is one of the {@link PowerWatch} reasons, or null. */
    void onStart(String how) {
        if (PowerWatch.startIsIgnitionOn(shell.ignitionOff(), how, auto.armed(), power.offSeenHere(shell.now()))) {
            shell.newWake("ignition on", WakeRules.IGNITION_ON_CYCLE);
            auto.arm(shell.now(), false);
            armed("ignition on");
        }
    }

    /**
     * Beta head units report the ignition themselves, so unlike {@link #onStart} no hold after ignition off applies.
     * Starts a cycle only after an ignition off, and not while a switch is pending. Without engineSignal nothing
     * will report the engine running, so nothing is sent.
     */
    void onIgnitionOn(String source, boolean engineSignal) {
        shell.ignitionOffNow(false);
        if (!shell.ignitionOff() || auto.armed()) return;
        shell.newWake(source, WakeRules.IGNITION_ON_CYCLE);
        auto.arm(shell.now(), false);
        if (engineSignal) {
            armed(source);
            return;
        }
        // Still armed, so a repeated ignition on does not start another cycle.
        shell.autoLog("no engine signal on this head unit, nothing sent; turn on Beta: guess the engine start to "
                + "switch anyway");
    }

    void onPowerState(int state, long at) {
        int event = power.onState(state, at);
        if (state == PowerWatch.STATE_AWAKE || state == PowerWatch.STATE_CRANK) shell.ignitionOffNow(false);
        if (event == PowerWatch.IGNITION_OFF) {
            shell.ignitionOffNow(true);
            shell.ignitionEvent(WakeRules.OFF_SEEN);
            shell.assumedEnd("ignition off");
            shell.cancelTicks();
            if (auto.disarm()) shell.autoLog("ignition off, nothing sent");
        } else if (state == PowerWatch.STATE_CRANK) {
            auto.onCrankStart(at);
        } else if (event == PowerWatch.CRANK_END) {
            shell.autoLog("crank seen");
            boolean fresh = auto.onCrank(at, shell.lastSwitchElapsed());
            if (fresh) shell.newWake("engine start", WakeRules.CRANK_CYCLE);
            else {
                shell.ignitionEvent(WakeRules.CRANK_CYCLE);
                shell.assumedEngineStart(auto.switchedDuringCrank());
            }
            armed(fresh ? "engine start" : "engine start, same cycle");
        } else if (state == PowerWatch.STATE_AWAKE) {
            auto.crankOver();
            shell.tickNow();
        }
    }

    void onBattery(int centivolts, long at) {
        act(auto.onBattery(centivolts, at));
    }

    /** The head unit reports the engine running without a battery reading; why is for the log. */
    void onRunning(String why, long at) {
        act(auto.onRunning(why, at));
    }

    /**
     * Beta head units: the engine runs again by its speed after it was seen stopped. After a switch in this cycle it
     * starts a new one, as a crank end does; otherwise it counts as {@link #onRunning}.
     */
    void onEngineStart(String why, long at) {
        // Beta head units have no crank state: an engine start seen in the engine speed counts as a crank end.
        boolean fresh = auto.onCrank(at, shell.lastSwitchElapsed());
        if (fresh) shell.newWake("engine start", WakeRules.CRANK_CYCLE);
        else {
            shell.ignitionEvent(WakeRules.CRANK_CYCLE);
            shell.assumedEngineStart(auto.switchedDuringCrank());
        }
        armed(fresh ? "engine start" : "engine start, same cycle");
        act(auto.onRunning(why, at));
    }

    void onTick(long at) {
        act(auto.onTick(at));
    }

    void linkClosed() {
        power.linkClosed();
    }

    boolean armed() {
        return auto.armed();
    }

    /** When the pending switch was last armed, as read from {@link Shell#now}. */
    long armedAt() {
        return armedAt;
    }

    private void armed(String why) {
        armedAt = shell.now();
        shell.cancelTicks();
        shell.tickIn(AutoSwitch.SETTLE_MS);
        shell.tickIn(AutoSwitch.GIVE_UP_MS);
        shell.autoLog(why + ", waiting for the engine to run");
    }

    private void act(int what) {
        if (what == AutoSwitch.WAIT) return;
        shell.cancelTicks();
        if (what == AutoSwitch.GIVE_UP) {
            shell.autoLog("engine not seen running within " + AutoSwitch.GIVE_UP_MS / 60_000 + " min, nothing sent");
            return;
        }
        if (shell.switchBusy()) {
            boolean first = !auto.deferring();
            if (auto.defer(shell.now())) {
                if (first) shell.autoLog("another switch is still being sent, waiting");
                shell.tickIn(BUSY_RETRY_MS);
            } else {
                shell.autoLog("another switch was still being sent after " + AutoSwitch.BUSY_MS / 1000
                        + " s, nothing sent");
            }
            return;
        }
        shell.autoLog("engine running (" + auto.runningWhy() + "), switching");
        shell.ignitionEvent(WakeRules.FIRED);
        shell.sendSwitch();
    }
}
