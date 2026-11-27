package com.furgo.nostartstop;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The feedback line of the stop-start card: the first of its sources that has something to say. */
final class Feedback {
    /** KEEP: the line keeps the colour it had. RESULT: the colour of the last result. */
    static final int KEEP = 0, MUTED = 1, WARN = 2, RESULT = 3;

    final String text;
    final int color;

    private Feedback(String text, int color) {
        this.text = text;
        this.color = color;
    }

    /**
     * note is null when there is none; result is empty when there is none; lastSwitchAt is the wall-clock time of the
     * last switch of this ignition cycle, or 0.
     */
    static Feedback of(String note, boolean autoSwitching, String result, boolean pending, boolean linkDown,
            boolean bootSwitchFailed, long lastSwitchAt, boolean lastSwitchAtBoot) {
        return of(note, autoSwitching, result, pending, linkDown, false, bootSwitchFailed, lastSwitchAt,
                lastSwitchAtBoot);
    }

    /** carUnsupported: the link works but the head unit is set up for a car or CAN box the app does not support. */
    static Feedback of(String note, boolean autoSwitching, String result, boolean pending, boolean linkDown,
            boolean carUnsupported, boolean bootSwitchFailed, long lastSwitchAt, boolean lastSwitchAtBoot) {
        if (note != null) {
            return new Feedback(note, WARN);
        } else if (autoSwitching) {
            return new Feedback("Switching…", MUTED);
        } else if (result.isEmpty() && pending) {
            return new Feedback("", KEEP);
        } else if (result.isEmpty() && carUnsupported) {
            return new Feedback("This car or CAN box is not supported.", MUTED);
        } else if (result.isEmpty() && linkDown) {
            return new Feedback("This head unit did not let the app reach the car.", MUTED);
        } else if (result.isEmpty() && bootSwitchFailed) {
            return new Feedback("Automatic switch failed. Tap to switch.", WARN);
        } else if (result.isEmpty() && lastSwitchAt != 0) {
            return new Feedback("Last switch: " + clock(lastSwitchAt) + (lastSwitchAtBoot ? ", automatic" : ""), MUTED);
        } else {
            return new Feedback(result, RESULT);
        }
    }

    /** Wall-clock milliseconds as "HH:mm" in the device's time zone. */
    static String clock(long wallMs) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(wallMs));
    }
}
