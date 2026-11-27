package com.furgo.nostartstop;

/**
 * The CAN box profile of this head unit, from the version name of the vendor app com.tw.car, which is built once
 * per profile. Only the stop-start command of {@link #PSA_RAISE} is tested on a car; the others are read from
 * decompiled vendor builds, see docs/vendor-profiles.md. Nothing outside PSA_RAISE is sent yet.
 */
final class CarProfile {
    static final String VENDOR_PACKAGE = "com.tw.car";

    /** Toggles stop-start on any change; see {@link Protocol#toggleValue}. */
    static final int TOGGLE = 0;
    /** Takes an explicit value: the data below turns stop-start off. */
    static final int VALUE = 1;
    /** Acts like a press of the car's button, with no value. */
    static final int PRESS = 2;

    final String code;
    final String name;
    final int kind;
    /** Command byte of message 1281 and its data that switches stop-start off (or toggles or presses it). */
    final int cmd;
    final byte[] off;
    final boolean tested;

    private CarProfile(String code, String name, int kind, int cmd, int[] off, boolean tested) {
        this.code = code;
        this.name = name;
        this.kind = kind;
        this.cmd = cmd;
        this.off = new byte[off.length];
        for (int i = 0; i < off.length; i++) this.off[i] = (byte) off[i];
        this.tested = tested;
    }

    static final CarProfile PSA_RAISE = new CarProfile("961", "Raise, PSA and Opel", TOGGLE, 128, new int[]{13, 1}, true);

    private static final CarProfile[] KNOWN = {
            PSA_RAISE,
            new CarProfile("96", "Simple Soft, Binary or LuZheng, PSA", VALUE, 128, new int[]{13, 0}, false),
            new CarProfile("964", "Hiworld, PSA and Opel", PRESS, 140, new int[]{1, 255}, false),
            new CarProfile("114", "Hiworld, VW MQB", VALUE, 74, new int[]{13, 0}, false),
            new CarProfile("111", "Raise, VW MQB", VALUE, 198, new int[]{17, 0}, false),
            new CarProfile("11", "Simple Soft or Binary, VW MQB", VALUE, 198, new int[]{58, 0}, false),
            new CarProfile("274", "Hiworld, Honda", VALUE, 111, new int[]{8, 0}, false),
            new CarProfile("880", "Simple Soft, Jeep", VALUE, 198, new int[]{114, 0}, false),
            new CarProfile("F91", "Raise, GWM Haval", VALUE, 131, new int[]{11, 0}, false),
            new CarProfile("F94", "Hiworld, GWM Haval", VALUE, 109, new int[]{1, 0, 255, 255}, false),
    };

    /** The profile code in a com.tw.car version name such as "961_0000_260130", or null. */
    static String code(String versionName) {
        if (versionName == null) return null;
        int end = versionName.indexOf('_');
        String code = end < 0 ? versionName : versionName.substring(0, end);
        return code.matches("[0-9A-Z]{1,8}") ? code : null;
    }

    /** The known profile for a com.tw.car version name, or null when none has a stop-start command. */
    static CarProfile of(String versionName) {
        String code = code(versionName);
        if (code == null) return null;
        for (CarProfile p : KNOWN) {
            if (p.code.equals(code)) return p;
        }
        return null;
    }

    /**
     * Why the PSA Raise commands must not be sent on this head unit, or null when they may: its vendor car app names
     * a known profile other than {@link #PSA_RAISE}, where those bytes mean something else. Unknown profiles and units
     * without the vendor app are left as they were.
     */
    static String psaRefusal(String versionName) {
        CarProfile p = of(versionName);
        return p == null || p == PSA_RAISE ? null : "the car app names profile " + p.code + " (" + p.name + ")";
    }

    /** For the log, for example "car app 961_0000_260130, profile 961 (Raise, PSA and Opel, tested)". */
    static String describe(String versionName) {
        if (versionName == null) return "car app not found";
        String code = code(versionName);
        CarProfile p = of(versionName);
        String clean = versionName.replaceAll("[^0-9A-Za-z_.-]", "?");
        if (clean.length() > 40) clean = clean.substring(0, 40);
        if (code == null) return "car app " + clean + ", profile unknown";
        if (p == null) return "car app " + clean + ", profile " + code + " (no stop-start command known)";
        return "car app " + clean + ", profile " + code + " (" + p.name + (p.tested ? ", tested)" : ", untested)");
    }
}
