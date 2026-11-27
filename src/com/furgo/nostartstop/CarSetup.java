package com.furgo.nostartstop;

import java.util.ArrayList;
import java.util.List;

/**
 * The CAN box the user picked, and whether the app may send to it. Nothing is sent until the user confirms a pick
 * for what the head unit reports now (its detection); a change of the head unit's setting asks again. A pick that
 * differs from the detection is sent too, once the user confirmed it against a warning.
 * <p>
 * Detection: "topway:&lt;code&gt;" when the vendor car app names a profile, "fyt" or "microntek" for those head units
 * without it, else "none".
 * Choice: "psa_raise", "profile:&lt;code&gt;" for a profile {@link CarProfile#forBeta} drives, "fyt:psa_raise" or
 * "microntek:psa_raise" for PSA Raise over those head units' links, or "not_listed". A stable release offers only
 * "psa_raise", the one tested on a car; a beta or local build offers them all.
 */
final class CarSetup {
    static final int ASK = 0, SEND = 1, REFUSE = 2;
    static final String NONE = "none";
    static final String PSA_RAISE = "psa_raise";
    static final String FYT_PSA = "fyt:psa_raise";
    static final String MICRONTEK_PSA = "microntek:psa_raise";
    static final String NOT_LISTED = "not_listed";
    static final String FYT = "fyt", MICRONTEK = "microntek";
    private static final String TOPWAY = "topway:";
    private static final String PROFILE = "profile:";

    private CarSetup() {
    }

    /** A stable release is named X.Y.Z; betas ("X.Y.Z-beta.N") and local builds ("dev…") offer the beta CAN boxes. */
    static boolean betaBuild(String versionName) {
        return versionName == null || !versionName.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}");
    }

    /** From the vendor car app's version name, or null when it is absent. */
    static String detection(String vendorVersion) {
        return detection(vendorVersion, Detection.Platform.NONE);
    }

    /** With the beta link this head unit has when the vendor car app names no profile. */
    static String detection(String vendorVersion, Detection.Platform other) {
        String code = CarProfile.code(vendorVersion);
        if (code != null) return TOPWAY + code;
        if (other == Detection.Platform.FYT) return FYT;
        if (other == Detection.Platform.MICRONTEK) return MICRONTEK;
        return NONE;
    }

    static String profileChoice(String code) {
        return PROFILE + code;
    }

    /** The profile code of a "profile:" choice, or null. */
    static String profileCode(String choice) {
        return choice != null && choice.startsWith(PROFILE) ? choice.substring(PROFILE.length()) : null;
    }

    /** The link a choice goes over: FYT or MICRONTEK for those, TOPWAY for the others, NONE for none. */
    static Detection.Platform platform(String choice) {
        if (FYT_PSA.equals(choice)) return Detection.Platform.FYT;
        if (MICRONTEK_PSA.equals(choice)) return Detection.Platform.MICRONTEK;
        if (PSA_RAISE.equals(choice) || driven(profileCode(choice)) != null) return Detection.Platform.TOPWAY;
        return Detection.Platform.NONE;
    }

    private static CarProfile driven(String code) {
        return code == null ? null : CarProfile.forBeta(code);
    }

    private static String detectedCode(String detection) {
        return detection != null && detection.startsWith(TOPWAY) ? detection.substring(TOPWAY.length()) : null;
    }

    /** Whether this build offers the choice. */
    static boolean available(String choice, boolean beta) {
        if (PSA_RAISE.equals(choice) || NOT_LISTED.equals(choice)) return true;
        return beta && platform(choice) != Detection.Platform.NONE;
    }

    /** confirmedDetection is the detection the choice was confirmed against; both are null until confirmed. */
    static int decide(String detection, String choice, String confirmedDetection, boolean beta) {
        if (choice == null || confirmedDetection == null || !confirmedDetection.equals(detection)) return ASK;
        if (!available(choice, beta)) return ASK;
        return NOT_LISTED.equals(choice) ? REFUSE : SEND;
    }

    /**
     * Why a choice may not suit this head unit, for the warning the user confirms, or null when it fits: PSA Raise
     * fits its own profile, no detection and codes the app does not know; the others fit only what they name.
     */
    static String mismatch(String detection, String choice) {
        if (choice == null || NOT_LISTED.equals(choice)) return null;
        String code = detectedCode(detection);
        boolean fits;
        if (PSA_RAISE.equals(choice)) {
            CarProfile p = code == null ? null : CarProfile.of(code);
            fits = !FYT.equals(detection) && !MICRONTEK.equals(detection) && (p == null || p == CarProfile.PSA_RAISE);
        } else if (FYT_PSA.equals(choice)) {
            fits = FYT.equals(detection);
        } else if (MICRONTEK_PSA.equals(choice)) {
            fits = MICRONTEK.equals(detection);
        } else {
            String wanted = profileCode(choice);
            fits = wanted != null && wanted.equals(code);
        }
        if (fits) return null;
        return detectionLabel(detection) + " The CAN box only understands the commands it was made for: "
                + choiceLabel(choice) + " may do nothing, or something else in the car.";
    }

    /** The choice to preselect: the last one when this build still offers it, else the detected one, or null. */
    static String suggestion(String detection, String last, boolean beta) {
        if (last != null && available(last, beta) && !NOT_LISTED.equals(last)) return last;
        String code = detectedCode(detection);
        String detected;
        if (FYT.equals(detection)) detected = FYT_PSA;
        else if (MICRONTEK.equals(detection)) detected = MICRONTEK_PSA;
        else if (code == null || CarProfile.of(code) == null || CarProfile.of(code) == CarProfile.PSA_RAISE) {
            detected = PSA_RAISE;
        } else detected = driven(code) != null ? profileChoice(code) : null;
        return detected != null && available(detected, beta) ? detected : null;
    }

    /** The choices this build offers, the detected one first, "not listed" last. */
    static String[] choices(String detection, boolean beta) {
        List<String> out = new ArrayList<>();
        String detected = suggestion(detection, null, beta);
        if (detected != null) out.add(detected);
        if (!out.contains(PSA_RAISE)) out.add(PSA_RAISE);
        if (beta) {
            for (CarProfile p : CarProfile.betaProfiles()) {
                String c = profileChoice(p.code);
                if (!out.contains(c)) out.add(c);
            }
            if (!out.contains(FYT_PSA)) out.add(FYT_PSA);
            if (!out.contains(MICRONTEK_PSA)) out.add(MICRONTEK_PSA);
        }
        out.add(NOT_LISTED);
        return out.toArray(new String[0]);
    }

    /** Whether a choice is one of the untested ones. */
    static boolean beta(String choice) {
        return !PSA_RAISE.equals(choice) && !NOT_LISTED.equals(choice);
    }

    static String choiceLabel(String choice) {
        if (PSA_RAISE.equals(choice)) return "PSA with a Raise CAN box (tested on a Citroën Jumpy)";
        if (FYT_PSA.equals(choice)) return "PSA with a Raise CAN box, FYT head unit (beta, untested)";
        if (MICRONTEK_PSA.equals(choice)) return "PSA with a Raise CAN box, Microntek head unit (beta, untested)";
        if (NOT_LISTED.equals(choice)) return "My car is not listed";
        CarProfile p = driven(profileCode(choice));
        if (p == null) return "Unknown choice";
        return "Profile " + p.code + ": " + p.name + " (beta, untested)";
    }

    /** For the setup screen. */
    static String detectionLabel(String detection) {
        if (FYT.equals(detection)) return "This looks like an FYT head unit (Teyes, Joying and others).";
        if (MICRONTEK.equals(detection)) return "This looks like a Microntek head unit (Dasaita, Xtrons and others).";
        String code = detectedCode(detection);
        if (code == null) return "The head unit does not say which car it is set up for.";
        CarProfile p = CarProfile.of(code);
        if (p == null) return "The head unit is set up for profile " + code + ", which the app does not know.";
        return "The head unit is set up for profile " + code + ": " + p.name + ".";
    }

    /** Short, for the log and the settings. */
    static String detectionName(String detection) {
        if (FYT.equals(detection)) return "an FYT head unit";
        if (MICRONTEK.equals(detection)) return "a Microntek head unit";
        String code = detectedCode(detection);
        return code == null ? "no profile" : "profile " + code;
    }

    /** Why nothing may be sent, for the log, or null when it may. */
    static String why(String detection, String choice, String confirmedDetection, boolean beta) {
        int d = decide(detection, choice, confirmedDetection, beta);
        if (d == SEND) return null;
        if (d == ASK) {
            if (choice == null || confirmedDetection == null) return "not confirmed";
            if (!confirmedDetection.equals(detection)) {
                return "the head unit changed from " + detectionName(confirmedDetection) + " to "
                        + detectionName(detection) + " since it was confirmed";
            }
            return available(choice, true) ? choiceLabel(choice) + " is only in beta builds" : "not confirmed";
        }
        return "the car is not listed";
    }
}
