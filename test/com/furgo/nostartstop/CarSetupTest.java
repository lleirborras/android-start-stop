package com.furgo.nostartstop;

import java.util.Arrays;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class CarSetupTest {
    private static final int ASK = CarSetup.ASK, SEND = CarSetup.SEND, REFUSE = CarSetup.REFUSE;
    private static final String VAN = "topway:961", VW = "topway:111", FORD = "topway:41", NONE = "none";
    private static final boolean STABLE = false, BETA = true;

    public static void main(String[] args) {
        check("van detected", CarSetup.detection("961_0000_260130"), VAN);
        check("VW detected", CarSetup.detection("111_0000_260327"), VW);
        check("unknown code detected", CarSetup.detection("41_0000_210812"), FORD);
        check("no vendor app", CarSetup.detection(null), NONE);
        check("unreadable version", CarSetup.detection("abc_1"), NONE);
        check("FYT unit", CarSetup.detection(null, Detection.Platform.FYT), "fyt");
        check("Microntek unit", CarSetup.detection(null, Detection.Platform.MICRONTEK), "microntek");
        check("both", CarSetup.detection(null, Detection.Platform.AMBIGUOUS), "ambiguous");
        check("nothing preselected on both", CarSetup.suggestion("ambiguous", null, BETA), null);
        check("FYT on both warns", CarSetup.mismatch("ambiguous", "fyt:psa_raise") != null, true);
        check("PSA on both warns", CarSetup.mismatch("ambiguous", "psa_raise") != null, true);
        check("car app wins over FYT", CarSetup.detection("961_0000_260130", Detection.Platform.FYT), VAN);

        // Which builds offer the untested choices.
        check("stable release", CarSetup.betaBuild("0.21.0"), false);
        check("beta", CarSetup.betaBuild("0.21.0-beta.3"), true);
        check("local build", CarSetup.betaBuild("dev"), true);
        check("CI build", CarSetup.betaBuild("dev-abc1234"), true);
        check("no name counts as stable", CarSetup.betaBuild(null), false);
        check("unreadable name counts as stable", CarSetup.betaBuild("unknown"), false);

        // Nothing confirmed: everyone is asked once, the van included.
        check("van not confirmed", CarSetup.decide(VAN, null, null, STABLE), ASK);
        check("choice without its detection", CarSetup.decide(VAN, "psa_raise", null, STABLE), ASK);
        // Confirmed against another detection: asked again.
        check("detection changed", CarSetup.decide(VW, "psa_raise", VAN, STABLE), ASK);
        check("vendor app gone", CarSetup.decide(NONE, "profile:111", VW, BETA), ASK);
        check("unknown stored choice", CarSetup.decide(VAN, "something", VAN, BETA), ASK);
        check("undriven profile stored", CarSetup.decide(VAN, "profile:880", VAN, BETA), ASK);

        check("van confirmed", CarSetup.decide(VAN, "psa_raise", VAN, STABLE), SEND);
        check("van confirmed on a beta", CarSetup.decide(VAN, "psa_raise", VAN, BETA), SEND);
        check("PSA where nothing is detected", CarSetup.decide(NONE, "psa_raise", NONE, STABLE), SEND);
        check("not listed", CarSetup.decide(VAN, "not_listed", VAN, STABLE), REFUSE);

        // The user's confirmed pick is sent even where the head unit reports another box.
        check("PSA confirmed on VW", CarSetup.decide(VW, "psa_raise", VW, STABLE), SEND);
        check("VW confirmed on the van", CarSetup.decide(VAN, "profile:111", VAN, BETA), SEND);
        check("FYT confirmed on a Topway unit", CarSetup.decide(VAN, "fyt:psa_raise", VAN, BETA), SEND);

        // A stable release sends only PSA Raise: a beta pick kept from a beta build asks again.
        for (String c : new String[]{"profile:111", "fyt:psa_raise", "microntek:psa_raise"}) {
            check(c + " on a stable release", CarSetup.decide(VW, c, VW, STABLE), ASK);
            check(c + " on a beta", CarSetup.decide(VW, c, VW, BETA), SEND);
        }
        check("why a beta pick asks", CarSetup.why(VW, "profile:111", VW, STABLE),
                "Profile 111: Raise, VW MQB (beta, untested) is only in beta builds");

        // The choices each build offers: the detected one first, "not listed" last.
        check("stable on the van", CarSetup.choices(VAN, STABLE), "[psa_raise, not_listed]");
        check("stable on VW", CarSetup.choices(VW, STABLE), "[psa_raise, not_listed]");
        check("stable on FYT", CarSetup.choices("fyt", STABLE), "[psa_raise, not_listed]");
        check("beta on the van", CarSetup.choices(VAN, BETA), "[psa_raise, profile:96, profile:114, profile:111, "
                + "profile:274, profile:F91, fyt:psa_raise, microntek:psa_raise, not_listed]");
        check("beta on VW", CarSetup.choices(VW, BETA), "[profile:111, psa_raise, profile:96, profile:114, "
                + "profile:274, profile:F91, fyt:psa_raise, microntek:psa_raise, not_listed]");
        check("beta on FYT", CarSetup.choices("fyt", BETA)[0], "fyt:psa_raise");
        for (String d : new String[]{VAN, VW, FORD, NONE, "fyt", "microntek", "topway:964"}) {
            for (boolean beta : new boolean[]{STABLE, BETA}) {
                for (String c : CarSetup.choices(d, beta)) {
                    check("offered " + c + " on " + d, CarSetup.decide(d, c, d, beta),
                            c.equals("not_listed") ? REFUSE : SEND);
                }
            }
        }

        // The preselected choice: the last pick while this build offers it, else the detected one.
        check("suggest PSA on the van", CarSetup.suggestion(VAN, null, STABLE), "psa_raise");
        check("suggest the VW profile on a beta", CarSetup.suggestion(VW, null, BETA), "profile:111");
        check("nothing for VW on a stable release", CarSetup.suggestion(VW, null, STABLE), null);
        check("suggest FYT on a beta", CarSetup.suggestion("fyt", null, BETA), "fyt:psa_raise");
        check("suggest Microntek on a beta", CarSetup.suggestion("microntek", null, BETA), "microntek:psa_raise");
        check("suggest PSA without a detection", CarSetup.suggestion(NONE, null, STABLE), "psa_raise");
        check("suggest PSA for an unknown code", CarSetup.suggestion(FORD, null, STABLE), "psa_raise");
        check("nothing for an undriven profile", CarSetup.suggestion("topway:964", null, BETA), null);
        check("the last pick wins", CarSetup.suggestion(VAN, "profile:111", BETA), "profile:111");
        check("a beta pick on a stable release", CarSetup.suggestion(VAN, "profile:111", STABLE), "psa_raise");
        check("not listed is not preselected", CarSetup.suggestion(VAN, "not_listed", STABLE), "psa_raise");

        // A pick that differs from what the head unit reports gets a warning.
        check("PSA on the van fits", CarSetup.mismatch(VAN, "psa_raise"), null);
        check("PSA without a detection fits", CarSetup.mismatch(NONE, "psa_raise"), null);
        check("PSA on an unknown code fits", CarSetup.mismatch(FORD, "psa_raise"), null);
        check("VW on VW fits", CarSetup.mismatch(VW, "profile:111"), null);
        check("FYT on FYT fits", CarSetup.mismatch("fyt", "fyt:psa_raise"), null);
        check("not listed fits", CarSetup.mismatch(VW, "not_listed"), null);
        check("PSA on VW", CarSetup.mismatch(VW, "psa_raise"), "The head unit is set up for profile 111: Raise, VW "
                + "MQB. The CAN box only understands the commands it was made for: PSA with a Raise CAN box (tested on "
                + "a Citroën Jumpy) may do nothing, or something else in the car.");
        check("PSA on another PSA box", CarSetup.mismatch("topway:96", "psa_raise") != null, true);
        check("PSA on an FYT unit", CarSetup.mismatch("fyt", "psa_raise") != null, true);
        check("VW on the van", CarSetup.mismatch(VAN, "profile:111") != null, true);
        check("VW without a detection", CarSetup.mismatch(NONE, "profile:111") != null, true);
        check("FYT on a Topway unit", CarSetup.mismatch(VAN, "fyt:psa_raise") != null, true);
        check("Microntek on FYT", CarSetup.mismatch("fyt", "microntek:psa_raise") != null, true);

        check("platform PSA", CarSetup.platform("psa_raise"), Detection.Platform.TOPWAY);
        check("platform profile", CarSetup.platform("profile:111"), Detection.Platform.TOPWAY);
        check("platform FYT", CarSetup.platform("fyt:psa_raise"), Detection.Platform.FYT);
        check("platform Microntek", CarSetup.platform("microntek:psa_raise"), Detection.Platform.MICRONTEK);
        check("platform not listed", CarSetup.platform("not_listed"), Detection.Platform.NONE);
        check("platform undriven", CarSetup.platform("profile:880"), Detection.Platform.NONE);

        check("PSA label", CarSetup.choiceLabel("psa_raise"), "PSA with a Raise CAN box (tested on a Citroën Jumpy)");
        check("profile label", CarSetup.choiceLabel("profile:111"), "Profile 111: Raise, VW MQB (beta, untested)");
        check("FYT label", CarSetup.choiceLabel("fyt:psa_raise"),
                "PSA with a Raise CAN box, FYT head unit (beta, untested)");
        check("not listed label", CarSetup.choiceLabel("not_listed"), "My car is not listed");
        check("van detection", CarSetup.detectionLabel(VAN),
                "The head unit is set up for profile 961: Raise, PSA and Opel.");
        check("no detection", CarSetup.detectionLabel(NONE), "The head unit does not say which car it is set up for.");
        check("unknown code", CarSetup.detectionLabel(FORD),
                "The head unit is set up for profile 41, which the app does not know.");
        check("van name", CarSetup.detectionName(VAN), "profile 961");
        check("FYT name", CarSetup.detectionName("fyt"), "an FYT head unit");
        check("none name", CarSetup.detectionName(NONE), "no profile");

        check("why not confirmed", CarSetup.why(VAN, null, null, STABLE), "not confirmed");
        check("why changed", CarSetup.why(VW, "psa_raise", VAN, STABLE),
                "the head unit changed from profile 961 to profile 111 since it was confirmed");
        check("why not listed", CarSetup.why(VAN, "not_listed", VAN, STABLE), "the car is not listed");
        check("nothing to say when sent", CarSetup.why(VAN, "psa_raise", VAN, STABLE), null);
        System.out.println("CarSetupTest passed");
    }

    private static void check(String name, Object got, Object want) {
        if (got == null ? want != null : !got.equals(want)) {
            System.err.println("FAIL " + name + ": got " + got + ", want " + want);
            System.exit(1);
        }
    }

    private static void check(String name, String[] got, String want) {
        check(name, Arrays.toString(got), want);
    }
}
