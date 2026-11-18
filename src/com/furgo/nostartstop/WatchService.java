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
 * Only a boot or the MCU's own power state start a cycle; broadcasts any app could send are only logged.
 */
public class WatchService extends Service implements CanBox.Listener, CanBox.PowerListener {
    static final String EXTRA_BOOT_WAKE = "boot_wake";
    private static final int NOTIFICATION_ID = 3;
    private static final long[] OPEN_BACKOFF_MS = {1_000, 2_000, 4_000, 8_000, 15_000};
    private static final long SILENT_MS = 60_000;
    private static final long MAX_REOPEN_MS = 10 * 60_000;
    private static final LogBudget POWER_LINES = new LogBudget(60);
    private static final LogBudget AUTO_LINES = new LogBudget(50);
    private static final LogBudget WATCH_LINES = new LogBudget(50);
    private static final LogBudget BATTERY_LINES = new LogBudget(100);
    private static final LogBudget SILENT_LINES = new LogBudget(5);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AutoSwitch auto = new AutoSwitch();
    private CanBox canBox;
    private int openAttempt = -1;   // -1 while not trying
    private boolean linkOpen;
    private boolean powerRefused;   // the last open series failed
    private long lastMessageAt;
    private long lastReopenAt = -MAX_REOPEN_MS;
    private long reopenEvery = SILENT_MS;
    private boolean quietReopen;
    private int lastPowerState = -1;
    private boolean sawSleep;

    static void start(Context c, boolean bootWake) {
        if (!Prefs.applyAtBoot(c)) return;
        Intent i = new Intent(c, WatchService.class);
        if (bootWake) i.putExtra(EXTRA_BOOT_WAKE, true);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException e) {
            if (WATCH_LINES.take(SystemClock.elapsedRealtime())) LogFile.append(c, "watch service not started: " + e);
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
                quietReopen = false;
                lastMessageAt = SystemClock.elapsedRealtime();
                auto.linkOpened();
                return;
            }
            if (openAttempt >= OPEN_BACKOFF_MS.length) {
                openAttempt = -1;
                powerRefused = true;
                watchLog("no power messages from the head unit (" + canBox.openFailure() + "), waiting for the next boot");
                tick.run();
                return;
            }
            handler.postDelayed(this, OPEN_BACKOFF_MS[openAttempt++]);
        }
    };

    private final Runnable health = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            if (linkOpen && now - lastMessageAt >= SILENT_MS && now - lastReopenAt >= reopenEvery) {
                lastReopenAt = now;
                reopenEvery = Math.min(reopenEvery * 2, MAX_REOPEN_MS);
                canBox.close();
                linkOpen = false;
                quietReopen = !SILENT_LINES.take(now);
                if (!quietReopen) LogFile.append(WatchService.this, "watch: link silent, reopened");
                ensureOpen();
            }
            handler.postDelayed(this, SILENT_MS);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            act(auto.onTick(SystemClock.elapsedRealtime(), powerRefused), -1);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        if (!Prefs.applyAtBoot(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (canBox == null) {
            watchLog("service started, " + DeviceWatch.facts(this));
            canBox = new CanBox(this);
            canBox.watchPower(this);
            IntentFilter f = new IntentFilter();
            for (String a : WakeReceiver.RUNTIME_ACTIONS) f.addAction(a);
            registerReceiver(receiver, f);
            ensureOpen();
            handler.postDelayed(health, SILENT_MS);
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_BOOT_WAKE, false)) arm(true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (canBox != null) {
            unregisterReceiver(receiver);
            canBox.close();
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
        if (state != lastPowerState) {
            lastPowerState = state;
            if (POWER_LINES.take(lastMessageAt)) LogFile.append(this, "power state " + state);
        }
        if (WakeRules.isSleepState(state)) {
            sawSleep = true;
            handler.removeCallbacks(tick);
            if (auto.disarm()) autoLog("head unit going to sleep, nothing sent");
        } else if (state == WakeRules.POWER_AWAKE && sawSleep) {
            sawSleep = false;
            if (Prefs.newWake(this, "power state " + state)) arm(false);
        }
    }

    @Override
    public void onBattery(int centivolts) {
        heard();
        act(auto.onBattery(centivolts, lastMessageAt), centivolts);
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
        powerRefused = false;
        handler.post(openTick);
    }

    private void arm(boolean afterBoot) {
        auto.arm(SystemClock.elapsedRealtime(), afterBoot);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, AutoSwitch.FALLBACK_MS);
        handler.postDelayed(tick, AutoSwitch.GIVE_UP_MS);
        if (linkOpen) auto.linkOpened();
        autoLog(afterBoot ? "boot, waiting for the engine to run" : "wake, waiting for the engine to start");
        if (afterBoot) ensureOpen();
    }

    private void act(int what, int centivolts) {
        if (what == AutoSwitch.WAIT) return;
        handler.removeCallbacks(tick);
        if (what == AutoSwitch.GIVE_UP) {
            autoLog("engine not seen running within " + AutoSwitch.GIVE_UP_MS / 60_000 + " min, nothing sent");
            return;
        }
        if (what == AutoSwitch.ALREADY_RUNNING) {
            autoLog("engine already running at wake, nothing sent");
            return;
        }
        if (BootService.running) {
            autoLog("a switch is already being sent, nothing more sent");
            return;
        }
        autoLog(what == AutoSwitch.FIRE ? "engine running (" + Protocol.volts(centivolts) + " V), switching"
                : "no battery data from the head unit, switching " + AutoSwitch.FALLBACK_MS / 1000 + " s after boot");
        Intent i = new Intent(this, BootService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (RuntimeException e) {
            autoLog("switch service not started: " + e);
        }
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
