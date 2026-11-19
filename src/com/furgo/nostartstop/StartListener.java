package com.furgo.nostartstop;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

/**
 * Only a way to get started: Android binds an enabled notification listener by itself when the head unit powers on,
 * and again after the app was killed, without the app's screen. It reads no notification.
 */
public class StartListener extends NotificationListenerService {
    private static final LogBudget LINES = new LogBudget(40);

    @Override
    public void onListenerConnected() {
        if (LINES.take(SystemClock.elapsedRealtime())) {
            // Time asleep tells a rebind after the head unit woke from one right after it stopped the app.
            long asleep = (SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()) / 1000;
            LogFile.append(this, "listener: connected, asleep " + asleep + " s since boot, " + DeviceWatch.facts(this));
        }
        WatchService.start(this, PowerWatch.BY_LISTENER);
    }

    @Override
    public void onListenerDisconnected() {
        if (LINES.take(SystemClock.elapsedRealtime())) LogFile.append(this, "listener: disconnected");
    }

    /** The user gave the app notification access. */
    static boolean enabled(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (on == null) return false;
        ComponentName me = new ComponentName(c, StartListener.class);
        for (String s : on.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(s))) return true;
        }
        return false;
    }

    /** Android 10 and older never bind a notification listener on a low-memory device. */
    static boolean available(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        return Build.VERSION.SDK_INT > 29 || am == null || !am.isLowRamDevice();
    }
}
