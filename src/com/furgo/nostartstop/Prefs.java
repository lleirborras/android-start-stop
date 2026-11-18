package com.furgo.nostartstop;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.UUID;

final class Prefs {
    private static final String FILE = "prefs";
    private static final String APPLY_AT_BOOT = "apply_at_boot";
    private static final String TELEMETRY = "telemetry";
    private static final String CAPTURE_FRAMES = "capture_frames";
    private static final String BOOT_SWITCH_BOOT = "boot_switch_boot";
    private static final String LAST_SWITCH_AT = "last_switch_at";
    private static final String LAST_SWITCH_AT_BOOT = "last_switch_at_boot";
    private static final String LAST_SWITCH_BOOT = "last_switch_boot";
    private static final String BOOT_SWITCH_FAILED = "boot_switch_failed";
    private static final String WAKE_BOOT = "wake_boot";
    private static final String WAKE_COUNT = "wake_count";
    private static final String IGNITION_OFF = "ignition_off";
    private static final String LAST_SWITCH_ELAPSED = "last_switch_elapsed";
    private static final LogBudget WAKE_LINES = new LogBudget(30);
    private static String currentBoot;
    private static final String INSTALL_ID = "install_id";

    static boolean applyAtBoot(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(APPLY_AT_BOOT, false);
    }

    static void setApplyAtBoot(Context c, boolean on) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(APPLY_AT_BOOT, on).apply();
    }

    /** Read once per process: a reboot always starts a new process. */
    static synchronized String currentBoot(Context c) {
        if (currentBoot == null) {
            int count = Build.VERSION.SDK_INT >= 24
                    ? Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1) : -1;
            currentBoot = BootId.of(count, System.currentTimeMillis(), SystemClock.elapsedRealtime());
        }
        return currentBoot;
    }

    /**
     * The current ignition cycle: this boot plus the number of the last wake within it. The automatic switch and its
     * guards are per cycle, because the head unit usually sleeps and wakes instead of rebooting.
     */
    static synchronized String currentCycle(Context c) {
        String boot = currentBoot(c);
        SharedPreferences p = prefs(c);
        return WakeRules.cycle(boot, boot.equals(p.getString(WAKE_BOOT, null)) ? p.getInt(WAKE_COUNT, 0) : 0);
    }

    /** Starts a new ignition cycle; event is {@link WakeRules#IGNITION_ON_CYCLE} or {@link WakeRules#CRANK_CYCLE}. */
    static synchronized void newWake(Context c, String source, int event) {
        String boot = currentBoot(c);
        SharedPreferences p = prefs(c);
        boolean sameBoot = boot.equals(p.getString(WAKE_BOOT, null));
        long now = SystemClock.elapsedRealtime();
        int n = WakeRules.nextWake(sameBoot, p.getInt(WAKE_COUNT, 0));
        p.edit().putString(WAKE_BOOT, boot).putInt(WAKE_COUNT, n)
                .putBoolean(IGNITION_OFF, WakeRules.ignitionOffAfter(ignitionOff(c), event))
                .commit();
        if (WAKE_LINES.take(now)) LogFile.append(c, "wake " + n + " (" + source + ")");
    }

    /** Written at once: the head unit kills the app a few seconds after ignition off. */
    static void ignitionEvent(Context c, int event) {
        prefs(c).edit().putBoolean(IGNITION_OFF, WakeRules.ignitionOffAfter(ignitionOff(c), event)).commit();
    }

    static boolean ignitionOff(Context c) {
        return prefs(c).getBoolean(IGNITION_OFF, false);
    }

    /** True once the automatic switch was sent during the given ignition cycle. */
    static boolean bootSwitchDone(Context c, String cycle) {
        return cycle.equals(prefs(c).getString(BOOT_SWITCH_BOOT, null));
    }

    /** Written to disk before the automatic switch is sent, so even a crash right after cannot lead to a second send. */
    static void markBootSwitch(Context c, String cycle) {
        prefs(c).edit().putString(BOOT_SWITCH_BOOT, cycle).commit();
    }

    static void recordSwitch(Context c, boolean atBoot) {
        recordSwitch(c, atBoot, currentCycle(c));
    }

    /**
     * Records a switch command that was sent, by a tap or automatically. Written at once: the boot service reads it.
     * Clears the ignition off flag: stop-start is no longer in the state the car set at its last start.
     */
    static void recordSwitch(Context c, boolean atBoot, String cycle) {
        prefs(c).edit()
                .putLong(LAST_SWITCH_AT, System.currentTimeMillis())
                .putLong(LAST_SWITCH_ELAPSED, SystemClock.elapsedRealtime())
                .putBoolean(IGNITION_OFF,
                        WakeRules.ignitionOffAfter(ignitionOff(c), WakeRules.FIRED, WatchService.ignitionOffNow))
                .putBoolean(LAST_SWITCH_AT_BOOT, atBoot)
                .putString(LAST_SWITCH_BOOT, cycle)
                .remove(BOOT_SWITCH_FAILED)
                .commit();
    }

    static void setBootSwitchFailed(Context c, String cycle) {
        prefs(c).edit().putString(BOOT_SWITCH_FAILED, cycle).apply();
    }

    /** True when the automatic switch could not be sent during the current cycle, and no switch was sent since. */
    static boolean bootSwitchFailed(Context c) {
        return currentCycle(c).equals(prefs(c).getString(BOOT_SWITCH_FAILED, null));
    }

    static long lastSwitchAt(Context c) {
        return lastSwitchAt(c, currentCycle(c));
    }

    /** Wall-clock time of the last switch sent during the given cycle, or 0 if there was none. */
    static long lastSwitchAt(Context c, String cycle) {
        SharedPreferences p = prefs(c);
        return cycle.equals(p.getString(LAST_SWITCH_BOOT, null)) ? p.getLong(LAST_SWITCH_AT, 0) : 0;
    }

    /** elapsedRealtime of the last switch sent during the current cycle, or -1 if there was none. */
    static long lastSwitchElapsed(Context c) {
        SharedPreferences p = prefs(c);
        return currentCycle(c).equals(p.getString(LAST_SWITCH_BOOT, null)) ? p.getLong(LAST_SWITCH_ELAPSED, -1) : -1;
    }

    static boolean lastSwitchAtBoot(Context c) {
        return prefs(c).getBoolean(LAST_SWITCH_AT_BOOT, false);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Off by default: unknown CAN frames may hold vehicle details, and the log can be shared. */
    static boolean captureFrames(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(CAPTURE_FRAMES, false);
    }

    static void setCaptureFrames(Context c, boolean on) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(CAPTURE_FRAMES, on).apply();
    }

    /** False until the user has answered the first-launch question with yes. */
    static boolean telemetry(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(TELEMETRY, false);
    }

    /** True once the user has chosen, by the first-launch question or the switch. */
    static boolean telemetryAnswered(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).contains(TELEMETRY);
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

    private static final String AUTO_UPDATE = "auto_update";
    private static final String LATEST_TAG = "latest_tag";
    private static final String UPDATE_CHECKED_AT = "update_checked_at";
    private static final String NOTIFIED_TAG = "notified_tag";
    private static final String UPDATE_FAILURE = "update_failure";

    /** On by default: the check only asks GitHub for the latest release and never installs anything by itself. */
    static boolean autoUpdate(Context c) {
        return prefs(c).getBoolean(AUTO_UPDATE, true);
    }

    static void setAutoUpdate(Context c, boolean on) {
        prefs(c).edit().putBoolean(AUTO_UPDATE, on).apply();
    }

    /** The newest release tag seen by any check. Untrusted text: validate before use. */
    static String latestTag(Context c) {
        return prefs(c).getString(LATEST_TAG, null);
    }

    /** Wall-clock time of the last successful check, or 0. */
    static long updateCheckedAt(Context c) {
        return prefs(c).getLong(UPDATE_CHECKED_AT, 0);
    }

    static void setUpdateChecked(Context c, String tag, long at) {
        prefs(c).edit().putString(LATEST_TAG, tag).putLong(UPDATE_CHECKED_AT, at).remove(UPDATE_FAILURE).apply();
    }

    /** Keeps the stored failure, so the same reason is still logged only once. */
    static void setUpdateCheckedAt(Context c, long at) {
        prefs(c).edit().putLong(UPDATE_CHECKED_AT, at).apply();
    }

    static String notifiedTag(Context c) {
        return prefs(c).getString(NOTIFIED_TAG, null);
    }

    static void setNotifiedTag(Context c, String tag) {
        prefs(c).edit().putString(NOTIFIED_TAG, tag).apply();
    }

    /** Reason of the last failed background check, so the same failure is logged once until a check succeeds. */
    static String updateFailure(Context c) {
        return prefs(c).getString(UPDATE_FAILURE, null);
    }

    static void setUpdateFailure(Context c, String reason) {
        prefs(c).edit().putString(UPDATE_FAILURE, reason).apply();
    }
}
