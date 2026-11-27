package com.furgo.nostartstop;

/**
 * Beta: frame handling for head units without TWUtil, FYT and Microntek. Pure Java so it can be tested. The
 * commands come from vendor firmware read statically and are untested on these units; see
 * ../other-headunits/{fyt-syu,microntek}/FINDINGS.md.
 */
final class OtherUnits {
    /** FYT canbus id (low 16 bits of update code 1000) of PSA with a Raise box, handler with case 18 = 0x80 {13, v}. */
    static final int FYT_PSA_RAISE = 339;
    /** Microntek CAN box type (cfg_canbus) of PSA with a Raise box. */
    static final String MICRONTEK_PSA_RAISE = "50";

    /** Raise wire frame FD, length, command, data, checksum; the checksum is the low byte of the sum after FD. */
    static byte[] fdFrame(int cmd, byte[] data) {
        byte[] f = new byte[data.length + 4];
        f[0] = (byte) 0xFD;
        f[1] = (byte) (data.length + 3);
        f[2] = (byte) cmd;
        System.arraycopy(data, 0, f, 3, data.length);
        int sum = 0;
        for (int i = 1; i < f.length - 1; i++) sum += f[i] & 0xFF;
        f[f.length - 1] = (byte) sum;
        return f;
    }

    /** Microntek's own app sends the status query with one data byte, Topway with two: keep the vendor's form. */
    static byte[] microntekData(int cmd, byte[] data) {
        if (cmd == Protocol.CMD_QUERY && data.length > 1) return new byte[]{data[0]};
        return data;
    }

    /**
     * A Microntek status broadcast, [cmd, len, data...], as its data. Microntek forwards 6 data bytes of the
     * settings frame where Topway passes 9; at least 6 are padded to the Topway length so the status checks accept
     * them, and fewer stay short so they are reported as too short. Null when malformed.
     */
    static byte[] microntekData(byte[] sync) {
        if (sync == null || sync.length < 2) return null;
        int len = Math.min(sync[1] & 0xFF, sync.length - 2);
        byte[] d = new byte[len >= 6 ? Math.max(len, Protocol.MIN_STATUS_LENGTH) : len];
        System.arraycopy(sync, 2, d, 0, len);
        return d;
    }

    /** FYT publishes only the bit (update code 168): a Topway-shaped status frame with it in byte 5, bit 6. */
    static byte[] fytStatusFrame(int bit) {
        byte[] f = new byte[Protocol.MIN_STATUS_LENGTH];
        f[5] = (byte) ((bit & 1) << 6);
        return f;
    }

    /** Why a stop-start command must not be sent for this FYT canbus id, or null when it may. */
    static String fytRefusal(int canbusId) {
        if (canbusId < 0) return "car not identified yet";
        int box = canbusId & 0xFFFF;
        return box == FYT_PSA_RAISE ? null : "canbus id " + box + " is not PSA with a Raise box (" + FYT_PSA_RAISE + ")";
    }

    /** Why a stop-start command must not be sent for this Microntek CAN box type, or null when it may. */
    static String microntekRefusal(String type) {
        if (type == null) return "CAN box type unknown";
        String t = type.trim();
        return t.equals(MICRONTEK_PSA_RAISE) ? null
                : "CAN box type " + t.replaceAll("[^0-9A-Za-z]", "?") + " is not PSA with a Raise box (" + MICRONTEK_PSA_RAISE + ")";
    }
}
