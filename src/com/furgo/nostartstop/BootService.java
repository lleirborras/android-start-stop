package com.furgo.nostartstop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Sends exactly one stop-start switch after boot, then stops. The car turns stop-start on at every
 * engine start, so one switch turns it off; a second one would turn it back on.
 */
public class BootService extends Service implements CanBox.Listener {
    private static final long OPEN_RETRY_MS = 2_000;
    private static final long OPEN_TIMEOUT_MS = 30_000;
    private static final long STATUS_WAIT_MS = 5_000;
    private static final long ECHO_WAIT_MS = 3_000;

    /** True while the service is working; the main screen does not send meanwhile. Main thread only. */
    static boolean running;
    /** Set by the main screen while it is shown, to redraw when {@link #running} changes. */
    static Runnable onRunningChanged;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private CanBox canBox;
    private long startedAt;
    private boolean sent;
    private int sentBit = -1;

    private final Runnable openTick = new Runnable() {
        @Override
        public void run() {
            // The MCU link may not be up yet this early in boot.
            if (canBox.open()) {
                canBox.query();
                handler.postDelayed(sendOnce, STATUS_WAIT_MS);
                return;
            }
            if (SystemClock.elapsedRealtime() - startedAt >= OPEN_TIMEOUT_MS) {
                onLog("no link to the car after " + OPEN_TIMEOUT_MS / 1000 + " s, nothing sent");
                Prefs.setBootSwitchFailed(BootService.this);
                finish();
                return;
            }
            handler.postDelayed(this, OPEN_RETRY_MS);
        }
    };

    private final Runnable sendOnce = new Runnable() {
        @Override
        public void run() {
            if (sent) return;
            sent = true;
            handler.removeCallbacks(this);
            if (alreadySwitched()) {
                finish();
                return;
            }
            Prefs.markBootSwitch(BootService.this);
            sentBit = canBox.toggle();
            if (sentBit < 0) {
                Prefs.setBootSwitchFailed(BootService.this);
                onLog("switch failed, nothing sent");
                finish();
                return;
            }
            Prefs.recordSwitch(BootService.this, true);
            if (sentBit == 0) {
                // A cleared echo looks the same as no answer, so it cannot confirm this command.
                onLog("sent with bit 0, which the echo cannot confirm, done");
                finish();
                return;
            }
            handler.postDelayed(noEcho, ECHO_WAIT_MS);
        }
    };

    private final Runnable noEcho = new Runnable() {
        @Override
        public void run() {
            onLog("echo bit did not change within " + ECHO_WAIT_MS / 1000 + " s, done");
            finish();
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (canBox != null) return START_NOT_STICKY;
        startForeground(1, buildNotification());
        canBox = new CanBox(this);
        onLog("service started");
        if (alreadySwitched()) {
            finish();
            return START_NOT_STICKY;
        }
        setRunning(true);
        startedAt = SystemClock.elapsedRealtime();
        handler.post(openTick);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        setRunning(false);
        handler.removeCallbacksAndMessages(null);
        if (canBox != null) canBox.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onEcho(boolean echo) {
        if (!sent) {
            onLog("status received, echo bit " + (echo ? 1 : 0));
            sendOnce.run();
        } else if (sentBit == 1 && echo) {
            onLog("echo bit changed to " + sentBit + ", done");
            finish();
        }
    }

    @Override
    public void onLog(String line) {
        LogFile.append(this, "boot: " + line);
    }

    /** A second switch in the same boot would turn stop-start back on, whoever sent the first. */
    private boolean alreadySwitched() {
        if (Prefs.bootSwitchDone(this)) {
            onLog("already switched during this boot, nothing sent");
            return true;
        }
        if (Prefs.lastSwitchAt(this) != 0) {
            Prefs.markBootSwitch(this);
            onLog("already switched from the screen during this boot, nothing sent");
            return true;
        }
        return false;
    }

    private static void setRunning(boolean on) {
        if (running == on) return;
        running = on;
        if (onRunningChanged != null) onRunningChanged.run();
    }

    private void finish() {
        setRunning(false);
        handler.removeCallbacksAndMessages(null);
        canBox.close();
        stopSelf();
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("svc", "No Start-Stop", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
            b = new Notification.Builder(this, "svc");
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("No Start-Stop")
                .setContentText("Sending stop-start switch")
                .build();
    }
}
