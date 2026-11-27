package com.furgo.nostartstop;

import java.util.Arrays;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class CarSetupTest {
    private static final int ASK = CarSetup.ASK, SEND = CarSetup.SEND, REFUSE = CarSetup.REFUSE;
    private static final String VAN = "topway:961", VW = "topway:111", FORD = "topway:41", NONE = "none";

    public static void main(String[] args) {
        check("van detected", CarSetup.detection("961_0000_260130"), VAN);
        check("VW detected", CarSetup.detection("111_0000_260327"), VW);
        check("unknown code detected", CarSetup.detection("41_0000_210812"), FORD);
        check("no vendor app", CarSetup.detection(null), NONE);
        check("unreadable version", CarSetup.detection("abc_1"), NONE);

        // Nothing confirmed: everyone is asked once, the van included.
        check("van not confirmed", CarSetup.decide(VAN, null, null), ASK);
        check("nothing detected, not confirmed", CarSetup.decide(NONE, null, null), ASK);
        check("choice without its detection", CarSetup.decide(VAN, "psa_raise", null), ASK);

        // Confirmed against another detection: asked again.
        check("detection changed", CarSetup.decide(VW, "psa_raise", VAN), ASK);
        check("vendor app appeared", CarSetup.decide(VAN, "psa_raise", NONE), ASK);
        check("vendor app gone", CarSetup.decide(NONE, "profile:111", VW), ASK);
        check("not listed, detection changed", CarSetup.decide(VAN, "not_listed", VW), ASK);
        check("unknown stored choice", CarSetup.decide(VAN, "something", VAN), ASK);

        check("van confirmed", CarSetup.decide(VAN, "psa_raise", VAN), SEND);
        check("PSA by hand where nothing is detected", CarSetup.decide(NONE, "psa_raise", NONE), SEND);
        check("PSA on an unknown code", CarSetup.decide(FORD, "psa_raise", FORD), SEND);
        check("PSA on a known other profile", CarSetup.decide(VW, "psa_raise", VW), REFUSE);
        check("PSA on another PSA box", CarSetup.decide("topway:96", "psa_raise", "topway:96"), REFUSE);
        check("PSA on an undriven profile", CarSetup.decide("topway:964", "psa_raise", "topway:964"), REFUSE);

        check("VW profile on VW", CarSetup.decide(VW, "profile:111", VW), SEND);
        for (String code : new String[]{"96", "114", "111", "274", "F91"}) {
            check("profile " + code, CarSetup.decide("topway:" + code, "profile:" + code, "topway:" + code), SEND);
        }
        check("profile on another profile", CarSetup.decide("topway:114", "profile:111", "topway:114"), REFUSE);
        check("profile where nothing is detected", CarSetup.decide(NONE, "profile:111", NONE), REFUSE);
        check("profile on the van", CarSetup.decide(VAN, "profile:111", VAN), REFUSE);
        check("undriven profile", CarSetup.decide("topway:880", "profile:880", "topway:880"), REFUSE);
        check("PSA Raise as a profile", CarSetup.decide(VAN, "profile:961", VAN), REFUSE);

        check("not listed", CarSetup.decide(VAN, "not_listed", VAN), REFUSE);
        check("not listed, nothing detected", CarSetup.decide(NONE, "not_listed", NONE), REFUSE);

        check("suggest PSA on the van", CarSetup.suggestion(VAN), "psa_raise");
        check("suggest the VW profile", CarSetup.suggestion(VW), "profile:111");
        check("suggest the Haval profile", CarSetup.suggestion("topway:F91"), "profile:F91");
        check("no suggestion without a detection", CarSetup.suggestion(NONE), null);
        check("no suggestion for an unknown code", CarSetup.suggestion(FORD), null);
        check("no suggestion for an undriven profile", CarSetup.suggestion("topway:964"), null);

        // The screen offers only what would be sent.
        check("van choices", CarSetup.choices(VAN), "[psa_raise, not_listed]");
        check("VW choices", CarSetup.choices(VW), "[profile:111, not_listed]");
        check("unknown code choices", CarSetup.choices(FORD), "[psa_raise, not_listed]");
        check("undriven profile choices", CarSetup.choices("topway:964"), "[not_listed]");
        check("nothing detected choices", CarSetup.choices(NONE), "[psa_raise, not_listed]");
        for (String d : new String[]{VAN, VW, FORD, NONE, "topway:964", "topway:F91"}) {
            for (String c : CarSetup.choices(d)) {
                check("offered " + c + " on " + d, CarSetup.decide(d, c, d), c.equals("not_listed") ? REFUSE : SEND);
            }
        }

        check("PSA label", CarSetup.choiceLabel("psa_raise"), "PSA with a Raise CAN box (tested on a Citroën Jumpy)");
        check("profile label", CarSetup.choiceLabel("profile:111"), "Profile 111: Raise, VW MQB (beta, untested)");
        check("not listed label", CarSetup.choiceLabel("not_listed"), "My car is not listed");
        check("van detection", CarSetup.detectionLabel(VAN),
                "The head unit is set up for profile 961: Raise, PSA and Opel.");
        check("no detection", CarSetup.detectionLabel(NONE), "The head unit does not say which car it is set up for.");
        check("unknown code", CarSetup.detectionLabel(FORD),
                "The head unit is set up for profile 41, which the app does not know.");
        check("van name", CarSetup.detectionName(VAN), "profile 961");
        check("none name", CarSetup.detectionName(NONE), "no profile");

        check("why not confirmed", CarSetup.why(VAN, null, null), "not confirmed");
        check("why changed", CarSetup.why(VW, "psa_raise", VAN),
                "the head unit changed from profile 961 to profile 111 since it was confirmed");
        check("why not listed", CarSetup.why(VAN, "not_listed", VAN), "the car is not listed");
        check("why refused", CarSetup.why(VW, "psa_raise", VW),
                "PSA with a Raise CAN box (tested on a Citroën Jumpy) does not match profile 111");
        check("nothing to say when sent", CarSetup.why(VAN, "psa_raise", VAN), null);
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
