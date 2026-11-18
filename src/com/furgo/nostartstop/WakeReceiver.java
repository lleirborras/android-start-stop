package com.furgo.nostartstop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.List;

/**
 * Logs the broadcasts that may mark ignition on and off, and treats ACC on as a wake. Exported, so any app can send
 * these actions: the one-minute wake dedupe and the per-cycle guard limit what a fake one can do.
 */
public class WakeReceiver extends BroadcastReceiver {
    static final String ACC_ON = "com.unisound.intent.action.ACC_ON";
    static final String ACC_OFF = "com.unisound.intent.action.ACC_OFF";
    static final String DO_SLEEP = "com.unisound.intent.action.DO_SLEEP";
    private static final List<String> MANIFEST_ACTIONS = Arrays.asList(ACC_ON, ACC_OFF, DO_SLEEP,
            "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MEDIA_MOUNTED);
    static final List<String> RUNTIME_ACTIONS = Arrays.asList(ACC_ON, ACC_OFF, DO_SLEEP,
            Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED);
    private static final int MAX_LINES = 30;
    private static int lines;   // per process

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!MANIFEST_ACTIONS.contains(action)) return;
        log(context, action, "manifest");
        if (ACC_ON.equals(action)) {
            WatchService.start(context, Prefs.newWake(context, "ACC_ON") ? WatchService.EXTRA_WAKE : null);
        } else if (ACC_OFF.equals(action) || DO_SLEEP.equals(action)) {
            WatchService.start(context, WatchService.EXTRA_SLEEP);
        } else {
            WatchService.start(context, null);
        }
    }

    /** Only the action name, never extras or data. Callers pass only actions from the lists above. */
    static synchronized void log(Context context, String action, String via) {
        if (lines >= MAX_LINES) return;
        lines++;
        LogFile.append(context, "broadcast " + action + " (" + via + "), " + BootId.up(SystemClock.elapsedRealtime()));
    }
}
