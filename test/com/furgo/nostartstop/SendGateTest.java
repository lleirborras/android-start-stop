package com.furgo.nostartstop;

import com.furgo.nostartstop.Detection.Platform;
import com.furgo.nostartstop.SendGate.Kind;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class SendGateTest {
    private static final int SEND = SendGate.SEND, NOT_READY = SendGate.NOT_READY, ASK = SendGate.ASK,
            REFUSE = SendGate.REFUSE;
    private static final String VAN = "961_0000_260130", VW = "111_0000_260327", JEEP = "880_0000_250101",
            FORD = "41_0000_210812";

    /** One row's inputs; each with* returns a copy, so a row never changes another. */
    private static final class S implements Cloneable {
        boolean topway = true, fyt, microntek;
        String vendor = VAN;
        String choice = "psa_raise", confirmed = "topway:961";
        boolean beta, watch;
        String profile;
        Platform link = Platform.TOPWAY;
        String car;

        S copy() {
            try {
                return (S) clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }

        S vendor(String v) {
            S s = copy();
            s.vendor = v;
            s.confirmed = CarSetup.detection(v);
            return s;
        }

        S choice(String c) {
            S s = copy();
            s.choice = c;
            return s;
        }

        S confirmed(String d) {
            S s = copy();
            s.confirmed = d;
            return s;
        }

        S unconfirmed() {
            S s = copy();
            s.choice = null;
            s.confirmed = null;
            return s;
        }

        S copyStable() {
            S s = copy();
            s.beta = false;
            return s;
        }

        S beta() {
            S s = copy();
            s.beta = true;
            return s;
        }

        S watch() {
            S s = copy();
            s.watch = true;
            return s;
        }

        S profile(String code) {
            S s = copy();
            s.profile = code;
            return s;
        }

        S platforms(boolean topway, boolean fyt, boolean microntek) {
            S s = copy();
            s.topway = topway;
            s.fyt = fyt;
            s.microntek = microntek;
            return s;
        }

        S link(Platform l, String car) {
            S s = copy();
            s.link = l;
            s.car = car;
            return s;
        }

        /** An FYT unit on a beta build, no Topway car app, confirmed for PSA Raise over FYT. */
        S fyt(String car) {
            S s = vendor(null).platforms(false, true, false).link(Platform.FYT, car).beta();
            s.choice = "fyt:psa_raise";
            s.confirmed = "fyt";
            return s;
        }

        S microntek(String car) {
            S s = vendor(null).platforms(false, false, true).link(Platform.MICRONTEK, car).beta();
            s.choice = "microntek:psa_raise";
            s.confirmed = "microntek";
            return s;
        }

        SendGate.Verdict on(Kind k) {
            return SendGate.decide(k, new SendGate.Snapshot(new Detection(topway, fyt, microntek, vendor), choice,
                    confirmed, beta, watch, profile, link, car));
        }
    }

    public static void main(String[] args) {
        S van = new S();
        S vw = new S().vendor(VW).choice("profile:111").beta();

        // The tested van, on a stable release: everything goes.
        for (Kind k : new Kind[]{Kind.PSA_SET, Kind.PSA_QUERY, Kind.UNIT_QUERY}) check("van " + k, van.on(k), SEND, null);
        check("van on a beta", van.beta().on(Kind.PSA_SET), SEND, null);
        check("van, check at open", van.link(Platform.NONE, null).on(Kind.PSA_QUERY), SEND, null);
        check("PSA without car app", van.vendor(null).on(Kind.PSA_SET), SEND, null);
        check("PSA on an unknown code", van.vendor(FORD).on(Kind.PSA_SET), SEND, null);

        // ASK beats everything.
        for (Kind k : Kind.values()) {
            check("not confirmed " + k, van.unconfirmed().on(k), ASK, "car setup");
            check("not confirmed beats watch " + k, van.unconfirmed().watch().on(k), ASK, "car setup");
        }
        check("detection changed", van.confirmed("topway:111").on(Kind.PSA_SET), ASK, "car setup");
        check("car app gone", vw.vendor(null).confirmed("topway:111").on(Kind.PROFILE_SET), ASK, "car setup");

        // A beta pick kept on a stable release asks again, whatever the kind.
        for (Kind k : Kind.values()) {
            check("VW on a stable release " + k, vw.profile("111").copyStable().on(k), ASK, "car setup");
            check("FYT on a stable release " + k, van.fyt("339").copyStable().on(k), ASK, "car setup");
        }

        // Watch mode never sends.
        for (Kind k : Kind.values()) {
            check("watch " + k, van.watch().on(k), REFUSE, "watch");
            check("watch beats a refused setup " + k, van.choice("not_listed").watch().on(k), REFUSE, "watch");
        }
        check("not listed", van.choice("not_listed").on(Kind.PSA_SET), REFUSE, "car setup");
        check("not listed beats not ready", van.fyt(null).choice("not_listed").on(Kind.OTHER_SET), REFUSE,
                "car setup");

        // A confirmed pick goes out even where the head unit reports another box: the user confirmed the warning.
        check("PSA confirmed on VW", van.vendor(VW).on(Kind.PSA_SET), SEND, null);
        check("PSA confirmed on Jeep", van.vendor(JEEP).on(Kind.PSA_SET), SEND, null);
        check("VW confirmed on the van", vw.vendor(VAN).choice("profile:111").profile("111").on(Kind.PROFILE_SET),
                SEND, null);

        // Only the chosen box's commands.
        check("VW, profile switch", vw.profile("111").on(Kind.PROFILE_SET), SEND, null);
        check("VW, profile query", vw.profile("111").on(Kind.PROFILE_QUERY), SEND, null);
        check("VW, unit id", vw.on(Kind.UNIT_QUERY), SEND, null);
        check("VW, PSA switch", vw.on(Kind.PSA_SET), REFUSE, "profile");
        check("VW, PSA query", vw.on(Kind.PSA_QUERY), REFUSE, "profile");
        check("VW, another profile driven", vw.profile("114").on(Kind.PROFILE_SET), REFUSE, "profile");
        check("VW, no profile driven", vw.on(Kind.PROFILE_SET), REFUSE, "profile");
        check("PSA, a profile driven", van.beta().profile("111").on(Kind.PROFILE_SET), REFUSE, "profile");

        // FYT and Microntek: the chosen link only, and the car it reports.
        for (Kind k : new Kind[]{Kind.OTHER_SET, Kind.OTHER_QUERY}) {
            check("FYT 339 " + k, van.fyt("339").on(k), SEND, null);
            check("FYT 339 variant " + k, van.fyt(String.valueOf((5 << 16) | 339)).on(k), SEND, null);
            check("FYT other car, confirmed " + k, van.fyt("281").on(k), SEND, null);
            check("FYT unknown " + k, van.fyt(null).on(k), NOT_READY, "beta: FYT");
            check("Microntek 50 " + k, van.microntek("50").on(k), SEND, null);
            check("Microntek 50 spaces " + k, van.microntek(" 50\n").on(k), SEND, null);
            check("Microntek other box, confirmed " + k, van.microntek("59").on(k), SEND, null);
            check("Microntek unknown, confirmed " + k, van.microntek(null).on(k), SEND, null);
            check("beta link with PSA chosen " + k, van.beta().link(Platform.FYT, "339").on(k), REFUSE, "car setup");
            check("FYT chosen, Microntek link " + k, van.fyt("339").link(Platform.MICRONTEK, "50").on(k), REFUSE,
                    "beta");
        }
        check("FYT warning", SendGate.carMismatch(Platform.FYT, "281"), "canbus id 281 is not PSA with a Raise box (339)");
        check("Microntek warning", SendGate.carMismatch(Platform.MICRONTEK, "59"),
                "CAN box type 59 is not PSA with a Raise box (50)");
        check("no warning for FYT 339", SendGate.carMismatch(Platform.FYT, "339"), null);
        check("no warning for Microntek 50", SendGate.carMismatch(Platform.MICRONTEK, "50"), null);
        check("FYT picked on a unit with both", van.fyt("339").platforms(false, true, true).confirmed("ambiguous")
                .on(Kind.OTHER_SET), SEND, null);
        check("FYT chosen, TWUtil command", van.fyt("339").on(Kind.PSA_SET), REFUSE, "car setup");
        check("FYT chosen, unit query", van.fyt("339").on(Kind.UNIT_QUERY), REFUSE, "car setup");
        check("FYT chosen, open", van.fyt("339").link(Platform.NONE, null).on(Kind.OTHER_QUERY), SEND, null);

        // The TWUtil link.
        check("TWUtil link without TWUtil", van.platforms(false, false, false).on(Kind.PSA_SET), REFUSE, "link");
        check("TWUtil with FYT too", van.platforms(true, true, true).on(Kind.PSA_SET), SEND, null);

        // What the beta link reports about the car only warns.
        check("Microntek unknown type", SendGate.carMismatch(Platform.MICRONTEK, null), "CAN box type unknown");
        check("FYT not reported yet", SendGate.carMismatch(Platform.FYT, null), OtherUnits.NOT_IDENTIFIED);
        check("FYT car not a number, confirmed", van.fyt("abc").on(Kind.OTHER_SET), SEND, null);

        // The kind on each link.
        check("PSA set on FYT", SendGate.onLink(Kind.PSA_SET, Platform.FYT), Kind.OTHER_SET);
        check("PSA query on Microntek", SendGate.onLink(Kind.PSA_QUERY, Platform.MICRONTEK), Kind.OTHER_QUERY);
        check("profile set on FYT", SendGate.onLink(Kind.PROFILE_SET, Platform.FYT), Kind.OTHER_SET);
        check("profile query on FYT", SendGate.onLink(Kind.PROFILE_QUERY, Platform.FYT), Kind.OTHER_QUERY);
        check("unit query on FYT", SendGate.onLink(Kind.UNIT_QUERY, Platform.FYT), Kind.UNIT_QUERY);
        for (Kind k : Kind.values()) {
            check("TWUtil keeps " + k, SendGate.onLink(k, Platform.TOPWAY), k);
            check("no link keeps " + k, SendGate.onLink(k, Platform.NONE), k);
        }

        // An undriven profile is never offered, so a stored one asks again.
        check("undriven profile", van.vendor(JEEP).choice("profile:880").beta().on(Kind.PROFILE_SET), ASK,
                "car setup");
        System.out.println("SendGateTest passed");
    }

    private static void check(String name, SendGate.Verdict v, int result, String rule) {
        if (v.result != result || (rule == null ? v.rule != null : !rule.equals(v.rule))) {
            System.err.println("FAIL " + name + ": got " + v + ", want " + result + " " + rule);
            System.exit(1);
        }
    }

    private static void check(String name, Object got, Object want) {
        if (got == null ? want != null : !got.equals(want)) {
            System.err.println("FAIL " + name + ": got " + got + ", want " + want);
            System.exit(1);
        }
    }
}
