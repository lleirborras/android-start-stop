package com.furgo.nostartstop;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class VersionTest {
    public static void main(String[] args) {
        check("v0.4.0", "0.3.0", true);
        check("v0.3.1", "0.3.0", true);
        check("v1.0.0", "0.9.9", true);
        check("v0.10.0", "0.9.0", true);
        check("0.4.0", "0.3.0", true);

        check("v0.3.0", "0.3.0", false);
        check("v0.2.9", "0.3.0", false);
        check("v0.9.0", "0.10.0", false);
        check("v0.9.9", "1.0.0", false);

        // Anything that is not X.Y.Z must never trigger an update.
        check("v0.4.0", "dev", false);
        check("v0.4.0", "unknown", false);
        check("v0.4", "0.3.0", false);
        check("v0.4.0.1", "0.3.0", false);
        check("v0.4.0-beta", "0.3.0", false);
        check("latest", "0.3.0", false);
        check("", "0.3.0", false);
        check(null, "0.3.0", false);
        check("v0.4.0", null, false);

        System.out.println("VersionTest passed");
    }

    private static void check(String tag, String installed, boolean expected) {
        if (Version.isNewer(tag, installed) != expected) {
            throw new AssertionError("isNewer(" + tag + ", " + installed + ") should be " + expected);
        }
    }
}
