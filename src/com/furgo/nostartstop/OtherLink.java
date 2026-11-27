package com.furgo.nostartstop;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.Method;

/**
 * Beta: the link to the CAN box on head units without TWUtil. Sends only the PSA Raise stop-start command and
 * status query, and only when the unit says it is set up for PSA with a Raise box. Untested on these units.
 */
abstract class OtherLink {
    /** Called on any thread; {@link CanBox} moves it to the main thread. */
    interface Sink {
        void frame(int id, byte[] data);
        void log(String line);
        /** Watch mode only: the ignition as the head unit reports it. */
        void ignition(boolean on);
        /**
         * Watch mode only: the engine runs; why is for the log. afterStop: the engine speed shows it running again
         * after it was seen stopped, which may undo a switch sent before.
         */
        void engineRunning(String why, boolean afterStop);
    }

    /** Without an engine signal, the engine is taken as running this long after ignition on. A guess. */
    static final long GUESS_MS = 15_000;
    static final String GUESS = "assumed, " + GUESS_MS / 1000 + " s after ignition on";

    /**
     * Only after {@link SendGate} let it through, see CanBox.write; car is the {@link #car} it saw. Returns -2 when
     * the link is not ready yet, -1 when it failed: nothing was sent then.
     */
    abstract int write(int cmd, byte[] data, String car);

    /** Watch mode: whether the engine running can be reported at all, by rpm or by the opt-in guess. */
    boolean engineSignal() {
        return true;
    }

    /** Whether a write can be attempted now; until then it returns -2. */
    boolean ready() {
        return true;
    }

    abstract void close();

    abstract String name();

    abstract Detection.Platform platform();

    /** The car the head unit reports to this link, for the send gate: FYT canbus id, Microntek cfg_canbus; null until known. */
    abstract String car();

    /** Right before a send: reads the car again where it can change without a reopen. */
    void refreshCar() {
    }

    /**
     * The link for this head unit, or null after logging why there is none. With watch it only reads, and reports
     * the ignition and the engine through the sink.
     */
    static OtherLink open(Context c, Detection d, Sink sink, boolean watch) {
        Detection.Platform p = d.otherPlatform();
        if (p == Detection.Platform.AMBIGUOUS) {
            if (!bothLogged) sink.log("beta: both FYT and Microntek found, nothing will be sent");
            bothLogged = true;
            return null;
        }
        if (p == Detection.Platform.MICRONTEK) return Microntek.open(c, sink, watch);
        if (p == Detection.Platform.FYT) return Fyt.open(c, sink, watch);
        if (!noServiceLogged) sink.log("beta: no FYT or Microntek CAN service on this head unit");
        noServiceLogged = true;
        return null;
    }

    // Open is retried every few seconds at boot; the reason is the same each time.
    private static boolean noServiceLogged, bothLogged;

    private static String versionName(Context c, String pkg) {
        try {
            return c.getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Microntek MTCD/MTCE (HCT build): raw Raise frames by broadcast, status back by broadcast. */
    static final class Microntek extends OtherLink {
        static final String PACKAGE = "android.microntek.canbus";
        private static final String SEND = "com.microntek.canbus.send";
        private static final String SEND_KEY = "com.microntek.sendkey";
        private static final String SYNC = "com.microntek.sync";
        private static final long POLL_MS = 2_000;
        private final Context context;
        private final Sink sink;
        private final boolean watch;
        private final Handler handler = new Handler(Looper.getMainLooper());
        // cfg_canbus, as last read.
        private volatile String type;
        private String power;
        private final Runnable poll = new Runnable() {
            @Override
            public void run() {
                onPower(carCall(11, "carpower"));
                handler.postDelayed(this, POLL_MS);
            }
        };
        private final Runnable guess = new Runnable() {
            @Override
            public void run() {
                sink.engineRunning(GUESS, false);
            }
        };
        // The vendor broadcast has no permission, so any app could fake a status frame. It can only fool the
        // confirmation: what is sent still depends on cfg_canbus, which the send gate checks.
        private final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                byte[] sync = intent.getByteArrayExtra("syncdata");
                byte[] data = OtherUnits.microntekData(sync);
                if (data != null) sink.frame(sync[0] & 0xFF, data);
            }
        };

        private Microntek(Context c, Sink sink, boolean watch, String type) {
            context = c.getApplicationContext();
            this.type = type;
            this.sink = sink;
            this.watch = watch;
        }

        /** carpower (getStringState): power_on at ignition on; power_off, acc_off or sleep as it goes off. */
        private void onPower(String state) {
            if (state == null || state.equals(power)) return;
            power = state;
            if (state.equals("power_on")) {
                sink.ignition(true);
                // Microntek gives no engine signal: guess, if the user asked for it.
                handler.removeCallbacks(guess);
                if (Prefs.engineGuess(context)) handler.postDelayed(guess, GUESS_MS);
            } else if (state.equals("power_off") || state.equals("acc_off") || state.equals("sleep")) {
                handler.removeCallbacks(guess);
                sink.ignition(false);
            }
        }

        static OtherLink open(Context c, Sink sink, boolean watch) {
            // A refused CAN box type is not started: CanBox asks the send gate and closes it again.
            Microntek m = new Microntek(c, sink, watch, carParameter("cfg_canbus="));
            if (SendGate.carRefusal(Detection.Platform.MICRONTEK, m.type) != null) return m;
            if (watch) m.handler.post(m.poll);
            else m.context.registerReceiver(m.receiver, new IntentFilter(SYNC));
            return m;
        }

        /**
         * CarManager.getParameters without linking the class, which Android 10 hides: the carservice binder,
         * transaction 18, descriptor android.microntek.ICarService. Null on any failure.
         */
        private static String carParameter(String key) {
            return carCall(18, key);
        }

        /** A carservice call that takes a key and returns a string: 18 getParameters, 11 getStringState. */
        private static String carCall(int transaction, String key) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                Method get = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
                IBinder car = (IBinder) get.invoke(null, "carservice");
                if (car == null) return null;
                data.writeInterfaceToken("android.microntek.ICarService");
                data.writeString(key);
                if (!car.transact(transaction, data, reply, 0)) return null;
                reply.readException();
                return reply.readString();
            } catch (Throwable e) {
                return null;
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        @Override
        int write(int cmd, byte[] data, String car) {
            if (watch) return -1;
            byte[] frame = OtherUnits.fdFrame(cmd, OtherUnits.microntekData(cmd, data));
            context.sendBroadcast(new Intent(SEND).putExtra(SEND_KEY, frame));
            return 0;
        }

        @Override
        void close() {
            handler.removeCallbacksAndMessages(null);
            if (watch) return;
            try {
                context.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
        }

        @Override
        boolean engineSignal() {
            return Prefs.engineGuess(context);
        }

        @Override
        String name() {
            return "Microntek";
        }

        @Override
        Detection.Platform platform() {
            return Detection.Platform.MICRONTEK;
        }

        @Override
        String car() {
            return type;
        }

        /** The CAN box type may have been changed in the vendor settings since the open. */
        @Override
        void refreshCar() {
            type = carParameter("cfg_canbus=");
        }

    }

    /**
     * FYT / SYU: the com.syu.ms toolkit binder, module 7 (canbus). It takes command codes of the configured car's
     * handler, not raw frames, and publishes decoded values, so only the PSA Raise codes are mapped.
     */
    static final class Fyt extends OtherLink {
        static final String PACKAGE = "com.syu.ms";
        private static final String TOOLKIT = "com.syu.ipc.IRemoteToolkit";
        private static final String MODULE = "com.syu.ipc.IRemoteModule";
        private static final String CALLBACK = "com.syu.ipc.IModuleCallback";
        private static final int MAIN = 0;
        private static final int CANBUS = 7;
        /** Main module: ACC, 1 on and 0 off. */
        private static final int ACC = 50;
        /** Canbus module: engine speed, from box frame 0x7D sub 0x0A on PSA Raise. */
        private static final int RPM = 1032;
        private static final int SET_STOP_START = 18;
        private static final int CANBUS_ID = 1000;
        // The version line is the same for every link this process opens.
        private static boolean versionLogged;
        private final Context context;
        private final String version;
        /** Update code of the stop-start bit for this com.syu.ms build, or -1 when unknown. */
        private final int statusCode;
        private final Sink sink;
        private volatile IBinder module;
        private volatile int canbusId = -1;
        // Last status bit published under statusCode, -1 before the first.
        private volatile int status = -1;
        private volatile boolean closed;
        private final boolean watch;
        private volatile IBinder main;
        private final Handler handler = new Handler(Looper.getMainLooper());
        // Main thread. stopped: rpm 0, or no rpm yet since the ignition on.
        private boolean running, stopped;
        private int lastRpm = -1;
        // 1 on, 0 off, -1 not reported yet. Main thread.
        private int acc = -1;
        private final Runnable guess = new Runnable() {
            @Override
            public void run() {
                if (!Prefs.fytRpmWorks(context)) sink.engineRunning(GUESS, false);
            }
        };

        private final Binder mainCallback = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(CALLBACK);
                if (closed) return true;
                int update = data.readInt();
                int[] ints = data.createIntArray();
                if (update != ACC || ints == null || ints.length == 0) return true;
                final boolean on = ints[0] == 1;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        handler.removeCallbacks(guess);
                        boolean wasOff = acc == 0;
                        acc = on ? 1 : 0;
                        if (!on) {
                            running = false;
                            // The next ignition on decides; an rpm from before this off must not count for it.
                            stopped = false;
                            lastRpm = -1;
                            sink.ignition(false);
                            return;
                        }
                        boolean rpmWorks = Prefs.fytRpmWorks(context);
                        // ACC first heard here may be a restart mid-drive, its rpm still to come. Only without rpm
                        // seen before could a guess have switched too early, and then the first rpm must re-arm.
                        if (lastRpm <= 0 && (wasOff || !rpmWorks)) stopped = true;
                        if (!rpmWorks && Prefs.engineGuess(context)) {
                            handler.postDelayed(guess, GUESS_MS);
                        }
                        sink.ignition(true);
                        // The engine speed can arrive before the ACC flag: the new cycle has not heard it yet.
                        if (lastRpm > 0) {
                            running = true;
                            sink.engineRunning("rpm " + lastRpm, false);
                        }
                    }
                });
                return true;
            }
        };

        private final Binder callback = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(CALLBACK);
                if (closed) return true;
                int update = data.readInt();
                int[] ints = data.createIntArray();
                if (ints == null || ints.length == 0) return true;
                if (update == CANBUS_ID) {
                    if (canbusId != ints[0]) sink.log("beta: FYT canbus id " + (ints[0] & 0xFFFF) + " (" + ints[0] + ")");
                    canbusId = ints[0];
                } else if (update == RPM) {
                    final int rpm = ints[0];
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            onRpm(rpm);
                        }
                    });
                } else if (update == statusCode && statusCode >= 0) {
                    status = ints[0] & 1;
                    // On another car the status is not ours: it must not show the link as working.
                    if (SendGate.unsupported(Detection.Platform.FYT, car()) != null) return true;
                    sink.frame(Protocol.STATUS_SETTINGS, OtherUnits.fytStatusFrame(status));
                }
                return true;
            }
        };

        private final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder toolkit) {
                // A restarted service may be set up for another car: identify it again before any send.
                forget();
                if (closed) return;
                try {
                    module = remoteModule(toolkit, CANBUS);
                    if (module == null) {
                        sink.log("beta: FYT toolkit has no canbus module");
                        return;
                    }
                    register(module, callback, CANBUS_ID, true);
                    if (watch) {
                        register(module, callback, RPM, true);
                        main = remoteModule(toolkit, MAIN);
                        if (main != null) register(main, mainCallback, ACC, true);
                        else sink.log("beta: FYT toolkit has no main module, ignition not followed");
                    } else if (statusCode >= 0) {
                        register(module, callback, statusCode, true);
                    }
                    sink.log("beta: FYT canbus module connected");
                    if (!versionLogged) {
                        sink.log("beta: FYT com.syu.ms " + version + (statusCode < 0
                                ? ": status code unknown, the switch cannot be confirmed" : ", status code " + statusCode));
                    }
                    versionLogged = true;
                } catch (Throwable e) {
                    sink.log("beta: FYT toolkit failed: " + e);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                forget();
                sink.log("beta: FYT toolkit disconnected");
            }
        };

        /** IRemoteToolkit.getRemoteModule, transaction 1. */
        private static IBinder remoteModule(IBinder toolkit, int code) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(TOOLKIT);
                data.writeInt(code);
                toolkit.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0);
                reply.readException();
                return reply.readStrongBinder();
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        /** Main thread. The engine counts as running on the first rpm above 0 after a stop. */
        private void onRpm(int rpm) {
            if (closed || rpm < 0) return;
            lastRpm = rpm;
            if (rpm == 0) {
                running = false;
                // With the ignition off, the next ignition on decides.
                stopped = acc != 0;
                return;
            }
            if (!Prefs.fytRpmWorks(context)) Prefs.setFytRpmWorks(context);
            handler.removeCallbacks(guess);
            if (running) return;
            running = true;
            boolean afterStop = stopped;
            stopped = false;
            sink.engineRunning("rpm " + rpm, afterStop);
        }

        private void forget() {
            // The first rpm after a reconnect is the engine as it is, not a start: it must not undo a switch.
            running = false;
            stopped = false;
            lastRpm = -1;
            acc = -1;
            module = null;
            main = null;
            canbusId = -1;
            status = -1;
        }

        private Fyt(Context c, Sink sink, boolean watch) {
            context = c.getApplicationContext();
            this.sink = sink;
            this.watch = watch;
            version = versionName(c, PACKAGE);
            statusCode = OtherUnits.fytStatusCode(version);
        }

        static OtherLink open(Context c, Sink sink, boolean watch) {
            Fyt f = new Fyt(c, sink, watch);
            Intent i = new Intent("com.syu.ms.toolkit").setComponent(new ComponentName(PACKAGE, "app.ToolkitService"));
            try {
                if (f.context.bindService(i, f.connection, Context.BIND_AUTO_CREATE)) return f;
                f.context.unbindService(f.connection);
            } catch (Throwable e) {
                sink.log("beta: FYT toolkit refused: " + e);
                return null;
            }
            sink.log("beta: FYT toolkit refused the binding");
            return null;
        }

        /**
         * Registers (transaction 3) or unregisters (4) the callback for an update code. On register, the last
         * argument 1 makes the service send the current value at once.
         */
        private static void register(IBinder m, Binder cb, int update, boolean on) throws RemoteException {
            if (m == null) return;
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(MODULE);
                data.writeStrongBinder(cb);
                data.writeInt(update);
                if (on) data.writeInt(1);
                m.transact(IBinder.FIRST_CALL_TRANSACTION + (on ? 2 : 3), data, null, IBinder.FLAG_ONEWAY);
            } finally {
                data.recycle();
            }
        }

        @Override
        int write(int cmd, byte[] data, String car) {
            if (watch) return -1;
            IBinder m = module;
            // Until the service reports the car, its handler does not exist and drops every command silently: the
            // send gate waits for the canbus id.
            if (m == null) return -2;
            // A reconnect since the gate asked may have brought another car, or none yet.
            String now = car();
            if (now == null) return -2;
            if (!now.equals(car)) return -1;
            try {
                if (cmd == Protocol.CMD_QUERY && data.length > 0 && data[0] == Protocol.STATUS_SETTINGS) {
                    // No command asks for frame 0x38 again; answer with the last value the service published.
                    int s = status;
                    if (s < 0) return -1;
                    sink.frame(Protocol.STATUS_SETTINGS, OtherUnits.fytStatusFrame(s));
                    return 0;
                }
                if (cmd != Protocol.CMD_SET || data.length != 2 || data[0] != 13) return -1;
                Parcel p = Parcel.obtain();
                try {
                    p.writeInterfaceToken(MODULE);
                    p.writeInt(SET_STOP_START);
                    p.writeIntArray(new int[]{data[1] & 0xFF});
                    p.writeFloatArray(null);
                    p.writeStringArray(null);
                    m.transact(IBinder.FIRST_CALL_TRANSACTION, p, null, IBinder.FLAG_ONEWAY);
                } finally {
                    p.recycle();
                }
                return 0;
            } catch (Throwable e) {
                sink.log("beta: FYT write failed: " + e);
                return -1;
            }
        }

        @Override
        void close() {
            closed = true;
            handler.removeCallbacksAndMessages(null);
            try {
                IBinder m = module;
                register(m, callback, CANBUS_ID, false);
                if (watch) register(m, callback, RPM, false);
                else if (statusCode >= 0) register(m, callback, statusCode, false);
                register(main, mainCallback, ACC, false);
            } catch (Throwable ignored) {
            }
            forget();
            try {
                context.unbindService(connection);
            } catch (Throwable ignored) {
            }
        }

        @Override
        boolean ready() {
            return module != null && canbusId >= 0;
        }

        @Override
        String name() {
            return "FYT";
        }

        @Override
        Detection.Platform platform() {
            return Detection.Platform.FYT;
        }

        @Override
        String car() {
            int id = canbusId;
            return id < 0 ? null : String.valueOf(id);
        }

    }
}
