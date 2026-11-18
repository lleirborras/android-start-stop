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
            sentBit = canBox.toggle();
            if (sentBit < 0) {
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
        startedAt = SystemClock.elapsedRealtime();
        handler.post(openTick);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
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
        } else if (sentBit >= 0 && echo == (sentBit == 1)) {
            onLog("echo bit changed to " + sentBit + ", done");
            finish();
        }
    }

    @Override
    public void onLog(String line) {
        LogFile.append(this, "boot: " + line);
    }

    private void finish() {
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
                .setContentText("Switching stop-start off")
                .build();
    }
}
