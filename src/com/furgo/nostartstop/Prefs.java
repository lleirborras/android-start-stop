package com.furgo.nostartstop;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

final class Prefs {
    private static final String FILE = "prefs";
    private static final String APPLY_AT_BOOT = "apply_at_boot";
    private static final String TELEMETRY = "telemetry";
    private static final String INSTALL_ID = "install_id";

    static boolean applyAtBoot(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(APPLY_AT_BOOT, false);
    }

    static void setApplyAtBoot(Context c, boolean on) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(APPLY_AT_BOOT, on).apply();
    }

    static boolean telemetry(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(TELEMETRY, false);
    }

    static void setTelemetry(Context c, boolean on) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(TELEMETRY, on).apply();
    }

    static synchronized void clearInstallId(Context c) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(INSTALL_ID).apply();
    }

    /** Random id for this install, created the first time it is needed. It identifies nothing else. */
    static synchronized String installId(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String id = prefs.getString(INSTALL_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(INSTALL_ID, id).apply();
        }
        return id;
    }
}
