package com.furgo.nostartstop;

import android.content.Context;

final class Prefs {
    private static final String FILE = "prefs";
    private static final String APPLY_AT_BOOT = "apply_at_boot";

    static boolean applyAtBoot(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(APPLY_AT_BOOT, false);
    }

    static void setApplyAtBoot(Context c, boolean on) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(APPLY_AT_BOOT, on).apply();
    }
}
