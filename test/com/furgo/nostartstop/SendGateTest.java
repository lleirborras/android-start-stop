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
        boolean otherCars, otherUnits, watch;
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

        S otherCars() {
            S s = copy();
            s.otherCars = true;
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

        /** An FYT unit with the beta on, no Topway car app, confirmed for PSA Raise. */
        S fyt(String car) {
            S s = vendor(null).platforms(false, true, false).link(Platform.FYT, car);
            s.otherUnits = true;
            return s;
        }

        S microntek(String car) {
            S s = vendor(null).platforms(false, false, true).link(Platform.MICRONTEK, car);
            s.otherUnits = true;
            return s;
        }

        SendGate.Verdict on(Kind k) {
            return SendGate.decide(k, new SendGate.Snapshot(new Detection(topway, fyt, microntek, vendor), choice,
                    confirmed, otherCars, otherUnits, watch, profile, link, car));
        }
    }

    public static void main(String[] args) {
        S van = new S();
        S vw = new S().vendor(VW).choice("profile:111");
        S vwDriven = vw.otherCars();

        // The tested van: everything goes.
        for (Kind k : new Kind[]{Kind.PSA_SET, Kind.PSA_QUERY, Kind.UNIT_QUERY}) check("van " + k, van.on(k), SEND, null);
        check("van, check at open", van.link(Platform.NONE, null).on(Kind.PSA_QUERY), SEND, null);
        check("PSA by hand without car app", van.vendor(null).on(Kind.PSA_SET), SEND, null);
        check("PSA by hand on an unknown code", van.vendor(FORD).on(Kind.PSA_SET), SEND, null);

        // ASK beats everything.
        for (Kind k : Kind.values()) {
            check("not confirmed " + k, van.unconfirmed().on(k), ASK, "car setup");
            check("not confirmed beats watch " + k, van.unconfirmed().watch().on(k), ASK, "car setup");
            check("not confirmed beats ambiguous " + k, van.fyt("339").platforms(false, true, true).unconfirmed().on(k),
                    ASK, "car setup");
        }
        check("detection changed", van.confirmed("topway:111").on(Kind.PSA_SET), ASK, "car setup");
        check("car app gone", vw.vendor(null).confirmed("topway:111").on(Kind.PROFILE_SET), ASK, "car setup");

        // Watch mode never sends.
        for (Kind k : Kind.values()) {
            check("watch " + k, van.watch().on(k), REFUSE, "watch");
            check("watch beats a refused setup " + k, van.choice("not_listed").watch().on(k), REFUSE, "watch");
        }

        // The car setup refuses.
        check("not listed", van.choice("not_listed").on(Kind.PSA_SET), REFUSE, "car setup");
        check("PSA on VW", vw.choice("psa_raise").on(Kind.PSA_SET), REFUSE, "car setup");
        check("not listed beats not ready", van.fyt(null).choice("not_listed").on(Kind.OTHER_SET), REFUSE,
                "car setup");

        // A known non-PSA profile gets PSA bytes only while the beta drives it, and never the switch.
        check("VW not driven, open", vw.link(Platform.NONE, null).on(Kind.PSA_QUERY), REFUSE, "PSA");
        check("VW not driven, query", vw.on(Kind.PSA_QUERY), REFUSE, "PSA");
        check("VW not driven, switch", vw.on(Kind.PSA_SET), REFUSE, "PSA");
        check("VW not driven, unit id", vw.on(Kind.UNIT_QUERY), REFUSE, "PSA");
        check("VW driven, open", vwDriven.link(Platform.NONE, null).on(Kind.PSA_QUERY), SEND, null);
        check("VW driven, PSA switch", vwDriven.on(Kind.PSA_SET), REFUSE, "PSA");
        check("VW driven, unit id", vwDriven.on(Kind.UNIT_QUERY), SEND, null);
        check("VW driven, profile switch", vwDriven.profile("111").on(Kind.PROFILE_SET), SEND, null);
        check("VW driven, profile query", vwDriven.profile("111").on(Kind.PROFILE_QUERY), SEND, null);
        S vwFyt = vwDriven.fyt("339").vendor(VW).choice("profile:111").otherCars();
        check("VW driven on FYT, query", vwFyt.on(Kind.OTHER_QUERY), SEND, null);
        check("VW driven on FYT, switch", vwFyt.on(Kind.OTHER_SET), REFUSE, "PSA");
        check("car app beats not ready", vwFyt.link(Platform.FYT, null).on(Kind.OTHER_SET), REFUSE, "PSA");

        // FYT: only canbus id 339, and nothing before the service reports it.
        for (Kind k : new Kind[]{Kind.OTHER_SET, Kind.OTHER_QUERY}) {
            check("FYT 339 " + k, van.fyt("339").on(k), SEND, null);
            check("FYT 339 variant " + k, van.fyt(String.valueOf((5 << 16) | 339)).on(k), SEND, null);
            check("FYT other car " + k, van.fyt("281").on(k), REFUSE, "beta: FYT");
            check("FYT unknown " + k, van.fyt(null).on(k), NOT_READY, "beta: FYT");
            check("Microntek 50 " + k, van.microntek("50").on(k), SEND, null);
            check("Microntek 50 spaces " + k, van.microntek(" 50\n").on(k), SEND, null);
            check("Microntek other box " + k, van.microntek("59").on(k), REFUSE, "beta: Microntek");
            check("Microntek unknown " + k, van.microntek(null).on(k), REFUSE, "beta: Microntek");
        }
        check("FYT reason", van.fyt("281").on(Kind.OTHER_SET).why, "canbus id 281 is not PSA with a Raise box (339)");
        check("Microntek reason", van.microntek("59").on(Kind.OTHER_SET).why,
                "CAN box type 59 is not PSA with a Raise box (50)");

        // A profile send checks the beta again: it may have been turned off since the open.
        check("profile, beta off", vw.profile("111").on(Kind.PROFILE_SET), REFUSE, "profile");
        check("profile query, beta off", vw.profile("111").on(Kind.PROFILE_QUERY), REFUSE, "profile");
        check("profile other than the car app's", vwDriven.profile("114").on(Kind.PROFILE_SET), REFUSE, "profile");
        check("profile on the van", van.otherCars().profile("111").on(Kind.PROFILE_SET), REFUSE, "profile");
        check("profile without one", vwDriven.on(Kind.PROFILE_SET), REFUSE, "profile");

        // The beta link checks its switch again.
        S fytOff = van.fyt("339");
        fytOff.otherUnits = false;
        check("FYT, beta off", fytOff.on(Kind.OTHER_SET), REFUSE, "beta");
        check("FYT unknown, beta off", fytOff.link(Platform.FYT, null).on(Kind.OTHER_QUERY), REFUSE, "beta");

        // The platform.
        S both = van.fyt("339").platforms(false, true, true);
        check("ambiguous, open", both.link(Platform.NONE, null).on(Kind.OTHER_QUERY), REFUSE, "beta");
        check("ambiguous, FYT link", both.on(Kind.OTHER_SET), REFUSE, "beta");
        check("ambiguous with TWUtil, TWUtil link", van.platforms(true, true, true).on(Kind.PSA_SET), SEND, null);
        check("FYT link on a Microntek unit", van.fyt("339").platforms(false, false, true).on(Kind.OTHER_SET),
                REFUSE, "beta");
        check("TWUtil link without TWUtil", van.platforms(false, false, false).on(Kind.PSA_SET), REFUSE, "link");
        check("TWUtil command on the FYT link", van.fyt("339").on(Kind.PSA_SET), REFUSE, "link");

        // The CAR NOT SUPPORTED state, only once the link reported the car.
        check("unsupported FYT unknown", SendGate.unsupported(Platform.FYT, null), null);
        check("unsupported FYT 339", SendGate.unsupported(Platform.FYT, "339"), null);
        check("unsupported FYT 281", SendGate.unsupported(Platform.FYT, "281"),
                "canbus id 281 is not PSA with a Raise box (339)");
        check("unsupported Microntek unknown", SendGate.unsupported(Platform.MICRONTEK, null), null);
        check("unsupported Microntek 59", SendGate.unsupported(Platform.MICRONTEK, "59"),
                "CAN box type 59 is not PSA with a Raise box (50)");
        check("open refusal Microntek unknown", SendGate.carRefusal(Platform.MICRONTEK, null), "CAN box type unknown");
        check("open refusal FYT unknown", SendGate.carRefusal(Platform.FYT, null), OtherUnits.NOT_IDENTIFIED);

        // An undriven profile: the setup never confirms it, so nothing reaches the profile rules.
        check("undriven profile", van.vendor(JEEP).choice("profile:880").on(Kind.PROFILE_SET), REFUSE, "car setup");
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
