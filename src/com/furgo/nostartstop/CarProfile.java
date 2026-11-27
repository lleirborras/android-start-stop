package com.furgo.nostartstop;

/**
 * The CAN box profile of this head unit, from the version name of the vendor app com.tw.car, which is built once
 * per profile. Only the stop-start command of {@link #PSA_RAISE} is tested on a car; the others are read from
 * decompiled vendor builds, see docs/vendor-profiles.md. The others are sent only with the beta, see
 * {@link #forBeta}.
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
    /** Where the box reports the setting: frame id (arg1), data[0] selector or -1, byte, bit; -1 frame when unknown. */
    private int frame = -1, selector = -1, readByte, readBit;
    /** A set readback bit means stop-start is on. */
    private boolean setMeansOn;
    /** The command that makes the box report that frame, or -1 when it reports it unasked. */
    private int queryCmd = -1;
    private byte[] query = new byte[0];

    private CarProfile(String code, String name, int kind, int cmd, int[] off, boolean tested) {
        this.code = code;
        this.name = name;
        this.kind = kind;
        this.cmd = cmd;
        this.off = new byte[off.length];
        for (int i = 0; i < off.length; i++) this.off[i] = (byte) off[i];
        this.tested = tested;
    }

    private CarProfile readback(int frame, int selector, int readByte, int readBit, boolean setMeansOn) {
        this.frame = frame;
        this.selector = selector;
        this.readByte = readByte;
        this.readBit = readBit;
        this.setMeansOn = setMeansOn;
        return this;
    }

    private CarProfile query(int cmd, int... data) {
        queryCmd = cmd;
        query = new byte[data.length];
        for (int i = 0; i < data.length; i++) query[i] = (byte) data[i];
        return this;
    }

    static final CarProfile PSA_RAISE = new CarProfile("961", "Raise, PSA and Opel", TOGGLE, 128, new int[]{13, 1}, true);

    private static final CarProfile[] KNOWN = {
            PSA_RAISE,
            // Readbacks and queries read from the decompiled builds, see docs/vendor-profiles.md. 11 has no readback,
            // 880's two builds send opposite values for off, and 964 only presses: none of them is driven.
            new CarProfile("96", "Simple Soft, Binary or LuZheng, PSA", VALUE, 128, new int[]{13, 0}, false)
                    .readback(56, -1, 4, 6, true).query(144, 56, 0),
            new CarProfile("964", "Hiworld, PSA and Opel", PRESS, 140, new int[]{1, 255}, false),
            new CarProfile("114", "Hiworld, VW MQB", VALUE, 74, new int[]{13, 0}, false)
                    .readback(69, -1, 6, 4, true).query(10, 1, 69),
            new CarProfile("111", "Raise, VW MQB", VALUE, 198, new int[]{17, 0}, false)
                    .readback(64, 16, 2, 7, true).query(144, 64, 16),
            new CarProfile("11", "Simple Soft or Binary, VW MQB", VALUE, 198, new int[]{58, 0}, false),
            new CarProfile("274", "Hiworld, Honda", VALUE, 111, new int[]{8, 0}, false)
                    .readback(105, -1, 0, 3, true).query(106, 5, 1, 105),
            new CarProfile("880", "Simple Soft, Jeep", VALUE, 198, new int[]{114, 0}, false),
            new CarProfile("F91", "Raise, GWM Haval", VALUE, 131, new int[]{11, 0}, false)
                    .readback(49, -1, 3, 5, true).query(144, 49, 0),
            // Frame 104 comes unasked, if at all: the vendor screen sends no query for it, so it is not driven.
            new CarProfile("F94", "Hiworld, GWM Haval", VALUE, 109, new int[]{1, 0, 255, 255}, false)
                    .readback(104, -1, 0, 0, true),
    };

    /**
     * Beta: the profile to drive instead of the PSA defaults, or null. Only profiles that take an explicit value and
     * can be asked for the setting back, since their readback is what confirms a switch and a tap waits for it.
     */
    static CarProfile forBeta(String versionName) {
        CarProfile p = of(versionName);
        return p != null && p != PSA_RAISE && p.kind == VALUE && p.frame >= 0 && p.queryCmd >= 0 && p.off.length >= 2
                ? p : null;
    }

    /** The profiles {@link #forBeta} drives, in the order they are listed. */
    static CarProfile[] betaProfiles() {
        java.util.List<CarProfile> out = new java.util.ArrayList<>();
        for (CarProfile p : KNOWN) {
            if (forBeta(p.code) != null) out.add(p);
        }
        return out.toArray(new CarProfile[0]);
    }

    /** Stop-start as this frame reports it: TRUE on, FALSE off, null when the frame is not this profile's readback. */
    Boolean stopStartOn(int id, byte[] data) {
        if (id != frame || data == null || data.length <= readByte) return null;
        if (selector >= 0 && (data[0] & 0xFF) != selector) return null;
        boolean set = (data[readByte] & (1 << readBit)) != 0;
        return set == setMeansOn;
    }

    /** The data that sets stop-start on or off: the off data with its value byte (index 1) flipped for on. */
    byte[] set(boolean on) {
        byte[] d = off.clone();
        if (on) d[1] = (byte) (d[1] == 0 ? 1 : 0);
        return d;
    }

    int queryCmd() {
        return queryCmd;
    }

    byte[] query() {
        return query.clone();
    }

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
