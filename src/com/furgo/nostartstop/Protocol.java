package com.furgo.nostartstop;

/** Raise CAN box frames for the PSA stop-start setting, as used by the vendor app com.tw.car. */
final class Protocol {
    static final int MSG_CANBOX = 1281;
    static final int CMD_SET = 128;
    static final int CMD_QUERY = 143;
    static final int STATUS_SETTINGS = 56;
    static final int MIN_STATUS_LENGTH = 9;

    private static final int SET_START_STOP = 13;

    static byte[] query() {
        return new byte[]{(byte) STATUS_SETTINGS, 0};
    }

    /** Any change of bit 0 flips stop-start on the car. Bit 7 ("permanently closed") has no effect, so it is always 0. */
    static byte[] set(int bit0) {
        return new byte[]{(byte) SET_START_STOP, (byte) (bit0 & 1)};
    }

    /** byte[5] bit 6: repeats bit 0 of the last command and clears by itself. It is not the car's stop-start state. */
    static boolean echo(byte[] status) {
        return (status[5] & 0x40) != 0;
    }

    /** The bit 0 to send so the command differs from the last one: the opposite of the echo, or 1 without a valid frame. */
    static int toggleValue(byte[] lastStatus) {
        if (lastStatus == null || lastStatus.length < MIN_STATUS_LENGTH) return 1;
        return echo(lastStatus) ? 0 : 1;
    }
}
