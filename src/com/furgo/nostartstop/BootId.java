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
}
