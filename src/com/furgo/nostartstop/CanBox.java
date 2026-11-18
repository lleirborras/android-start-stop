package com.furgo.nostartstop;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.tw.john.TWUtil;
import android.util.Log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** One connection to the CAN box through TWUtil. Main-thread only. */
final class CanBox {
    interface Listener {
        /** Called on the main thread for every settings status frame (id 56) with its echo bit, see {@link Protocol#echo}. */
        void onEcho(boolean echo);
        /** Called on the main thread with a short human-readable line (errors and sent commands). */
        void onLog(String line);
    }

    private static final String TAG = "NoStartStop";
    private static final int MAX_LINES_PER_FRAME_ID = 20;
    private static final int MAX_FRAME_IDS = 64;
    private static final int MAX_FRAME_LINES = 400;
    private static int instances;

    private final Listener listener;
    private final String tag = TAG + "-" + (instances++);
    private TWUtil tw;
    private boolean shortFrameLogged;
    private byte[] lastFrame;
    // Other frames are logged, only when the user opts in, so the real stop-start state can be found in them later.
    private boolean captureFrames;
    private int frameLines;
    private final Map<Integer, byte[]> otherFrames = new HashMap<Integer, byte[]>();
    private final Map<Integer, Integer> otherFrameLines = new HashMap<Integer, Integer>();

    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (tw == null || msg.what != Protocol.MSG_CANBOX || !(msg.obj instanceof byte[])) return;
            if (msg.arg1 == Protocol.STATUS_SETTINGS) onFrame((byte[]) msg.obj);
            else onOtherFrame(msg.arg1, (byte[]) msg.obj);
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
                lastFrame = null;
                otherFrames.clear();
                otherFrameLines.clear();
                frameLines = 0;
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

    void setCaptureFrames(boolean on) {
        captureFrames = on;
    }

    void query() {
        write(Protocol.CMD_QUERY, Protocol.query());
    }

    /**
     * Sends one stop-start switch command, which flips stop-start on the car. Returns the bit sent,
     * which the echo of the next frames should show, or -1 when nothing was sent.
     */
    int toggle() {
        if (tw == null) {
            log("not open, nothing sent");
            return -1;
        }
        int bit = Protocol.toggleValue(lastFrame);
        int rc = write(Protocol.CMD_SET, Protocol.set(bit));
        log("sent stop-start switch, bit=" + bit + ", rc=" + rc);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                query();
            }
        }, 1000);
        return bit;
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
        listener.onEcho(Protocol.echo(b));
    }

    private void onOtherFrame(int id, byte[] b) {
        if (!captureFrames || frameLines >= MAX_FRAME_LINES) return;
        Integer lines = otherFrameLines.get(id);
        if (lines == null && otherFrameLines.size() >= MAX_FRAME_IDS) return;
        int n = lines == null ? 0 : lines;
        if (n >= MAX_LINES_PER_FRAME_ID || Arrays.equals(b, otherFrames.get(id))) return;
        otherFrames.put(id, b.clone());
        otherFrameLines.put(id, n + 1);
        frameLines++;
        log("frame " + id + " " + hex(b));
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
