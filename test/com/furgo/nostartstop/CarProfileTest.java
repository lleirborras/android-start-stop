package com.furgo.nostartstop;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class CarProfileTest {
    public static void main(String[] args) {
        // The van's vendor app.
        check("van code", CarProfile.code("961_0000_260130"), "961");
        check("van profile", CarProfile.of("961_0000_260130") == CarProfile.PSA_RAISE, true);
        check("van tested", CarProfile.of("961_0000_260130").tested, true);
        check("van toggles", CarProfile.of("961_0000_260130").kind, CarProfile.TOGGLE);
        check("van line", CarProfile.describe("961_0000_260130"),
                "car app 961_0000_260130, profile 961 (Raise, PSA and Opel, tested)");

        // "96" and "961" are different profiles: the prefix must not match.
        check("96 is not 961", CarProfile.of("96_0000_261006").code, "96");
        check("96 untested", CarProfile.of("96_0000_261006").tested, false);
        check("F94 data", CarProfile.of("F94_0000_260331").off.length, 4);
        check("F94 off value", CarProfile.of("F94_0000_260331").off[1], (byte) 0);
        check("964 presses", CarProfile.of("964_0000_251031").kind, CarProfile.PRESS);

        check("Ford has none", CarProfile.of("41_0000_210812"), null);
        check("Ford line", CarProfile.describe("41_0000_210812"),
                "car app 41_0000_210812, profile 41 (no stop-start command known)");
        check("not installed", CarProfile.describe(null), "car app not found");
        check("no underscore", CarProfile.code("961"), "961");
        check("lower case refused", CarProfile.code("abc_1"), null);
        check("empty refused", CarProfile.code("_0000"), null);
        check("odd text cleaned", CarProfile.describe("x y\n"), "car app x?y?, profile unknown");
        System.out.println("CarProfileTest passed");
    }

    private static void check(String name, Object got, Object want) {
        if (got == null ? want != null : !got.equals(want)) {
            System.err.println(name + ": expected " + want + " but got " + got);
            System.exit(1);
        }
    }
}
