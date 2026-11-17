package com.furgo.nostartstop;

/** Compares release tags like "v0.4.0" with installed version names like "0.3.0". */
final class Version {
    /** False when either side is not X.Y.Z (a local "dev" build never counts as older). */
    static boolean isNewer(String tag, String installed) {
        int[] a = parse(tag);
        int[] b = parse(installed);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    private static int[] parse(String s) {
        if (s == null) return null;
        if (s.startsWith("v")) s = s.substring(1);
        String[] parts = s.split("\\.", -1);
        if (parts.length != 3) return null;
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 6) return null;
            for (int j = 0; j < parts[i].length(); j++) {
                if (!Character.isDigit(parts[i].charAt(j))) return null;
            }
            out[i] = Integer.parseInt(parts[i]);
        }
        return out;
    }
}
