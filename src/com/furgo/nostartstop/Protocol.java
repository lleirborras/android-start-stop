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

    static byte[] set(boolean startStopEnabled, boolean permanentlyClosed) {
        int value = (permanentlyClosed ? 0x80 : 0) | (startStopEnabled ? 1 : 0);
        return new byte[]{(byte) SET_START_STOP, (byte) value};
    }

    static boolean startStopEnabled(byte[] status) {
        return (status[5] & 0x40) != 0;
    }

    static boolean permanentlyClosed(byte[] status) {
        return (status[8] & 0x40) != 0;
    }
}
