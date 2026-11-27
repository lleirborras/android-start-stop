package com.furgo.nostartstop;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
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

    /** Power messages for the watch service, called on the main thread. */
    interface PowerListener {
        /** Message 514 arg1, every time it arrives. */
        void onPowerState(int state);
        /** Every valid battery reading, before the log rate limit. */
        void onBattery(int centivolts);
        /** Beta FYT and Microntek units: the ignition as the head unit reports it. */
        void onIgnition(boolean on);
        /** Beta FYT and Microntek units: the engine runs; why is for the log. */
        void onEngineRunning(String why);
    }

    private static final String TAG = "NoStartStop";
    private static final int MAX_LINES_PER_FRAME_ID = 20;
    private static final int MAX_FRAME_IDS = 64;
    private static final int MAX_FRAME_LINES = 400;
    private static final int MAX_FUNCTION_LINES = 40;
    private static final int MAX_ENGINE_LINES = 100;
    private static final int MAX_BATTERY_LINES = 100;
    private static final long CAR_DATA_EVERY_MS = 10_000;
    private static final int MAX_UNIT_CONFIG_BYTES = 256;
    private static final int MAX_UNIT_ID_CHARS = 200;
    private static final short[] ALL_IDS = {(short) Protocol.MSG_CANBOX, (short) Protocol.MSG_ENGINE,
            (short) Protocol.MSG_POWER, (short) Protocol.MSG_UNIT_ID, (short) Protocol.MSG_UNIT_CONFIG};
    private static final short[] CANBOX_IDS = {(short) Protocol.MSG_CANBOX};
    private static final short[] WATCH_IDS = {(short) Protocol.MSG_POWER_STATE, (short) Protocol.MSG_POWER};
    private static int instances;

    private final Context context;
    private final Listener listener;
    // Beta: set instead of tw on FYT and Microntek head units.
    private OtherLink other;
    // Set only for the watch service, which opens only the power messages and never sends.
    private PowerListener power;
    private String openFailure;
    private final String tag = TAG + "-" + (instances++);
    private TWUtil tw;
    private boolean shortFrameLogged;
    private byte[] lastFrame;
    // Other frames are logged, only when the user opts in, so the real stop-start state can be found in them later.
    private boolean captureFrames;
    private int frameLines;
    private final Map<Integer, byte[]> otherFrames = new HashMap<Integer, byte[]>();
    private final Map<Integer, Integer> otherFrameLines = new HashMap<Integer, Integer>();
    // Frame 57 is a candidate for the real stop-start state, logged always. Kept per process: the screen reconnects on
    // every resume and must not log the same frame again.
    private static byte[] lastFunction;
    private static boolean unitIdLogged, unitConfigLogged;
    private int functionLines;
    private boolean firstFunctionPending;
    // Unit and engine messages: their ids are unverified, so they are only opened while the user records car data.
    private boolean extraIds;
    private String extraIdsRefused;
    private int lastRpm = -1;
    private boolean engineLogged;
    private int loggedRpm, loggedSpeed, engineLines;
    private long engineLoggedAt;
    private int loggedCentivolts = -1, batteryLines;
    private long batteryLoggedAt;

    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (tw == null) return;
            if (power != null) {
                if (msg.what == Protocol.MSG_POWER_STATE) power.onPowerState(msg.arg1);
                else if (msg.what == Protocol.MSG_POWER) onCarMessage(msg);
                return;
            }
            if (msg.what != Protocol.MSG_CANBOX) {
                if (captureFrames) onCarMessage(msg);
                return;
            }
            if (!(msg.obj instanceof byte[])) return;
            onCanboxFrame(msg.arg1, (byte[]) msg.obj);
        }
    };

    private void onCanboxFrame(int id, byte[] b) {
        if (id == Protocol.STATUS_SETTINGS) onFrame(b);
        else if (id == Protocol.STATUS_FUNCTION) onFunction(b);
        else onOtherFrame(id, b);
    }

    CanBox(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** True while the beta FYT or Microntek link is the open one. */
    boolean usesOtherLink() {
        return other != null;
    }

    /** Beta: why this head unit's car or CAN box is not supported, or null. */
    String unsupported() {
        if (other != null) return other.unsupported();
        return tw == null && Prefs.otherUnits(context) ? OtherLink.refusedAtOpen : null;
    }

    private boolean linked() {
        return tw != null || other != null;
    }

    private final OtherLink.Sink sink = new OtherLink.Sink() {
        @Override
        public void frame(final int id, final byte[] data) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (other != null) onCanboxFrame(id, data);
                }
            });
        }

        @Override
        public void log(final String line) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    CanBox.this.log(line);
                }
            });
        }

        @Override
        public void ignition(final boolean on) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (other != null && power != null) power.onIgnition(on);
                }
            });
        }

        @Override
        public void engineRunning(final String why) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (other != null && power != null) power.onEngineRunning(why);
                }
            });
        }
    };

    /**
     * Call before {@link #open}. Then open() asks for the power messages only, without the CAN box, and does not log
     * its failures: {@link #openFailure} has the last one.
     */
    void watchPower(PowerListener listener) {
        power = listener;
    }

    String openFailure() {
        return openFailure;
    }

    /** With captureFrames set, also opens the engine and unit ids, falling back to the CAN box alone if they are refused. */
    boolean open() {
        if (linked()) return true;
        extraIdsRefused = null;
        TWUtil t = captureFrames && power == null ? connect(ALL_IDS, true) : null;
        extraIds = t != null;
        if (t == null) t = connect(power != null ? WATCH_IDS : CANBOX_IDS, false);
        if (t == null) return openOther();
        lastFrame = null;
        otherFrames.clear();
        otherFrameLines.clear();
        frameLines = 0;
        functionLines = 0;
        resetEngine();
        engineLines = 0;
        batteryLines = 0;
        try {
            t.addHandler(tag, handler);
            t.start();
        } catch (Throwable e) {
            failed("TWUtil unavailable: " + e);
            release(t);
            return false;
        }
        tw = t;
        log(extraIdsRefused == null ? "TWUtil open"
                : "TWUtil open, CAN box only (engine and unit ids refused: " + extraIdsRefused + ")");
        if (power != null) return true;
        // The caller asks for the settings frame first; frame 57 and the car data follow its answer.
        firstFunctionPending = true;
        handler.postDelayed(firstFunction, 500);
        return true;
    }

    /** Beta: without TWUtil, the FYT or Microntek link. For the watch service it only reads the ignition and engine. */
    private boolean openOther() {
        if (!Prefs.otherUnits(context)) return false;
        OtherLink o = OtherLink.open(context, sink, power != null);
        if (o == null) return false;
        other = o;
        lastFrame = null;
        otherFrames.clear();
        otherFrameLines.clear();
        frameLines = 0;
        functionLines = 0;
        log("beta: " + o.name() + " link open");
        return true;
    }

    private final Runnable firstFunction = new Runnable() {
        @Override
        public void run() {
            if (!firstFunctionPending) return;
            firstFunctionPending = false;
            handler.removeCallbacks(this);
            write(Protocol.CMD_QUERY, Protocol.query(Protocol.STATUS_FUNCTION));
            if (captureFrames) requestCarData();
        }
    };

    /** Logs only the failure that ends the attempt, so a failed open still writes one line. */
    private TWUtil connect(short[] ids, boolean extra) {
        TWUtil t = null;
        String failure;
        try {
            t = new TWUtil();
            int rc = t.open(ids);
            if (rc == 0) return t;
            failure = extra ? "rc=" + rc : "TWUtil.open failed, rc=" + rc;
        } catch (Throwable e) {
            failure = extra ? String.valueOf(e) : "TWUtil unavailable: " + e;
        }
        release(t);
        if (extra) extraIdsRefused = failure;
        else failed(failure);
        return null;
    }

    private void failed(String failure) {
        if (power != null) openFailure = failure;
        else log(failure);
    }

    private void release(TWUtil t) {
        if (t == null) return;
        try {
            t.removeHandler(tag);
            t.close();
        } catch (Throwable ignored) {
        }
    }

    void close() {
        handler.removeCallbacksAndMessages(null);
        if (other != null) {
            other.close();
            other = null;
        }
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
        boolean was = captureFrames;
        captureFrames = on;
        if (!on) resetEngine();
        else if (!was && tw != null) requestCarData();
    }

    /** Head unit identity, only asked for while the user records car data. */
    private void requestCarData() {
        if (!extraIds || (unitIdLogged && unitConfigLogged)) return;
        try {
            tw.write(Protocol.MSG_UNIT_ID, 255, 0);
            tw.write(Protocol.MSG_UNIT_CONFIG, 255, 0);
        } catch (Throwable e) {
            log("unit query failed: " + e);
        }
    }

    private void resetEngine() {
        lastRpm = -1;
        engineLogged = false;
        engineLoggedAt = 0;
        loggedCentivolts = -1;
        batteryLoggedAt = 0;
    }

    void query() {
        write(Protocol.CMD_QUERY, Protocol.query());
    }

    /**
     * Sends one stop-start switch command, which flips stop-start on the car. Its bit is the opposite of the
     * echo in the last status frame, so call it right after a fresh frame. Returns the bit sent, or -1 when
     * nothing was sent.
     */
    int toggle() {
        if (!linked()) {
            log("not open, nothing sent");
            return -1;
        }
        int bit = Protocol.toggleValue(lastFrame);
        int rc = write(Protocol.CMD_SET, Protocol.set(bit));
        if (rc < 0) {
            log("stop-start switch not sent, bit=" + bit + ", rc=" + rc);
            return -1;
        }
        log("sent stop-start switch, bit=" + bit + ", rc=" + rc
                + (captureFrames && lastRpm >= 0 ? ", rpm=" + lastRpm : ""));
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                query();
            }
        }, 1000);
        // Frame 57 may change some time after the car acts on the switch.
        Runnable after = new Runnable() {
            @Override
            public void run() {
                write(Protocol.CMD_QUERY, Protocol.query(Protocol.STATUS_FUNCTION));
            }
        };
        handler.postDelayed(after, 1500);
        handler.postDelayed(after, 5000);
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
        if (tw != null) firstFunction.run();
    }

    private void onFunction(byte[] b) {
        if (functionLines >= MAX_FUNCTION_LINES || Arrays.equals(b, lastFunction)) return;
        lastFunction = b.clone();
        functionLines++;
        int field = Protocol.stopStartField(b);
        log("function " + hex(b) + (field < 0 ? "" : " (stop-start field=" + field + ")"));
    }

    private void onCarMessage(Message msg) {
        long now = SystemClock.elapsedRealtime();
        if (msg.what == Protocol.MSG_ENGINE) {
            int rpm = Protocol.rpm(msg.arg2);
            int speed = msg.arg1;
            lastRpm = rpm;
            if (engineLines >= MAX_ENGINE_LINES || (engineLogged && now - engineLoggedAt < CAR_DATA_EVERY_MS)
                    || !Protocol.engineChanged(engineLogged, loggedRpm, loggedSpeed, rpm, speed)) return;
            engineLogged = true;
            loggedRpm = rpm;
            loggedSpeed = speed;
            engineLoggedAt = now;
            engineLines++;
            log("engine rpm=" + (rpm < 0 ? "?" : String.valueOf(rpm)) + " speed=" + speed);
        } else if (msg.what == Protocol.MSG_POWER && msg.arg1 == 0 && msg.obj instanceof byte[]) {
            int cv = Protocol.batteryCentivolts((byte[]) msg.obj);
            if (power != null && cv >= 0) power.onBattery(cv);
            if (cv < 0 || batteryLines >= MAX_BATTERY_LINES
                    || (loggedCentivolts >= 0 && now - batteryLoggedAt < CAR_DATA_EVERY_MS)
                    || !Protocol.batteryChanged(loggedCentivolts, cv)) return;
            loggedCentivolts = cv;
            batteryLoggedAt = now;
            batteryLines++;
            log("battery " + Protocol.volts(cv) + " V");
        } else if (msg.what == Protocol.MSG_UNIT_ID && msg.arg1 == 0 && msg.obj instanceof String && !unitIdLogged) {
            unitIdLogged = true;
            String id = ((String) msg.obj).replaceAll("\\p{Cntrl}", " ").trim();
            log("unit id " + (id.length() > MAX_UNIT_ID_CHARS ? id.substring(0, MAX_UNIT_ID_CHARS) : id));
        } else if (msg.what == Protocol.MSG_UNIT_CONFIG && msg.obj instanceof byte[] && !unitConfigLogged) {
            unitConfigLogged = true;
            byte[] b = (byte[]) msg.obj;
            log("unit config " + hex(b.length > MAX_UNIT_CONFIG_BYTES ? Arrays.copyOf(b, MAX_UNIT_CONFIG_BYTES) : b));
        }
    }

    private void onOtherFrame(int id, byte[] b) {
        if (!captureFrames || Protocol.noisyFrame(id) || frameLines >= MAX_FRAME_LINES) return;
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
        if (other != null) return other.write(cmd, data);
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
