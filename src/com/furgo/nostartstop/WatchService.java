package com.furgo.nostartstop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Runs while the automatic switch is on. Watches the head unit's power state (message 514) and the battery voltage,
 * and after each new ignition cycle starts {@link BootService} once the engine runs. It never sends anything itself.
 * A cycle starts at ignition on (the first start after the unit reported ignition off, a boot included, or the unit's
 * wake seen by a service that outlived its sleep) and at each engine start; broadcasts any app could send are only
 * logged.
 */
public class WatchService extends Service implements CanBox.Listener, CanBox.PowerListener {
    private static final String EXTRA_HOW = "how";
    /** The last power state this process saw was ignition off. Main thread only. */
    static boolean ignitionOffNow;
    /** This process has the power messages open, so an ignition off or engine start will be seen. Main thread only. */
    static boolean following;
    /** The last power state and battery reading this process saw, or -1, for the "Car shows the opposite" report. */
    static int seenPowerState = -1, seenCentivolts = -1;
    static long seenPowerStateAt, seenCentivoltsAt;
    private static final int NOTIFICATION_ID = 3;
    private static final long[] OPEN_BACKOFF_MS = {1_000, 2_000, 4_000, 8_000, 15_000};
    private static final long SILENT_MS = 60_000;
    private static final long MAX_REOPEN_MS = 10 * 60_000;
    private static final LogBudget POWER_LINES = new LogBudget(60);
    private static final LogBudget AUTO_LINES = new LogBudget(50);
    private static final LogBudget WATCH_LINES = new LogBudget(50);
    private static final LogBudget BATTERY_LINES = new LogBudget(100);
    private static final LogBudget ARMED_BATTERY_LINES = new LogBudget(60);
    private static final LogBudget SILENT_LINES = new LogBudget(5);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WatchDecisions decisions = new WatchDecisions(new WatchDecisions.Shell() {
        @Override
        public long now() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public boolean ignitionOff() {
            return Prefs.ignitionOff(WatchService.this);
        }

        @Override
        public long lastSwitchElapsed() {
            return Prefs.lastSwitchElapsed(WatchService.this);
        }

        @Override
        public boolean switchBusy() {
            return BootService.running;
        }

        @Override
        public void ignitionOffNow(boolean off) {
            ignitionOffNow = off;
        }

        @Override
        public void newWake(String source, int event) {
            Prefs.newWake(WatchService.this, source, event);
        }

        @Override
        public void ignitionEvent(int event) {
            Prefs.ignitionEvent(WatchService.this, event);
        }

        @Override
        public void assumedEnd(String why) {
            Prefs.assumedEnd(WatchService.this, why);
        }

        @Override
        public void assumedEngineStart(boolean switchedDuringCrank) {
            Prefs.assumedEngineStart(WatchService.this, switchedDuringCrank);
        }

        @Override
        public void autoLog(String line) {
            WatchService.this.autoLog(line);
        }

        @Override
        public void cancelTicks() {
            handler.removeCallbacks(tick);
        }

        @Override
        public void tickNow() {
            handler.post(tick);
        }

        @Override
        public void tickIn(long ms) {
            handler.postDelayed(tick, ms);
        }

        @Override
        public void sendSwitch() {
            Intent i = new Intent(WatchService.this, BootService.class);
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (RuntimeException e) {
                WatchService.this.autoLog("switch service not started: " + e);
            }
        }
    });
    private CanBox canBox;
    private int openAttempt = -1;   // -1 while not trying
    private boolean linkOpen;
    private long lastMessageAt;
    private long lastReopenAt = -MAX_REOPEN_MS;
    private long reopenEvery = SILENT_MS;
    private boolean quietReopen;
    private boolean gaveUp;
    private int lastPowerState = -1;

    static void start(Context c, String how) {
        if (!Prefs.applyAtBoot(c)) return;
        Intent i = new Intent(c, WatchService.class).putExtra(EXTRA_HOW, how);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException e) {
            if (WATCH_LINES.take(SystemClock.elapsedRealtime())) LogFile.append(c, "watch service not started: " + e);
            Prefs.assumedEnd(c, "watch service not started");
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (WakeReceiver.RUNTIME_ACTIONS.contains(action)) WakeReceiver.log(c, action, "runtime");
        }
    };

    private final Runnable openTick = new Runnable() {
        @Override
        public void run() {
            if (canBox.open()) {
                openAttempt = -1;
                linkOpen = true;
                following = true;
                quietReopen = false;
                gaveUp = false;
                lastMessageAt = SystemClock.elapsedRealtime();
                return;
            }
            // The head unit is set up for another car: retrying cannot help, and the reason is already logged.
            if (canBox.unsupported() != null) {
                openAttempt = -1;
                Prefs.assumedEnd(WatchService.this, "car not supported");
                return;
            }
            if (openAttempt >= OPEN_BACKOFF_MS.length) {
                openAttempt = -1;
                if (gaveUp) return;
                gaveUp = true;
                watchLog("no power messages from the head unit (" + canBox.openFailure() + "), nothing will be sent");
                Prefs.assumedEnd(WatchService.this, "no power messages");
                return;
            }
            handler.postDelayed(this, OPEN_BACKOFF_MS[openAttempt++]);
        }
    };

    private final Runnable health = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            // The beta link reports only changes, so its silence means nothing.
            boolean quietLink = canBox.usesOtherLink();
            if (linkOpen && !quietLink && now - lastMessageAt >= SILENT_MS && now - lastReopenAt >= reopenEvery) {
                lastReopenAt = now;
                reopenEvery = Math.min(reopenEvery * 2, MAX_REOPEN_MS);
                canBox.close();
                linkOpen = false;
                forgetSeen();
                decisions.linkClosed();
                quietReopen = !SILENT_LINES.take(now);
                if (!quietReopen) LogFile.append(WatchService.this, "watch: link silent, reopened");
                Prefs.assumedEnd(WatchService.this, "link silent");
                ensureOpen();
            } else if (!linkOpen && openAttempt < 0 && canBox.unsupported() == null) {
                // Past the backoff, so openTick tries once and does not reschedule itself.
                openAttempt = OPEN_BACKOFF_MS.length;
                handler.post(openTick);
            }
            handler.postDelayed(this, SILENT_MS);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            decisions.onTick(SystemClock.elapsedRealtime());
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        if (!Prefs.applyAtBoot(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String how = intent == null ? PowerWatch.RESTARTED : intent.getStringExtra(EXTRA_HOW);
        if (canBox == null) {
            watchLog("service started (" + (how == null ? "unknown" : how) + "), " + DeviceWatch.facts(this));
            canBox = new CanBox(this, this);
            canBox.watchPower(this);
            IntentFilter f = new IntentFilter();
            for (String a : WakeReceiver.RUNTIME_ACTIONS) f.addAction(a);
            registerReceiver(receiver, f);
            handler.postDelayed(health, SILENT_MS);
        }
        decisions.onStart(how);
        ensureOpen();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (canBox != null) {
            unregisterReceiver(receiver);
            canBox.close();
            forgetSeen();
            watchLog("service stopped");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onPowerState(int state) {
        heard();
        seenPowerState = state;
        seenPowerStateAt = lastMessageAt;
        if (state != lastPowerState) {
            lastPowerState = state;
            if (POWER_LINES.take(lastMessageAt)) LogFile.append(this, "power state " + state);
        }
        decisions.onPowerState(state, lastMessageAt);
    }

    @Override
    public void onBattery(int centivolts) {
        heard();
        seenCentivolts = centivolts;
        seenCentivoltsAt = lastMessageAt;
        if (decisions.armed() && ARMED_BATTERY_LINES.take(lastMessageAt)) {
            long ms = lastMessageAt - decisions.armedAt();
            LogFile.append(this, "auto: battery " + Protocol.volts(centivolts) + " V, " + ms / 1000 + "." + ms % 1000 / 100
                    + " s after arming");
        }
        decisions.onBattery(centivolts, lastMessageAt);
    }

    @Override
    public void onIgnition(boolean on) {
        heard();
        watchLog(on ? "ignition on (head unit)" : "ignition off (head unit)");
        if (on) decisions.onIgnitionOn("ignition on reported by the head unit", canBox.engineSignal());
        else decisions.onPowerState(PowerWatch.STATE_OFF, lastMessageAt);
    }

    @Override
    public void onEngineRunning(String why, boolean afterStop) {
        heard();
        if (afterStop) decisions.onEngineStart(why, lastMessageAt);
        else decisions.onRunning(why, lastMessageAt);
    }

    @Override
    public void onEcho(boolean echo) {
    }

    @Override
    public void onLog(String line) {
        if (quietReopen && line.startsWith("TWUtil open")) return;
        LogBudget budget = line.startsWith("battery ") ? BATTERY_LINES : WATCH_LINES;
        if (budget.take(SystemClock.elapsedRealtime())) LogFile.append(this, "watch: " + line);
    }

    private static void forgetSeen() {
        following = false;
        seenPowerState = -1;
        seenCentivolts = -1;
    }

    private void heard() {
        lastMessageAt = SystemClock.elapsedRealtime();
        reopenEvery = SILENT_MS;
    }

    private void watchLog(String line) {
        if (WATCH_LINES.take(SystemClock.elapsedRealtime())) LogFile.append(this, "watch: " + line);
    }

    private void autoLog(String line) {
        if (AUTO_LINES.take(SystemClock.elapsedRealtime())) LogFile.append(this, "auto: " + line);
    }

    private void ensureOpen() {
        if (openAttempt >= 0 || linkOpen) return;
        openAttempt = 0;
        handler.post(openTick);
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("watch", "Engine start watch", NotificationManager.IMPORTANCE_MIN);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
            b = new Notification.Builder(this, "watch");
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }
        return b.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("No Start-Stop")
                .setContentText("Watching for engine start")
                .build();
    }
}
