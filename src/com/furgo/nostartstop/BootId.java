package com.furgo.nostartstop;

/** Identifies the current boot of the head unit, so the boot service switches at most once per boot. */
final class BootId {
    private BootId() {
    }

    /**
     * bootCount is Settings.Global.BOOT_COUNT, or negative where it is missing (before Android 7). The fallback
     * is the boot time rounded to 10 s, so the small drift between the two clocks does not change it.
     */
    static String of(int bootCount, long wallMs, long elapsedMs) {
        if (bootCount >= 0) return "count " + bootCount;
        return "time " + Math.round((wallMs - elapsedMs) / 10_000.0);
    }

    /** For the log, for example "boot count 7, up 42 s". */
    static String facts(int bootCount, long elapsedMs) {
        return "boot count " + (bootCount >= 0 ? String.valueOf(bootCount) : "unknown") + ", " + up(elapsedMs);
    }

    static String up(long elapsedMs) {
        return "up " + elapsedMs / 1000 + " s";
    }
}
