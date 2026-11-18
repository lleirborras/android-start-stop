package com.furgo.nostartstop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** An update stops the app, so the watch starts again here instead of at the next boot. Not a wake. */
public class UpdatedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        WatchService.start(context, false);
    }
}
