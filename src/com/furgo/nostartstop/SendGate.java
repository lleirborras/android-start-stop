package com.furgo.nostartstop;

/**
 * Whether one command may go to the CAN box. {@link CanBox} asks right before the bytes go out, with a fresh
 * {@link Snapshot}, so the rules live here and nowhere else. Pure Java so it can be tested.
 */
final class SendGate {
    enum Kind {
        /** The PSA Raise stop-start switch over TWUtil. */
        PSA_SET,
        /** A PSA Raise status query over TWUtil. */
        PSA_QUERY,
        /** A beta car profile's own command over TWUtil. */
        PROFILE_SET,
        PROFILE_QUERY,
        /** The PSA Raise switch over the beta FYT or Microntek link. */
        OTHER_SET,
        OTHER_QUERY,
        /** Messages 266 and 267 to the head unit, asking its identity; not CAN box commands. */
        UNIT_QUERY
    }

    static final int SEND = 0, NOT_READY = 1, ASK = 2, REFUSE = 3;

    static final class Verdict {
        final int result;
        /** Which rule decided, for the log: "car setup", "watch", "PSA", "beta: FYT", ... Null for SEND. */
        final String rule;
        /** Why, for the log and the CAR NOT SUPPORTED state; null for SEND. */
        final String why;

        Verdict(int result, String rule, String why) {
            this.result = result;
            this.rule = rule;
            this.why = why;
        }

        boolean send() {
            return result == SEND;
        }

        @Override
        public String toString() {
            return result == SEND ? "SEND" : (result == NOT_READY ? "NOT_READY" : result == ASK ? "ASK" : "REFUSE")
                    + " " + rule + ": " + why;
        }
    }

    private static final Verdict SENT = new Verdict(SEND, null, null);

    /** Everything a send depends on, read just before it. */
    static final class Snapshot {
        final Detection detection;
        /** The car setup, see {@link CarSetup#decide}. */
        final String setupChoice, setupConfirmed;
        /** A beta or local build: only those send the untested choices. */
        final boolean betaBuild;
        /** The watch service: it only listens. */
        final boolean watch;
        /** Code of the beta profile the TWUtil link drives, or null for the PSA defaults. */
        final String profile;
        /** The open link: TOPWAY for TWUtil, FYT or MICRONTEK, NONE before one is open. */
        final Detection.Platform link;
        /** The car the beta link reports: FYT canbus id, Microntek cfg_canbus; null until known. */
        final String car;

        Snapshot(Detection detection, String setupChoice, String setupConfirmed, boolean betaBuild, boolean watch,
                String profile, Detection.Platform link, String car) {
            this.detection = detection;
            this.setupChoice = setupChoice;
            this.setupConfirmed = setupConfirmed;
            this.betaBuild = betaBuild;
            this.watch = watch;
            this.profile = profile;
            this.link = link;
            this.car = car;
        }
    }

    private SendGate() {
    }

    /**
     * The kind a TWUtil command becomes on this link: the PSA bytes and profile commands go to a beta link as
     * OTHER_SET or OTHER_QUERY. UNIT_QUERY stays, and has no beta link to go to.
     */
    static Kind onLink(Kind kind, Detection.Platform link) {
        if (link != Detection.Platform.FYT && link != Detection.Platform.MICRONTEK) return kind;
        if (kind == Kind.UNIT_QUERY || kind == Kind.OTHER_SET || kind == Kind.OTHER_QUERY) return kind;
        return kind == Kind.PSA_SET || kind == Kind.PROFILE_SET ? Kind.OTHER_SET : Kind.OTHER_QUERY;
    }

    /**
     * The user's pick decides what goes out, a pick that differs from the head unit's own setting included: the
     * user confirmed it against a warning. Only the commands of that pick, over its link, are sent.
     */
    static Verdict decide(Kind kind, Snapshot s) {
        String detection = s.detection.carSetup();
        int setup = CarSetup.decide(detection, s.setupChoice, s.setupConfirmed, s.betaBuild);
        // Nothing at all before the user confirms the car, not even in watch mode.
        if (setup == CarSetup.ASK) {
            return new Verdict(ASK, "car setup", CarSetup.why(detection, s.setupChoice, s.setupConfirmed, s.betaBuild));
        }
        if (s.watch) return new Verdict(REFUSE, "watch", "the watch service never sends");
        if (setup == CarSetup.REFUSE) {
            return new Verdict(REFUSE, "car setup", CarSetup.why(detection, s.setupChoice, s.setupConfirmed, s.betaBuild));
        }
        Detection.Platform wanted = CarSetup.platform(s.setupChoice);
        boolean other = kind == Kind.OTHER_SET || kind == Kind.OTHER_QUERY;
        if (other != (wanted == Detection.Platform.FYT || wanted == Detection.Platform.MICRONTEK)) {
            return new Verdict(REFUSE, "car setup", other ? "the chosen CAN box is not on an FYT or Microntek unit"
                    : "the chosen CAN box is on an FYT or Microntek unit");
        }
        String code = CarSetup.profileCode(s.setupChoice);
        boolean profileKind = kind == Kind.PROFILE_SET || kind == Kind.PROFILE_QUERY;
        if (profileKind && (code == null || !code.equals(s.profile))) {
            return new Verdict(REFUSE, "profile", "profile " + s.profile + " is not the chosen CAN box");
        }
        if (code != null && (kind == Kind.PSA_SET || kind == Kind.PSA_QUERY)) {
            return new Verdict(REFUSE, "profile", "PSA bytes, but profile " + code + " was chosen");
        }
        Verdict platform = other ? otherPlatform(s, wanted) : topwayPlatform(s);
        if (platform != null) return platform;
        if (other && s.link != Detection.Platform.NONE) {
            String car = carRefusal(s.link, s.car);
            // "beta: " first: the main screen redraws on those log lines.
            String rule = "beta: " + (s.link == Detection.Platform.FYT ? "FYT" : "Microntek");
            if (OtherUnits.NOT_IDENTIFIED.equals(car)) return new Verdict(NOT_READY, rule, car);
            if (car != null) return new Verdict(REFUSE, rule, car);
        }
        return SENT;
    }

    private static Verdict topwayPlatform(Snapshot s) {
        if (s.link == Detection.Platform.NONE) return null;
        if (s.link != Detection.Platform.TOPWAY) return new Verdict(REFUSE, "link", "TWUtil command on the beta link");
        return s.detection.topway ? null : new Verdict(REFUSE, "link", "TWUtil not found");
    }

    private static Verdict otherPlatform(Snapshot s, Detection.Platform wanted) {
        if (s.link == Detection.Platform.NONE) return null;
        return s.link == wanted ? null : new Verdict(REFUSE, "beta", "the open link is " + s.link.name()
                + ", not the chosen " + wanted.name());
    }

    /**
     * Why the PSA Raise command must not go to the car this beta link reports, or null when it may.
     * {@link OtherUnits#NOT_IDENTIFIED} while an FYT unit has not said yet.
     */
    static String carRefusal(Detection.Platform link, String car) {
        if (link == Detection.Platform.FYT) {
            if (car == null) return OtherUnits.NOT_IDENTIFIED;
            try {
                return OtherUnits.fytRefusal(Integer.parseInt(car));
            } catch (NumberFormatException e) {
                return OtherUnits.fytRefusal(0);
            }
        }
        if (link == Detection.Platform.MICRONTEK) return OtherUnits.microntekRefusal(car);
        return null;
    }

    /** For the CAR NOT SUPPORTED state: only once the link has reported the car. */
    static String unsupported(Detection.Platform link, String car) {
        return car == null ? null : carRefusal(link, car);
    }
}
