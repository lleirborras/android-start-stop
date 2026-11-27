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
        check("v0.4.0-rc.1", "0.3.0", false);

        // A beta comes after the release before it and before its own stable release.
        check("v0.4.0-beta.7", "0.3.0", true);
        check("v0.4.0", "0.4.0-beta.7", true);
        check("v0.4.0-beta.7", "0.4.0", false);
        check("v0.4.0-beta.8", "0.4.0-beta.7", true);
        check("v0.4.0-beta.10", "0.4.0-beta.9", true);
        check("v0.4.0-beta.7", "0.4.0-beta.7", false);
        check("v0.3.1", "0.4.0-beta.7", false);
        check("latest", "0.3.0", false);
        check("", "0.3.0", false);
        check(null, "0.3.0", false);
        check("v0.4.0", null, false);

        label("v0.21.0-beta.3", "v0.21.0 beta 3");
        label("0.21.0-beta.3", "v0.21.0 beta 3");
        label("0.20.1", "v0.20.1");
        label("v0.20.1", "v0.20.1");
        label("dev-abc1234", "dev-abc1234");
        label("", "");

        System.out.println("VersionTest passed");
    }

    private static void label(String in, String expected) {
        if (!Version.label(in).equals(expected)) {
            throw new AssertionError("label(" + in + ") should be " + expected + " but is " + Version.label(in));
        }
    }

    private static void check(String tag, String installed, boolean expected) {
        if (Version.isNewer(tag, installed) != expected) {
            throw new AssertionError("isNewer(" + tag + ", " + installed + ") should be " + expected);
        }
    }
}
