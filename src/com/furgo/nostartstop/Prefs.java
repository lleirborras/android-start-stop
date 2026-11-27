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
    private static final String WITHOUT_ADB = "without_adb";
    private static final String OTHER_UNITS = "other_units";
    private static final String OTHER_CARS = "other_cars";
    private static final String SETUP_CHOICE = "setup_choice";
    private static final String SETUP_DETECTION = "setup_detection";
    private static final String ENGINE_GUESS = "engine_guess";
    private static final String FYT_RPM_WORKS = "fyt_rpm_works";
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
    // The fields of AssumedState.
    private static final String ASSUMED_CYCLE = "assumed_cycle";
    private static final String ASSUMED_ENDED = "assumed_ended_flag";
    private static final String ASSUMED_WAKE = "assumed_wake";
    private static final String ASSUMED_SOURCE = "assumed_source";
    private static final String ASSUMED_START_AT = "assumed_start_at";
    private static final String ASSUMED_ENGINE_START = "assumed_engine_start";
    private static final String ASSUMED_SWITCHES = "assumed_switches";
    private static final String ASSUMED_REPORTS = "assumed_reports";
    private static final String ASSUMED_BASE = "assumed_base";
    private static final LogBudget ASSUMED_LINES = new LogBudget(60);
    private static final LogBudget REPORT_LINES = new LogBudget(50);
    /** Set by the main screen while it is shown, to redraw when the assumed state changes. Main thread only. */
    static Runnable onAssumedChanged;
    private static final String BOOT_ID = "boot_id";
    private static final String BOOT_ID_SEEN = "boot_id_seen";
    private static final long BOOT_SEEN_EVERY_MS = 60_000;
    private static String currentBoot;
    private static boolean bootStored;
    private static long bootSeenAt;
    private static final String INSTALL_ID = "install_id";

    static boolean applyAtBoot(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(APPLY_AT_BOOT, false);
    }

    static void setApplyAtBoot(Context c, boolean on) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(APPLY_AT_BOOT, on).apply();
    }

    /**
     * Decided once per process: a reboot always starts a new process. Without a boot count the id is stored, so a
     * later process of the same boot gets the same one, see {@link BootId#keep}.
     */
    static synchronized String currentBoot(Context c) {
        long elapsed = SystemClock.elapsedRealtime();
        if (currentBoot == null) {
            int count = Build.VERSION.SDK_INT >= 24
                    ? Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1) : -1;
            currentBoot = BootId.of(count, System.currentTimeMillis(), elapsed);
            bootStored = count < 0;
            if (bootStored) {
                SharedPreferences p = prefs(c);
                currentBoot = BootId.keep(p.getString(BOOT_ID, null), p.getLong(BOOT_ID_SEEN, 0), currentBoot, elapsed);
                bootSeenAt = elapsed - BOOT_SEEN_EVERY_MS;
            }
        }
        // The later the stored uptime, the fewer reboots can be taken for the same boot.
        if (bootStored && elapsed - bootSeenAt >= BOOT_SEEN_EVERY_MS) {
            bootSeenAt = elapsed;
            prefs(c).edit().putString(BOOT_ID, currentBoot).putLong(BOOT_ID_SEEN, elapsed).apply();
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
    static void newWake(Context c, String source, int event) {
        long now = SystemClock.elapsedRealtime();
        int n;
        String change;
        synchronized (Prefs.class) {
            String boot = currentBoot(c);
            SharedPreferences p = prefs(c);
            boolean sameBoot = boot.equals(p.getString(WAKE_BOOT, null));
            n = WakeRules.nextWake(sameBoot, p.getInt(WAKE_COUNT, 0));
            String cycle = WakeRules.cycle(boot, n);
            AssumedState m = loadAssumed(p);
            m.newCycle(cycle, n, source, event == WakeRules.CRANK_CYCLE, now);
            saveAssumed(p.edit().putString(WAKE_BOOT, boot).putInt(WAKE_COUNT, n)
                    .putBoolean(IGNITION_OFF, WakeRules.ignitionOffAfter(ignitionOff(c), event)), m)
                    .commit();
            change = AssumedState.change(m.state(cycle, applyAtBoot(c)), source);
        }
        if (WAKE_LINES.take(now)) LogFile.append(c, "wake " + n + " (" + source + ")");
        assumedChanged(c, change);
    }

    static synchronized int assumedState(Context c) {
        return loadAssumed(prefs(c)).state(currentCycle(c), applyAtBoot(c));
    }

    /**
     * An engine start that stays in the current cycle: the car turned stop-start on again. switchedDuringCrank: a
     * switch went out during that crank, so it counts for this engine start.
     */
    static void assumedEngineStart(Context c, boolean switchedDuringCrank) {
        String change;
        synchronized (Prefs.class) {
            SharedPreferences p = prefs(c);
            String cycle = currentCycle(c);
            AssumedState m = loadAssumed(p);
            m.sameCycleEngineStart(cycle, currentWake(c), switchedDuringCrank, SystemClock.elapsedRealtime());
            saveAssumed(p.edit(), m).commit();
            change = AssumedState.change(m.state(cycle, applyAtBoot(c)), m.source);
        }
        assumedChanged(c, change);
    }

    /**
     * The state is unknown until the next cycle starts: ignition off, the automatic switch turned off, or the watch
     * service unable to follow the ignition. why is logged.
     */
    static void assumedEnd(Context c, String why) {
        synchronized (Prefs.class) {
            SharedPreferences p = prefs(c);
            AssumedState m = loadAssumed(p);
            if (!m.end(currentCycle(c), true)) return;
            saveAssumed(p.edit(), m).commit();
        }
        assumedChanged(c, AssumedState.change(AssumedState.UNKNOWN, why));
    }

    static final int REPORT_REFUSED = 0, REPORT_LOGGED = 1, REPORT_NOT_LOGGED = 2;

    /**
     * The user says the shown state is wrong: logs what led to it and flips it. Sends nothing to the car. Refused
     * while the state is unknown; REPORT_NOT_LOGGED when the daily limit for report lines is reached.
     */
    static int reportAssumedWrong(Context c, int centivolts, long centivoltsAt, int powerState, long powerStateAt) {
        long now = SystemClock.elapsedRealtime();
        String line;
        String change;
        synchronized (Prefs.class) {
            SharedPreferences p = prefs(c);
            String cycle = currentCycle(c);
            boolean enabled = applyAtBoot(c);
            AssumedState m = loadAssumed(p);
            if (m.state(cycle, enabled) == AssumedState.UNKNOWN) return REPORT_REFUSED;
            long switchAt = cycle.equals(p.getString(LAST_SWITCH_BOOT, null)) ? p.getLong(LAST_SWITCH_ELAPSED, -1) : -1;
            line = m.reportLine(cycle, enabled, now, switchAt, p.getBoolean(LAST_SWITCH_AT_BOOT, false),
                    centivolts, centivoltsAt, powerState, powerStateAt);
            m.report(cycle, enabled);
            saveAssumed(p.edit(), m).commit();
            change = AssumedState.change(m.state(cycle, enabled), "reported wrong");
        }
        boolean logged = REPORT_LINES.take(now);
        if (logged) LogFile.append(c, line);
        assumedChanged(c, change);
        return logged ? REPORT_LOGGED : REPORT_NOT_LOGGED;
    }

    /** True while the watch service follows the ignition, so a state set by hand ends at the next ignition off. */
    static boolean canSetAssumed(Context c) {
        return applyAtBoot(c) && WatchService.following;
    }

    /** The user sets the state from the instrument cluster while it is unknown. Sends nothing to the car. */
    static boolean setAssumedByHand(Context c, int state) {
        synchronized (Prefs.class) {
            SharedPreferences p = prefs(c);
            AssumedState m = loadAssumed(p);
            if (!m.setByHand(currentCycle(c), canSetAssumed(c), currentWake(c), state, SystemClock.elapsedRealtime())) {
                return false;
            }
            saveAssumed(p.edit(), m).commit();
        }
        assumedChanged(c, AssumedState.change(state, "set by hand"));
        return true;
    }

    /** Logs one assumed-state event and redraws the screen. Never called with the Prefs lock held. */
    private static void assumedChanged(Context c, String change) {
        if (ASSUMED_LINES.take(SystemClock.elapsedRealtime())) LogFile.append(c, change);
        if (onAssumedChanged != null) onAssumedChanged.run();
    }

    private static int currentWake(Context c) {
        SharedPreferences p = prefs(c);
        return currentBoot(c).equals(p.getString(WAKE_BOOT, null)) ? p.getInt(WAKE_COUNT, 0) : 0;
    }

    private static AssumedState loadAssumed(SharedPreferences p) {
        AssumedState m = new AssumedState();
        m.cycle = p.getString(ASSUMED_CYCLE, null);
        m.ended = p.getBoolean(ASSUMED_ENDED, false);
        m.wake = p.getInt(ASSUMED_WAKE, -1);
        m.source = p.getString(ASSUMED_SOURCE, null);
        m.startAt = p.getLong(ASSUMED_START_AT, 0);
        m.engineStart = p.getBoolean(ASSUMED_ENGINE_START, false);
        m.switches = p.getInt(ASSUMED_SWITCHES, 0);
        m.reports = p.getInt(ASSUMED_REPORTS, 0);
        m.base = p.getInt(ASSUMED_BASE, AssumedState.ON);
        return m;
    }

    private static SharedPreferences.Editor saveAssumed(SharedPreferences.Editor e, AssumedState m) {
        return e.putString(ASSUMED_CYCLE, m.cycle)
                .putBoolean(ASSUMED_ENDED, m.ended)
                .putInt(ASSUMED_WAKE, m.wake)
                .putString(ASSUMED_SOURCE, m.source)
                .putLong(ASSUMED_START_AT, m.startAt)
                .putBoolean(ASSUMED_ENGINE_START, m.engineStart)
                .putInt(ASSUMED_SWITCHES, m.switches)
                .putInt(ASSUMED_REPORTS, m.reports)
                .putInt(ASSUMED_BASE, m.base);
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
        String change;
        synchronized (Prefs.class) {
            SharedPreferences p = prefs(c);
            String current = currentCycle(c);
            boolean enabled = applyAtBoot(c);
            AssumedState m = loadAssumed(p);
            boolean counted = m.switchSent(cycle, current, enabled);
            saveAssumed(p.edit(), m)
                    .putLong(LAST_SWITCH_AT, System.currentTimeMillis())
                    .putLong(LAST_SWITCH_ELAPSED, SystemClock.elapsedRealtime())
                    .putBoolean(IGNITION_OFF,
                            WakeRules.ignitionOffAfter(ignitionOff(c), WakeRules.FIRED, WatchService.ignitionOffNow))
                    .putBoolean(LAST_SWITCH_AT_BOOT, atBoot)
                    .putString(LAST_SWITCH_BOOT, cycle)
                    .remove(BOOT_SWITCH_FAILED)
                    .commit();
            change = counted ? AssumedState.change(m.state(current, enabled),
                    atBoot ? "switch sent, automatic" : "switch sent, tap") : null;
        }
        if (change != null) assumedChanged(c, change);
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

    /** Beta, off by default: see {@link HiddenApi}. */
    static boolean withoutAdb(Context c) {
        return prefs(c).getBoolean(WITHOUT_ADB, false);
    }

    static void setWithoutAdb(Context c, boolean on) {
        prefs(c).edit().putBoolean(WITHOUT_ADB, on).apply();
    }

    /** Beta, off by default: FYT and Microntek head units, see {@link OtherLink}. */
    static boolean otherUnits(Context c) {
        return prefs(c).getBoolean(OTHER_UNITS, false);
    }

    static void setOtherUnits(Context c, boolean on) {
        prefs(c).edit().putBoolean(OTHER_UNITS, on).apply();
    }

    /** Beta, off by default: Topway car profiles other than PSA Raise, see {@link CarProfile#forBeta}. */
    static boolean otherCars(Context c) {
        return prefs(c).getBoolean(OTHER_CARS, false);
    }

    static void setOtherCars(Context c, boolean on) {
        prefs(c).edit().putBoolean(OTHER_CARS, on).apply();
    }

    /** The confirmed car, see {@link CarSetup}; null until the user confirms one, on every install and update. */
    static String setupChoice(Context c) {
        return prefs(c).getString(SETUP_CHOICE, null);
    }

    /** The detection the choice was confirmed against, see {@link CarSetup#detection}. */
    static String setupDetection(Context c) {
        return prefs(c).getString(SETUP_DETECTION, null);
    }

    static void setSetup(Context c, String choice, String detection) {
        prefs(c).edit().putString(SETUP_CHOICE, choice).putString(SETUP_DETECTION, detection).apply();
    }

    /**
     * Beta, off by default: on FYT and Microntek units without an engine speed, take the engine as running a while
     * after ignition on. A guess: a switch sent before the engine runs may be undone by the start.
     */
    static boolean engineGuess(Context c) {
        return prefs(c).getBoolean(ENGINE_GUESS, false);
    }

    static void setEngineGuess(Context c, boolean on) {
        prefs(c).edit().putBoolean(ENGINE_GUESS, on).apply();
    }

    /** The FYT head unit has reported an engine speed above 0 once, so it needs no guess. */
    static boolean fytRpmWorks(Context c) {
        return prefs(c).getBoolean(FYT_RPM_WORKS, false);
    }

    static void setFytRpmWorks(Context c) {
        prefs(c).edit().putBoolean(FYT_RPM_WORKS, true).apply();
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
    private static final String BETA_PROGRAM = "beta_program";
    private static final String LATEST_TAG = "latest_tag";
    private static final String UPDATE_CHECKED_AT = "update_checked_at";
    private static final String NOTIFIED_TAG = "notified_tag";
    private static final String UPDATE_FAILURE = "update_failure";
    private static final String PENDING_SESSION = "pending_session";

    /** On by default: the check only asks GitHub for the latest release and never installs anything by itself. */
    static boolean autoUpdate(Context c) {
        return prefs(c).getBoolean(AUTO_UPDATE, true);
    }

    static void setAutoUpdate(Context c, boolean on) {
        prefs(c).edit().putBoolean(AUTO_UPDATE, on).apply();
    }

    static boolean betaProgram(Context c) {
        return prefs(c).getBoolean(BETA_PROGRAM, false);
    }

    /** Also forgets the release found so far: it may be a beta, or a stable one older than the newest beta. */
    static void setBetaProgram(Context c, boolean on) {
        prefs(c).edit().putBoolean(BETA_PROGRAM, on).remove(LATEST_TAG).remove(UPDATE_CHECKED_AT).remove(NOTIFIED_TAG)
                .apply();
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

    /** Id of the install session this app committed and has no result for yet, or -1. */
    static int pendingSession(Context c) {
        return prefs(c).getInt(PENDING_SESSION, -1);
    }

    /** Written at once: the installer may answer after this process is gone. */
    static void setPendingSession(Context c, int session) {
        prefs(c).edit().putInt(PENDING_SESSION, session).commit();
    }
}
