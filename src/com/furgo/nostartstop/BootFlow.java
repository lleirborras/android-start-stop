package com.furgo.nostartstop;

/**
 * The decisions of {@link BootService}: whether the one switch of an ignition cycle is sent, and when the service is
 * done. Everything it does goes through {@link Shell}, in the order written here. Main thread only.
 */
final class BootFlow {
    /** What the flow reads and does; {@link BootService} passes each call on unchanged. */
    interface Shell {
        /** elapsedRealtime milliseconds. */
        long now();
        String currentCycle();
        boolean bootSwitchDone(String cycle);
        long lastSwitchAt(String cycle);
        void markBootSwitch(String cycle);
        void setBootSwitchFailed(String cycle);
        void recordSwitch(String cycle);
        String deviceFacts();
        void onLog(String line);

        /** A new, closed link replaces the one before. */
        void newLink();
        boolean openLink();
        void query();
        /** False while the link is open but cannot send yet (the beta FYT link before it knows the car). */
        boolean linkReady();
        /** The bit sent, or below 0 when nothing was sent. */
        int toggle();
        void closeLink();

        /** The timer comes back through {@link BootFlow#onTimer}. */
        void post(int timer);
        void postDelayed(int timer, long ms);
        void cancel(int timer);
        void cancelAll();

        void running(boolean on);
        void endForeground();
        void endService();
    }

    static final int OPEN_TICK = 0, SEND_ONCE = 1, NO_ECHO = 2, STOP = 3;

    static final long OPEN_RETRY_MS = 2_000;
    static final long OPEN_TIMEOUT_MS = 30_000;
    static final long STATUS_WAIT_MS = 5_000;
    static final long ECHO_WAIT_MS = 3_000;
    // CanBox asks for frame 57 again 1.5 s and 5 s after a switch; keep the link open for the answers.
    static final long FUNCTION_WAIT_MS = 6_000;

    private final Shell shell;
    private boolean linked;
    private String cycle;
    private long startedAt;
    private boolean sent;
    private boolean done;
    private boolean waitLogged;
    private long sentAt;
    private int sentBit = -1;

    BootFlow(Shell shell) {
        this.shell = shell;
    }

    /** A start request for the service. */
    void start() {
        if (linked) {
            if (!done || cycle.equals(shell.currentCycle())) {
                shell.onLog("already running, start request dropped");
                return;
            }
            // finish() ran and only the wait for the last answers is left: a request for a newer cycle starts over.
            shell.cancel(STOP);
            shell.closeLink();
            sent = false;
            done = false;
            waitLogged = false;
            sentAt = 0;
            sentBit = -1;
        }
        // The cycle this switch belongs to, even if a new one starts while it is being sent.
        cycle = shell.currentCycle();
        shell.newLink();
        linked = true;
        shell.onLog("service started, " + shell.deviceFacts());
        if (alreadySwitched()) {
            finish();
            return;
        }
        shell.running(true);
        startedAt = shell.now();
        shell.post(OPEN_TICK);
    }

    void onTimer(int timer) {
        if (timer == OPEN_TICK) openTick();
        else if (timer == SEND_ONCE) sendOnce();
        else if (timer == NO_ECHO) noEcho();
        else if (timer == STOP) stop();
    }

    /** A settings status frame arrived with this echo bit. */
    void onEcho(boolean echo) {
        if (done) return;
        if (!sent) {
            shell.onLog("status received, echo bit " + (echo ? 1 : 0));
            sendOnce();
        } else if (sentBit == 1 && echo) {
            shell.onLog("echo bit changed to " + sentBit + ", done");
            finish();
        }
    }

    private void openTick() {
        // The MCU link may not be up yet this early in boot or right after a wake.
        if (shell.openLink()) {
            shell.query();
            shell.postDelayed(SEND_ONCE, STATUS_WAIT_MS);
            return;
        }
        if (shell.now() - startedAt >= OPEN_TIMEOUT_MS) {
            shell.onLog("no link to the car after " + OPEN_TIMEOUT_MS / 1000 + " s, nothing sent");
            shell.setBootSwitchFailed(cycle);
            finish();
            return;
        }
        shell.postDelayed(OPEN_TICK, OPEN_RETRY_MS);
    }

    private void sendOnce() {
        if (sent) return;
        sent = true;
        shell.cancel(SEND_ONCE);
        if (!cycle.equals(shell.currentCycle())) {
            shell.onLog("cycle changed, nothing sent");
            finish();
            return;
        }
        if (alreadySwitched()) {
            finish();
            return;
        }
        // Not marked yet: nothing has been tried, so the cycle is not used up.
        if (!shell.linkReady()) {
            if (shell.now() - startedAt >= OPEN_TIMEOUT_MS) {
                shell.onLog("link not ready after " + OPEN_TIMEOUT_MS / 1000 + " s, nothing sent");
                shell.setBootSwitchFailed(cycle);
                finish();
                return;
            }
            if (!waitLogged) shell.onLog("link not ready yet, waiting");
            waitLogged = true;
            sent = false;
            shell.postDelayed(SEND_ONCE, OPEN_RETRY_MS);
            return;
        }
        shell.markBootSwitch(cycle);
        sentBit = shell.toggle();
        if (sentBit >= 0) sentAt = shell.now();
        if (sentBit < 0) {
            shell.setBootSwitchFailed(cycle);
            shell.onLog("switch failed, nothing sent");
            finish();
            return;
        }
        shell.recordSwitch(cycle);
        if (sentBit == 0) {
            // A cleared echo looks the same as no answer, so it cannot confirm this command.
            shell.onLog("sent with bit 0, which the echo cannot confirm, done");
            finish();
            return;
        }
        shell.postDelayed(NO_ECHO, ECHO_WAIT_MS);
    }

    private void noEcho() {
        shell.onLog("echo bit did not change within " + ECHO_WAIT_MS / 1000 + " s, done");
        finish();
    }

    /** A second switch in the same ignition cycle would turn stop-start back on, whoever sent the first. */
    private boolean alreadySwitched() {
        if (shell.bootSwitchDone(cycle)) {
            shell.onLog("already switched during this ignition cycle, nothing sent");
            return true;
        }
        if (shell.lastSwitchAt(cycle) != 0) {
            shell.markBootSwitch(cycle);
            shell.onLog("already switched from the screen during this ignition cycle, nothing sent");
            return true;
        }
        return false;
    }

    private void finish() {
        done = true;
        shell.running(false);
        shell.endForeground();
        shell.cancelAll();
        long left = sentAt == 0 ? 0 : sentAt + FUNCTION_WAIT_MS - shell.now();
        if (left <= 0) {
            stop();
            return;
        }
        shell.postDelayed(STOP, left);
    }

    private void stop() {
        shell.closeLink();
        shell.endService();
    }
}
