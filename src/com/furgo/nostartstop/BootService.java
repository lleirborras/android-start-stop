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

/** Applies start-stop off (enabled=false, permanently closed) once after boot, then stops. */
public class BootService extends Service implements CanBox.Listener {
    private static final long TICK_MS = 2_000;
    private static final long BLIND_SEND_MS = 5_000;
    private static final long TIMEOUT_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private CanBox canBox;
    private long startedAt;
    private long lastSendAt;
    private boolean statusSeen;
    private boolean blindSent;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long elapsed = SystemClock.elapsedRealtime() - startedAt;
            if (elapsed >= TIMEOUT_MS) {
                finish();
                return;
            }
            // The MCU link may not be up yet this early in boot.
            if (!canBox.open()) {
                handler.postDelayed(this, TICK_MS);
                return;
            }
            if (!statusSeen && !blindSent && elapsed >= BLIND_SEND_MS) {
                blindSent = true;
                send();
            }
            canBox.query();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (canBox != null) return START_NOT_STICKY;
        startForeground(1, buildNotification());
        canBox = new CanBox(this);
        startedAt = SystemClock.elapsedRealtime();
        handler.post(tick);
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
    public void onState(boolean startStopEnabled, boolean permanentlyClosed) {
        statusSeen = true;
        if (!startStopEnabled && permanentlyClosed) {
            finish();
        } else {
            send();
        }
    }

    @Override
    public void onLog(String line) {
    }

    private void send() {
        long now = SystemClock.elapsedRealtime();
        if (lastSendAt != 0 && now - lastSendAt < TICK_MS) return;
        lastSendAt = now;
        canBox.set(false, true);
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
                .setContentText("Turning stop-start off")
                .build();
    }
}
