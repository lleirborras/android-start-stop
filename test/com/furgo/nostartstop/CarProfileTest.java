package com.furgo.nostartstop;

import java.util.Arrays;

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
        beta();
        check("beta profiles", java.util.Arrays.toString(codes(CarProfile.betaProfiles())), "[96, 114, 111, 274, F91]");
        System.out.println("CarProfileTest passed");
    }

    /** The beta drives only profiles that take a value and report it back. */
    private static void beta() {
        check("PSA Raise keeps the default path", CarProfile.forBeta("961_0000_260130"), null);
        check("VW without readback is not driven", CarProfile.forBeta("11_0000_260403"), null);
        check("Jeep with opposite values is not driven", CarProfile.forBeta("880_0000_260911"), null);
        check("Hiworld PSA press is not driven", CarProfile.forBeta("964_0000_251031"), null);
        check("unknown is not driven", CarProfile.forBeta("41_0000_210812"), null);

        CarProfile vw = CarProfile.forBeta("111_0000_260327");
        check("Raise VW is driven", vw.code, "111");
        check("Raise VW off", Arrays.toString(vw.set(false)), "[17, 0]");
        check("Raise VW on", Arrays.toString(vw.set(true)), "[17, 1]");
        check("Raise VW query", vw.queryCmd() + " " + Arrays.toString(vw.query()), "144 [64, 16]");
        check("Raise VW on in frame 64", vw.stopStartOn(64, bytes(16, 0, 0x80)), true);
        check("Raise VW off in frame 64", vw.stopStartOn(64, bytes(16, 0xFF, 0x7F)), false);
        check("Raise VW other selector", vw.stopStartOn(64, bytes(0, 0, 0x80)), null);
        check("Raise VW other frame", vw.stopStartOn(65, bytes(16, 0, 0x80)), null);
        check("Raise VW short frame", vw.stopStartOn(64, bytes(16, 0)), null);

        CarProfile honda = CarProfile.forBeta("274_0000_241116");
        check("Honda on", honda.stopStartOn(105, bytes(0x08)), true);
        check("Honda off", honda.stopStartOn(105, bytes(0xF7)), false);
        check("Honda query", honda.queryCmd() + " " + Arrays.toString(honda.query()), "106 [5, 1, 105]");

        check("Haval Hiworld without a query is not driven", CarProfile.forBeta("F94_0000_260331"), null);

        check("Hiworld VW byte 6 bit 4", CarProfile.forBeta("114_0000_261027").stopStartOn(69, bytes(0, 0, 0, 0, 0, 0, 0x10)), true);
        check("Raise Haval byte 3 bit 5", CarProfile.forBeta("F91_0000_251031").stopStartOn(49, bytes(0, 0, 0, 0x20)), true);
        check("Simple Soft PSA byte 4 bit 6", CarProfile.forBeta("96_0000_261006").stopStartOn(56, bytes(0, 0, 0, 0, 0x40)), true);
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static String[] codes(CarProfile[] ps) {
        String[] out = new String[ps.length];
        for (int i = 0; i < ps.length; i++) out[i] = ps[i].code;
        return out;
    }

    private static void check(String name, Object got, Object want) {
        if (got == null ? want != null : !got.equals(want)) {
            System.err.println(name + ": expected " + want + " but got " + got);
            System.exit(1);
        }
    }
}
