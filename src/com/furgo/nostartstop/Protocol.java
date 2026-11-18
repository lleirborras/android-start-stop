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

    // Everything below comes from decompiled vendor code and is not verified on the van; it is only logged.
    static final int STATUS_FUNCTION = 57;
    static final int STATUS_TRIP = 51;
    static final int MSG_ENGINE = 1291;
    static final int MSG_POWER = 1292;
    static final int MSG_UNIT_ID = 266;
    static final int MSG_UNIT_CONFIG = 267;
    static final int RPM_INVALID = 65535;

    static byte[] query(int frameId) {
        return new byte[]{(byte) frameId, 0};
    }

    /** Candidate stop-start state in frame 57: byte[2] bits 1-0 (1 enabled, 2 disabled, 0 not reported), or -1 if too short. */
    static int stopStartField(byte[] function) {
        return function == null || function.length < 3 ? -1 : function[2] & 0x03;
    }

    /** Battery voltage in hundredths of a volt from message 1292 with arg1 0, or -1 when absent or flagged invalid (bit 7). */
    static int batteryCentivolts(byte[] b) {
        if (b == null || b.length < 2 || (b[0] & 0x80) != 0) return -1;
        return (b[0] & 0x7F) << 8 | (b[1] & 0xFF);
    }

    /** Message 1291 arg2, or -1 when the unit reports it invalid. */
    static int rpm(int arg2) {
        return arg2 == RPM_INVALID || arg2 < 0 ? -1 : arg2;
    }

    /** Worth a new engine line: nothing logged yet, the engine stopped or started, or a large enough change. */
    static boolean engineChanged(boolean logged, int lastRpm, int lastSpeed, int rpm, int speed) {
        if (!logged) return true;
        if ((lastRpm < 0) != (rpm < 0) || (lastRpm == 0) != (rpm == 0)) return true;
        return Math.abs(rpm - lastRpm) >= 300 || Math.abs(speed - lastSpeed) >= 10;
    }

    /** Worth a new battery line: nothing logged yet, or at least 0.2 V of change. */
    static boolean batteryChanged(int lastCentivolts, int centivolts) {
        return lastCentivolts < 0 || Math.abs(centivolts - lastCentivolts) >= 20;
    }

    static String volts(int centivolts) {
        return centivolts / 100 + "." + (centivolts % 100 < 10 ? "0" : "") + centivolts % 100;
    }
}
