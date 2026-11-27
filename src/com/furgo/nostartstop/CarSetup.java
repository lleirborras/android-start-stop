package com.furgo.nostartstop;

/**
 * The car the user confirmed, and whether the app may send to it. Nothing is sent until the user confirms a choice
 * for what the head unit reports now (its detection); a change of the head unit's setting asks again.
 * <p>
 * Detection: "topway:&lt;code&gt;" when the vendor car app names a profile, else "none". FYT and Microntek units
 * learn the car only once their link is open, so they are "none" here and keep their own checks.
 * Choice: "psa_raise", "profile:&lt;code&gt;" for a profile {@link CarProfile#forBeta} drives, or "not_listed".
 */
final class CarSetup {
    static final int ASK = 0, SEND = 1, REFUSE = 2;
    static final String NONE = "none";
    static final String PSA_RAISE = "psa_raise";
    static final String NOT_LISTED = "not_listed";
    private static final String TOPWAY = "topway:";
    private static final String PROFILE = "profile:";

    private CarSetup() {
    }

    /** From the vendor car app's version name, or null when it is absent. */
    static String detection(String vendorVersion) {
        String code = CarProfile.code(vendorVersion);
        return code == null ? NONE : TOPWAY + code;
    }

    static String profileChoice(String code) {
        return PROFILE + code;
    }

    /** The profile code of a "profile:" choice, or null. */
    static String profileCode(String choice) {
        return choice != null && choice.startsWith(PROFILE) ? choice.substring(PROFILE.length()) : null;
    }

    private static String detectedCode(String detection) {
        return detection != null && detection.startsWith(TOPWAY) ? detection.substring(TOPWAY.length()) : null;
    }

    /** confirmedDetection is the detection the choice was confirmed against; both are null until confirmed. */
    static int decide(String detection, String choice, String confirmedDetection) {
        if (choice == null || confirmedDetection == null || !confirmedDetection.equals(detection)) return ASK;
        if (NOT_LISTED.equals(choice)) return REFUSE;
        if (!PSA_RAISE.equals(choice) && profileCode(choice) == null) return ASK;
        return consistent(detection, choice) ? SEND : REFUSE;
    }

    /**
     * PSA Raise fits its own profile, no detection and codes the app does not know (the user said so); a profile
     * fits only the same detected profile.
     */
    private static boolean consistent(String detection, String choice) {
        String code = detectedCode(detection);
        if (PSA_RAISE.equals(choice)) {
            CarProfile p = code == null ? null : CarProfile.of(code);
            return p == null || p == CarProfile.PSA_RAISE;
        }
        String wanted = profileCode(choice);
        return wanted != null && wanted.equals(code) && CarProfile.forBeta(code) != null;
    }

    /** The choice to preselect, or null. */
    static String suggestion(String detection) {
        String code = detectedCode(detection);
        if (code == null) return null;
        if (CarProfile.of(code) == CarProfile.PSA_RAISE) return PSA_RAISE;
        return CarProfile.forBeta(code) != null ? profileChoice(code) : null;
    }

    /** The choices to offer: only those that would be sent, and "not listed". */
    static String[] choices(String detection) {
        String code = detectedCode(detection);
        boolean psa = consistent(detection, PSA_RAISE);
        boolean beta = code != null && CarProfile.forBeta(code) != null;
        if (psa) return new String[]{PSA_RAISE, NOT_LISTED};
        if (beta) return new String[]{profileChoice(code), NOT_LISTED};
        return new String[]{NOT_LISTED};
    }

    static String choiceLabel(String choice) {
        if (PSA_RAISE.equals(choice)) return "PSA with a Raise CAN box (tested on a Citroën Jumpy)";
        if (NOT_LISTED.equals(choice)) return "My car is not listed";
        String code = profileCode(choice);
        CarProfile p = code == null ? null : CarProfile.of(code);
        if (p == null) return "Unknown choice";
        return "Profile " + p.code + ": " + p.name + " (beta, untested)";
    }

    /** For the setup screen. */
    static String detectionLabel(String detection) {
        String code = detectedCode(detection);
        if (code == null) return "The head unit does not say which car it is set up for.";
        CarProfile p = CarProfile.of(code);
        if (p == null) return "The head unit is set up for profile " + code + ", which the app does not know.";
        return "The head unit is set up for profile " + code + ": " + p.name + ".";
    }

    /** Short, for the log and the settings. */
    static String detectionName(String detection) {
        String code = detectedCode(detection);
        return code == null ? "no profile" : "profile " + code;
    }

    /** Why nothing may be sent, for the log, or null when it may. */
    static String why(String detection, String choice, String confirmedDetection) {
        int d = decide(detection, choice, confirmedDetection);
        if (d == SEND) return null;
        if (d == ASK) {
            if (choice == null || confirmedDetection == null || confirmedDetection.equals(detection)) return "not confirmed";
            return "the head unit changed from " + detectionName(confirmedDetection) + " to "
                    + detectionName(detection) + " since it was confirmed";
        }
        if (NOT_LISTED.equals(choice)) return "the car is not listed";
        return choiceLabel(choice) + " does not match " + detectionName(detection);
    }
}
