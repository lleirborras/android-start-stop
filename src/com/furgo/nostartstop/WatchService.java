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
 * Runs while the automatic switch is on. Watches the head unit's power state and the battery voltage, and after each
 * new ignition cycle starts {@link BootService} once the engine runs. It never sends anything itself.
 */
public class WatchService extends Service implements CanBox.Listener, CanBox.PowerListener {
    static final String EXTRA_WAKE = "wake";
    static final String EXTRA_SLEEP = "sleep";
    private static final int NOTIFICATION_ID = 3;
    private static final long[] OPEN_BACKOFF_MS = {1_000, 2_000, 4_000, 8_000, 15_000};
    private static final int MAX_POWER_LINES = 60;
    private static int powerLines;   // per process

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AutoSwitch auto = new AutoSwitch();
    private CanBox canBox;
    private int openAttempt = -1;   // -1 while not trying
    private int lastPowerState = -1;
    private boolean sawSleep;

    static void start(Context c, String extra) {
        if (!Prefs.applyAtBoot(c)) return;
        Intent i = new Intent(c, WatchService.class);
        if (extra != null) i.putExtra(extra, true);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException e) {
            LogFile.append(c, "watch service not started: " + e);
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (!WakeReceiver.RUNTIME_ACTIONS.contains(action)) return;
            WakeReceiver.log(c, action, "runtime");
            if (WakeReceiver.ACC_ON.equals(action)) wake("ACC_ON");
            else if (WakeReceiver.ACC_OFF.equals(action) || WakeReceiver.DO_SLEEP.equals(action)) sleep();
        }
    };

    private final Runnable openTick = new Runnable() {
        @Override
        public void run() {
            if (canBox.open()) {
                openAttempt = -1;
                return;
            }
            if (openAttempt >= OPEN_BACKOFF_MS.length) {
                openAttempt = -1;
                log("watch: no link to the car after 30 s, waiting for the next wake");
                return;
            }
            handler.postDelayed(this, OPEN_BACKOFF_MS[openAttempt++]);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            act(auto.onTick(SystemClock.elapsedRealtime()), -1);
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
            log("watch: service started, " + DeviceWatch.facts(this));
            canBox = new CanBox(this);
            canBox.watchPower(this);
            IntentFilter f = new IntentFilter();
            for (String a : WakeReceiver.RUNTIME_ACTIONS) f.addAction(a);
            registerReceiver(receiver, f);
            ensureOpen();
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_SLEEP, false)) sleep();
        if (intent != null && intent.getBooleanExtra(EXTRA_WAKE, false)) arm();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (canBox != null) {
            unregisterReceiver(receiver);
            canBox.close();
            log("watch: service stopped");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onPowerState(int state) {
        if (state != lastPowerState) {
            lastPowerState = state;
            if (powerLines < MAX_POWER_LINES) {
                powerLines++;
                log("power state " + state);
            }
        }
        if (WakeRules.isSleepState(state)) {
            sawSleep = true;
            sleep();
        } else if (state == WakeRules.POWER_AWAKE && sawSleep) {
            sawSleep = false;
            wake("power state " + state);
        }
    }

    @Override
    public void onBattery(int centivolts) {
        act(auto.onBattery(centivolts, SystemClock.elapsedRealtime()), centivolts);
    }

    @Override
    public void onEcho(boolean echo) {
    }

    @Override
    public void onLog(String line) {
        log("watch: " + line);
    }

    private void log(String line) {
        LogFile.append(this, line);
    }

    private void wake(String source) {
        if (Prefs.newWake(this, source)) arm();
        ensureOpen();
    }

    private void ensureOpen() {
        if (openAttempt >= 0) return;
        openAttempt = 0;
        handler.post(openTick);
    }

    private void arm() {
        auto.arm(SystemClock.elapsedRealtime());
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, AutoSwitch.FALLBACK_MS);
        handler.postDelayed(tick, AutoSwitch.GIVE_UP_MS);
        log("auto: waiting for the engine to run");
        ensureOpen();
    }

    private void sleep() {
        handler.removeCallbacks(tick);
        if (auto.disarm()) log("auto: head unit going to sleep, nothing sent");
    }

    private void act(int what, int centivolts) {
        if (what == AutoSwitch.WAIT) return;
        handler.removeCallbacks(tick);
        if (what == AutoSwitch.GIVE_UP) {
            log("auto: engine not seen running within " + AutoSwitch.GIVE_UP_MS / 60_000 + " min, nothing sent");
            return;
        }
        log(what == AutoSwitch.FIRE ? "auto: engine running (" + Protocol.volts(centivolts) + " V), switching"
                : "auto: no battery reading within " + AutoSwitch.FALLBACK_MS / 1000 + " s, switching anyway");
        Intent i = new Intent(this, BootService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (RuntimeException e) {
            log("auto: switch service not started: " + e);
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
