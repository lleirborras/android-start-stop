package com.furgo.nostartstop;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
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
    }

    /** Returns a negative value when nothing was sent. */
    abstract int write(int cmd, byte[] data);

    abstract void close();

    abstract String name();

    /** The link for this head unit, or null after logging why there is none. */
    static OtherLink open(Context c, Sink sink) {
        if (installed(c, Microntek.PACKAGE)) return Microntek.open(c, sink);
        if (installed(c, Fyt.PACKAGE)) return Fyt.open(c, sink);
        if (!noServiceLogged) sink.log("beta: no FYT or Microntek CAN service on this head unit");
        noServiceLogged = true;
        return null;
    }

    // Open is retried every few seconds at boot; the reason is the same each time.
    private static boolean noServiceLogged;

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Microntek MTCD/MTCE (HCT build): raw Raise frames by broadcast, status back by broadcast. */
    static final class Microntek extends OtherLink {
        static final String PACKAGE = "android.microntek.canbus";
        private static final String SEND = "com.microntek.canbus.send";
        private static final String SEND_KEY = "com.microntek.sendkey";
        private static final String SYNC = "com.microntek.sync";
        private final Context context;
        private final Sink sink;
        // The vendor broadcast has no permission, so any app could fake a status frame. It can only fool the
        // confirmation: what is sent still depends on cfg_canbus.
        private final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                byte[] sync = intent.getByteArrayExtra("syncdata");
                byte[] data = OtherUnits.microntekData(sync);
                if (data != null) sink.frame(sync[0] & 0xFF, data);
            }
        };

        private Microntek(Context c, Sink sink) {
            context = c.getApplicationContext();
            this.sink = sink;
        }

        static OtherLink open(Context c, Sink sink) {
            String type = carParameter("cfg_canbus=");
            String refusal = OtherUnits.microntekRefusal(type);
            if (refusal != null) {
                sink.log("beta: Microntek " + refusal + ", nothing will be sent");
                return null;
            }
            Microntek m = new Microntek(c, sink);
            m.context.registerReceiver(m.receiver, new IntentFilter(SYNC));
            return m;
        }

        /**
         * CarManager.getParameters without linking the class, which Android 10 hides: the carservice binder,
         * transaction 18, descriptor android.microntek.ICarService. Null on any failure.
         */
        private static String carParameter(String key) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                Method get = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
                IBinder car = (IBinder) get.invoke(null, "carservice");
                if (car == null) return null;
                data.writeInterfaceToken("android.microntek.ICarService");
                data.writeString(key);
                if (!car.transact(18, data, reply, 0)) return null;
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
        int write(int cmd, byte[] data) {
            byte[] frame = OtherUnits.fdFrame(cmd, OtherUnits.microntekData(cmd, data));
            context.sendBroadcast(new Intent(SEND).putExtra(SEND_KEY, frame));
            return 0;
        }

        @Override
        void close() {
            try {
                context.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
        }

        @Override
        String name() {
            return "Microntek";
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
        private static final int CANBUS = 7;
        private static final int SET_STOP_START = 18;
        private static final int STATUS = 168;
        private static final int CANBUS_ID = 1000;
        private final Context context;
        private final Sink sink;
        private volatile IBinder module;
        private volatile int canbusId = -1;
        // Last status bit published by update code 168, -1 before the first.
        private volatile int status = -1;
        private volatile boolean closed;
        private volatile boolean refusalLogged;

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
                } else if (update == STATUS) {
                    status = ints[0] & 1;
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
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(TOOLKIT);
                    data.writeInt(CANBUS);
                    toolkit.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0);
                    reply.readException();
                    module = reply.readStrongBinder();
                    if (module == null) {
                        sink.log("beta: FYT toolkit has no canbus module");
                        return;
                    }
                    register(CANBUS_ID, true);
                    register(STATUS, true);
                    sink.log("beta: FYT canbus module connected");
                } catch (Throwable e) {
                    sink.log("beta: FYT toolkit failed: " + e);
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                forget();
                sink.log("beta: FYT toolkit disconnected");
            }
        };

        private void forget() {
            module = null;
            canbusId = -1;
            status = -1;
            refusalLogged = false;
        }

        private Fyt(Context c, Sink sink) {
            context = c.getApplicationContext();
            this.sink = sink;
        }

        static OtherLink open(Context c, Sink sink) {
            Fyt f = new Fyt(c, sink);
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
        private void register(int update, boolean on) throws Exception {
            IBinder m = module;
            if (m == null) return;
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(MODULE);
                data.writeStrongBinder(callback);
                data.writeInt(update);
                if (on) data.writeInt(1);
                m.transact(IBinder.FIRST_CALL_TRANSACTION + (on ? 2 : 3), data, null, IBinder.FLAG_ONEWAY);
            } finally {
                data.recycle();
            }
        }

        @Override
        int write(int cmd, byte[] data) {
            IBinder m = module;
            if (m == null) return -1;
            try {
                if (cmd == Protocol.CMD_QUERY && data.length > 0 && data[0] == Protocol.STATUS_SETTINGS) {
                    // No command asks for frame 0x38 again; answer with the last value the service published.
                    int s = status;
                    if (s < 0) return -1;
                    sink.frame(Protocol.STATUS_SETTINGS, OtherUnits.fytStatusFrame(s));
                    return 0;
                }
                if (cmd != Protocol.CMD_SET || data.length != 2 || data[0] != 13) return -1;
                String refusal = OtherUnits.fytRefusal(canbusId);
                if (refusal != null) {
                    if (!refusalLogged) sink.log("beta: FYT " + refusal + ", nothing sent");
                    refusalLogged = true;
                    return -1;
                }
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
            try {
                register(CANBUS_ID, false);
                register(STATUS, false);
            } catch (Throwable ignored) {
            }
            forget();
            try {
                context.unbindService(connection);
            } catch (Throwable ignored) {
            }
        }

        @Override
        String name() {
            return "FYT";
        }
    }
}
