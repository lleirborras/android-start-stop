package com.furgo.nostartstop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

/** Logs head unit power and network facts, to learn how it behaves across ignition cycles. No car data. */
final class DeviceWatch {
    private final Context context;
    private final CanBox.Listener out;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long NETWORK_SETTLE_MS = 1000;
    private static final int MAX_NETWORK_LINES = 50;
    private static int networkLines;   // per process, so a flapping link cannot flood the log
    private String lastNetwork;
    private boolean started;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            String up = BootId.up(SystemClock.elapsedRealtime());
            if (Intent.ACTION_SHUTDOWN.equals(action)) out.onLog("shutdown, " + up);
            else out.onLog((Intent.ACTION_SCREEN_ON.equals(action) ? "screen on, " : "screen off, ") + up);
        }
    };

    private ConnectivityManager.NetworkCallback callback;

    DeviceWatch(Context context, CanBox.Listener out) {
        this.context = context;
        this.out = out;
    }

    /** For example "boot count 7, up 42 s, network down". */
    static String facts(Context c) {
        int count = -1;
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                count = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            }
        } catch (Throwable ignored) {
        }
        return BootId.facts(count, SystemClock.elapsedRealtime()) + ", " + networkNow(c);
    }

    private static String networkNow(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected() ? "network up (" + type(info) + ")" : "network down";
        } catch (Throwable e) {
            return "network unknown";
        }
    }

    private static String type(NetworkInfo info) {
        switch (info.getType()) {
            case ConnectivityManager.TYPE_WIFI: return "wifi";
            case ConnectivityManager.TYPE_MOBILE: return "cellular";
            case ConnectivityManager.TYPE_ETHERNET: return "ethernet";
            case ConnectivityManager.TYPE_BLUETOOTH: return "bluetooth";
            case ConnectivityManager.TYPE_VPN: return "vpn";
            default: return "other";
        }
    }

    void start() {
        if (started) return;
        started = true;
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        // From Android 9 the shutdown broadcast only reaches receivers registered at run time; before, ShutdownReceiver gets it.
        if (Build.VERSION.SDK_INT >= 28) f.addAction(Intent.ACTION_SHUTDOWN);
        context.registerReceiver(screen, f);
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        lastNetwork = networkNow(context);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                changed();
            }

            @Override
            public void onLost(Network network) {
                changed();
            }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder().build(), callback);
        } catch (Throwable e) {
            callback = null;
            out.onLog("network watch unavailable: " + e);
        }
    }

    void stop() {
        if (!started) return;
        started = false;
        context.unregisterReceiver(screen);
        if (callback != null) {
            try {
                ((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE))
                        .unregisterNetworkCallback(callback);
            } catch (Throwable ignored) {
            }
            callback = null;
        }
        handler.removeCallbacks(report);
    }

    /**
     * Called on ConnectivityManager's callback thread. Registering replays the networks already up, so only the
     * settled active network is logged.
     */
    private void changed() {
        handler.removeCallbacks(report);
        handler.postDelayed(report, NETWORK_SETTLE_MS);
    }

    private final Runnable report = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            String now = networkNow(context);
            if (now.equals(lastNetwork) || networkLines >= MAX_NETWORK_LINES) return;
            lastNetwork = now;
            networkLines++;
            out.onLog(now);
        }
    };
}
