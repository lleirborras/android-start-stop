package com.furgo.nostartstop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.List;

/**
 * Logs broadcasts that may mark ignition on and off, to learn how the head unit behaves. Exported, so any app can send
 * them: they are only logged and never start or end a cycle or cause a switch.
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
    private static final LogBudget LINES = new LogBudget(30);

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!MANIFEST_ACTIONS.contains(action)) return;
        log(context, action, "manifest");
        WatchService.start(context, false);
    }

    /** Only the action name, never extras or data. Callers pass only actions from the lists above. */
    static void log(Context context, String action, String via) {
        long now = SystemClock.elapsedRealtime();
        if (!LINES.take(now)) return;
        LogFile.append(context, "broadcast " + action + " (" + via + "), " + BootId.up(now));
    }
}
