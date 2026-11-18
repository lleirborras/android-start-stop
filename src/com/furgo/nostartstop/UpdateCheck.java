package com.furgo.nostartstop;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Rules for the background update check, kept free of Android classes so they can be tested on a plain JDK. */
final class UpdateCheck {
    static final long MIN_GAP_MS = 6 * 60 * 60 * 1000L;

    /** True when the last successful check is at least MIN_GAP_MS old, never happened, or lies in the future (clock reset). */
    static boolean due(long now, long lastCheckedAt) {
        if (lastCheckedAt <= 0) return true;
        long age = now - lastCheckedAt;
        return age < 0 || age >= MIN_GAP_MS;
    }

    /** Release tags are "vX.Y.Z"; anything else read from GitHub or prefs is not shown or used. */
    static boolean validTag(String tag) {
        return tag != null && tag.matches("v[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}");
    }

    /** The stored tag when it is valid and newer than the installed version, else null. */
    static String offerable(String storedTag, String installed) {
        return validTag(storedTag) && Version.isNewer(storedTag, installed) ? storedTag : null;
    }

    static boolean shouldNotify(String tag, String installed, String notifiedTag) {
        return offerable(tag, installed) != null && !tag.equals(notifiedTag);
    }

    /** A short, printable reason for a log line. */
    static String reason(Throwable e) {
        if (e instanceof UnknownHostException || e instanceof ConnectException || e instanceof NoRouteToHostException) {
            return "no internet";
        }
        if (e instanceof SocketTimeoutException) return "timed out";
        String m = e.getMessage();
        String s = e.getClass() == IOException.class && m != null ? m
                : e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length() && i < 80; i++) {
            char c = s.charAt(i);
            out.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return out.toString();
    }
}
