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
 * Sends exactly one stop-start switch per ignition cycle, then stops. Started by {@link WatchService} once the engine
 * runs. The car turns stop-start on at every engine start, so one switch turns it off; a second one would turn it back on.
 */
public class BootService extends Service implements CanBox.Listener, BootFlow.Shell {
    /** True while the service is working; the main screen does not send meanwhile. Main thread only. */
    static boolean running;
    /** Set by the main screen while it is shown, to redraw when {@link #running} changes. */
    static Runnable onRunningChanged;

    private static final LogBudget LINES = new LogBudget(200);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BootFlow flow = new BootFlow(this);
    // Indexed by the timer itself, so in the order of their values.
    private final Runnable[] timers = {timer(BootFlow.OPEN_TICK), timer(BootFlow.SEND_ONCE), timer(BootFlow.NO_ECHO),
            timer(BootFlow.STOP)};
    private CanBox canBox;

    private Runnable timer(final int which) {
        return new Runnable() {
            @Override
            public void run() {
                flow.onTimer(which);
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        flow.start();
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
        flow.onEcho(echo);
    }

    @Override
    public void onLog(String line) {
        if (LINES.take(SystemClock.elapsedRealtime())) LogFile.append(this, "auto: " + line);
    }

    @Override
    public long now() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public String currentCycle() {
        return Prefs.currentCycle(this);
    }

    @Override
    public boolean bootSwitchDone(String cycle) {
        return Prefs.bootSwitchDone(this, cycle);
    }

    @Override
    public long lastSwitchAt(String cycle) {
        return Prefs.lastSwitchAt(this, cycle);
    }

    @Override
    public void markBootSwitch(String cycle) {
        Prefs.markBootSwitch(this, cycle);
    }

    @Override
    public void setBootSwitchFailed(String cycle) {
        Prefs.setBootSwitchFailed(this, cycle);
    }

    @Override
    public void recordSwitch(String cycle) {
        Prefs.recordSwitch(this, true, cycle);
    }

    @Override
    public String deviceFacts() {
        return DeviceWatch.facts(this);
    }

    @Override
    public void newLink() {
        canBox = new CanBox(this, this);
    }

    @Override
    public boolean openLink() {
        return canBox.open();
    }

    @Override
    public void query() {
        canBox.query();
    }

    @Override
    public boolean linkReady() {
        return canBox.ready();
    }

    @Override
    public int toggle() {
        return canBox.toggle();
    }

    @Override
    public void closeLink() {
        canBox.close();
    }

    @Override
    public void post(int timer) {
        handler.post(timers[timer]);
    }

    @Override
    public void postDelayed(int timer, long ms) {
        handler.postDelayed(timers[timer], ms);
    }

    @Override
    public void cancel(int timer) {
        handler.removeCallbacks(timers[timer]);
    }

    @Override
    public void cancelAll() {
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void running(boolean on) {
        setRunning(on);
    }

    @Override
    public void endForeground() {
        stopForeground(true);
    }

    @Override
    public void endService() {
        stopSelf();
    }

    private static void setRunning(boolean on) {
        if (running == on) return;
        running = on;
        if (onRunningChanged != null) onRunningChanged.run();
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
