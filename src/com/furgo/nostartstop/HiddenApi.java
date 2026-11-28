package com.furgo.nostartstop;

import android.os.Build;

import java.lang.reflect.Method;

/** Beta: lets this app use the vendor class TWUtil where Android blocks it, without adb. */
final class HiddenApi {
    private static boolean done;

    /**
     * Returns null when the exemption is in place or not needed, otherwise why it failed. The reflection calls are
     * made through reflection themselves, so Android sees the framework as the caller. Seen working on Android 14
     * with targetSdkVersion 28; newer targets may lose it.
     */
    static synchronized String exempt() {
        if (done || Build.VERSION.SDK_INT < 28) return null;
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod =
                    Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> runtime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(runtime, "getRuntime", null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(runtime, "setHiddenApiExemptions",
                    new Class<?>[]{String[].class});
            setExemptions.invoke(getRuntime.invoke(null), new Object[]{new String[]{"Landroid/tw/"}});
            done = true;
            return null;
        } catch (Throwable e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return cause.toString();
        }
    }
}
