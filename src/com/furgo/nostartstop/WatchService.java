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
 * A cycle starts at ignition on (the first start after the unit reported ignition off, a boot included) and at each
 * engine start; broadcasts any app could send are only logged.
 */
public class WatchService extends Service implements CanBox.Listener, CanBox.PowerListener {
    static final String BY_APP = "by the app", AT_BOOT = "at boot", AFTER_UPDATE = "after update",
            BY_BROADCAST = "by a broadcast";
    private static final String EXTRA_HOW = "how";
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
    private final AutoSwitch auto = new AutoSwitch();
    private final PowerWatch power = new PowerWatch();
    private CanBox canBox;
    private int openAttempt = -1;   // -1 while not trying
    private boolean linkOpen;
    private long lastMessageAt;
    private long lastReopenAt = -MAX_REOPEN_MS;
    private long reopenEvery = SILENT_MS;
    private boolean quietReopen;
    private int lastPowerState = -1;
    private long armedAt;

    static void start(Context c, String how) {
        if (!Prefs.applyAtBoot(c)) return;
        Intent i = new Intent(c, WatchService.class).putExtra(EXTRA_HOW, how);
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
                return;
            }
            if (openAttempt >= OPEN_BACKOFF_MS.length) {
                openAttempt = -1;
                watchLog("no power messages from the head unit (" + canBox.openFailure() + "), nothing will be sent");
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
                power.linkClosed();
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
            act(auto.onTick(SystemClock.elapsedRealtime()));
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        if (!Prefs.applyAtBoot(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String how = intent == null ? "restarted by the system" : intent.getStringExtra(EXTRA_HOW);
        if (canBox == null) {
            watchLog("service started (" + (how == null ? "unknown" : how) + "), " + DeviceWatch.facts(this));
            canBox = new CanBox(this);
            canBox.watchPower(this);
            IntentFilter f = new IntentFilter();
            for (String a : WakeReceiver.RUNTIME_ACTIONS) f.addAction(a);
            registerReceiver(receiver, f);
            handler.postDelayed(health, SILENT_MS);
        }
        if (PowerWatch.startIsIgnitionOn(Prefs.ignitionOff(this), BY_BROADCAST.equals(how))) {
            Prefs.newWake(this, "ignition on");
            auto.arm(SystemClock.elapsedRealtime());
            armed("ignition on");
        }
        ensureOpen();
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
        int event = power.onState(state, lastMessageAt);
        if (event == PowerWatch.IGNITION_OFF) {
            Prefs.setIgnitionOff(this);
            handler.removeCallbacks(tick);
            if (auto.disarm()) autoLog("ignition off, nothing sent");
        } else if (event == PowerWatch.CRANK_END) {
            autoLog("crank seen");
            boolean fresh = auto.onCrank(lastMessageAt);
            if (fresh) Prefs.newWake(this, "engine start");
            armed(fresh ? "engine start" : "engine start, same cycle");
        }
    }

    @Override
    public void onBattery(int centivolts) {
        heard();
        if (auto.armed() && ARMED_BATTERY_LINES.take(lastMessageAt)) {
            long ms = lastMessageAt - armedAt;
            LogFile.append(this, "auto: battery " + Protocol.volts(centivolts) + " V, " + ms / 1000 + "." + ms % 1000 / 100
                    + " s after arming");
        }
        act(auto.onBattery(centivolts, lastMessageAt));
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
        handler.post(openTick);
    }

    private void armed(String why) {
        armedAt = SystemClock.elapsedRealtime();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, AutoSwitch.SETTLE_MS);
        handler.postDelayed(tick, AutoSwitch.GIVE_UP_MS);
        autoLog(why + ", waiting for the engine to run");
    }

    private void act(int what) {
        if (what == AutoSwitch.WAIT) return;
        handler.removeCallbacks(tick);
        if (what == AutoSwitch.GIVE_UP) {
            autoLog("engine not seen running within " + AutoSwitch.GIVE_UP_MS / 60_000 + " min, nothing sent");
            return;
        }
        if (BootService.running) {
            autoLog("a switch is already being sent, nothing more sent");
            return;
        }
        autoLog("engine running (" + Protocol.volts(auto.newest()) + " V), switching");
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
