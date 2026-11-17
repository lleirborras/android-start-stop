package com.furgo.nostartstop;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.tw.john.TWUtil;
import android.util.Log;

import java.util.Arrays;
import java.util.Locale;

/** One connection to the CAN box through TWUtil. Main-thread only. */
final class CanBox {
    interface Listener {
        /** Called on the main thread for every settings status frame (id 56). */
        void onState(boolean startStopEnabled, boolean permanentlyClosed);
        /** Called on the main thread with a short human-readable line (errors and sent commands). */
        void onLog(String line);
    }

    private static final String TAG = "NoStartStop";
    private static int instances;

    private final Listener listener;
    private final String tag = TAG + "-" + (instances++);
    private TWUtil tw;
    private boolean shortFrameLogged;
    private byte[] lastFrame;

    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (tw != null && msg.what == Protocol.MSG_CANBOX && msg.arg1 == Protocol.STATUS_SETTINGS
                    && msg.obj instanceof byte[]) {
                onFrame((byte[]) msg.obj);
            }
        }
    };

    CanBox(Listener listener) {
        this.listener = listener;
    }

    boolean open() {
        if (tw != null) return true;
        TWUtil t = null;
        try {
            t = new TWUtil();
            int rc = t.open(new short[]{(short) Protocol.MSG_CANBOX});
            if (rc == 0) {
                t.addHandler(tag, handler);
                t.start();
                tw = t;
                log("TWUtil open");
                return true;
            }
            log("TWUtil.open failed, rc=" + rc);
        } catch (Throwable e) {
            log("TWUtil unavailable: " + e);
        }
        if (t != null) {
            try {
                t.removeHandler(tag);
                t.close();
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    void close() {
        handler.removeCallbacksAndMessages(null);
        if (tw == null) return;
        TWUtil t = tw;
        tw = null;
        try {
            t.removeHandler(tag);
            t.stop();
            t.close();
        } catch (Throwable e) {
            log("close failed: " + e);
        }
    }

    void query() {
        write(Protocol.CMD_QUERY, Protocol.query());
    }

    void set(boolean startStopEnabled, boolean permanentlyClosed) {
        if (tw == null) {
            log("not open, nothing sent");
            return;
        }
        int rc = write(Protocol.CMD_SET, Protocol.set(startStopEnabled, permanentlyClosed));
        log("sent start-stop enabled=" + startStopEnabled + " permClosed=" + permanentlyClosed
                + ", rc=" + rc);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                query();
            }
        }, 1000);
    }

    private void onFrame(byte[] b) {
        if (b.length < Protocol.MIN_STATUS_LENGTH) {
            if (!shortFrameLogged) {
                shortFrameLogged = true;
                log("status frame too short: " + hex(b));
            }
            return;
        }
        if (!Arrays.equals(b, lastFrame)) {
            lastFrame = b.clone();
            log("status " + hex(b));
        }
        listener.onState(Protocol.startStopEnabled(b), Protocol.permanentlyClosed(b));
    }

    private int write(int cmd, byte[] data) {
        if (tw == null) {
            log("not open");
            return -1;
        }
        try {
            return tw.write(Protocol.MSG_CANBOX, cmd, data.length, data);
        } catch (Throwable e) {
            log("write failed: " + e);
            return -1;
        }
    }

    private void log(String line) {
        Log.i(TAG, line);
        listener.onLog(line);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02X ", x));
        return sb.toString().trim();
    }
}
