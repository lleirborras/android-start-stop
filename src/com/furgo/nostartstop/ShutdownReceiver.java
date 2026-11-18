package com.furgo.nostartstop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Logs a clean shutdown, so a reboot without one in the log points to a power cut. Android 9 and later only send it to
 * receivers registered at run time, so there {@link DeviceWatch} logs it while the app is open.
 */
public class ShutdownReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_SHUTDOWN.equals(intent.getAction())) return;
        LogFile.append(context, "shutdown, " + BootId.up(SystemClock.elapsedRealtime()));
    }
}
