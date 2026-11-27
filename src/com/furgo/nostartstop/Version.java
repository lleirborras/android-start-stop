package com.furgo.nostartstop;

/**
 * Compares release tags like "v0.4.0" or "v0.5.0-beta.212" with installed version names like "0.3.0". A beta comes
 * before the stable release of the same number, and betas of one number are ordered by their build number.
 */
final class Version {
    private static final String BETA = "-beta.";

    /** False when either side is not X.Y.Z or X.Y.Z-beta.N (a local "dev" build never counts as older). */
    static boolean isNewer(String tag, String installed) {
        long[] a = parse(tag);
        long[] b = parse(installed);
        if (a == null || b == null) return false;
        for (int i = 0; i < 4; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    static boolean valid(String s) {
        return parse(s) != null;
    }

    static boolean isBeta(String s) {
        long[] v = parse(s);
        return v != null && v[3] != Long.MAX_VALUE;
    }

    /** major, minor, patch, then the beta build number, or Long.MAX_VALUE for a stable release. */
    private static long[] parse(String s) {
        if (s == null) return null;
        if (s.startsWith("v")) s = s.substring(1);
        long beta = Long.MAX_VALUE;
        int at = s.indexOf(BETA);
        if (at >= 0) {
            String n = s.substring(at + BETA.length());
            if (!digits(n, 9)) return null;
            beta = Long.parseLong(n);
            s = s.substring(0, at);
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length != 3) return null;
        long[] out = new long[4];
        for (int i = 0; i < 3; i++) {
            if (!digits(parts[i], 6)) return null;
            out[i] = Long.parseLong(parts[i]);
        }
        out[3] = beta;
        return out;
    }

    private static boolean digits(String s, int max) {
        if (s.isEmpty() || s.length() > max) return false;
        for (int j = 0; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
