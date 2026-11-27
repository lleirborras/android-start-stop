package com.furgo.nostartstop;

import java.util.Arrays;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class OtherUnitsTest {
    public static void main(String[] args) {
        // Frames worked out by hand from Microntek's SendCanBusCmdDataFD and FYT's Raise codec.
        bytes("set 1", OtherUnits.fdFrame(0x80, new byte[]{13, 1}), 0xFD, 0x05, 0x80, 0x0D, 0x01, 0x93);
        bytes("set 0", OtherUnits.fdFrame(0x80, new byte[]{13, 0}), 0xFD, 0x05, 0x80, 0x0D, 0x00, 0x92);
        bytes("query", OtherUnits.fdFrame(0x8F, new byte[]{0x38}), 0xFD, 0x04, 0x8F, 0x38, 0xCB);
        bytes("checksum wraps", OtherUnits.fdFrame(0xFF, new byte[]{(byte) 0xFF}), 0xFD, 0x04, 0xFF, 0xFF, 0x02);

        bytes("query trimmed to one byte", OtherUnits.microntekData(Protocol.CMD_QUERY, Protocol.query()), 56);
        bytes("set kept", OtherUnits.microntekData(Protocol.CMD_SET, Protocol.set(1)), 13, 1);

        byte[] sync = {56, 6, 1, 2, 3, 4, 5, 0x40};
        byte[] d = OtherUnits.microntekData(sync);
        check("sync padded", d.length, Protocol.MIN_STATUS_LENGTH);
        check("sync echo", Protocol.echo(d), true);
        check("sync data", d[0], (byte) 1);
        check("sync length beyond array", OtherUnits.microntekData(new byte[]{56, 50, 0x40}).length, 1);
        check("sync too short", OtherUnits.microntekData(new byte[]{56}), null);
        check("five data bytes stay short", OtherUnits.microntekData(new byte[]{56, 5, 1, 2, 3, 4, 0x40}).length, 5);

        check("fyt echo on", Protocol.echo(OtherUnits.fytStatusFrame(1)), true);
        check("fyt echo off", Protocol.echo(OtherUnits.fytStatusFrame(0)), false);
        check("fyt toggles from echo", Protocol.toggleValue(OtherUnits.fytStatusFrame(1)), 0);

        check("fyt 2023 build", OtherUnits.fytStatusCode("2.23.1117.1115"), 164);
        check("fyt 2025 build", OtherUnits.fytStatusCode("25.0510"), 168);
        check("fyt 2026 build", OtherUnits.fytStatusCode("26.1015"), 168);
        check("fyt other build", OtherUnits.fytStatusCode("2.24.0101"), -1);
        check("fyt 2.23 without the dot", OtherUnits.fytStatusCode("2.230"), -1);
        check("fyt 27 build", OtherUnits.fytStatusCode("27.0101"), -1);
        check("fyt no version", OtherUnits.fytStatusCode(null), -1);

        check("fyt psa raise", OtherUnits.fytRefusal(339), null);
        check("fyt psa raise variant", OtherUnits.fytRefusal((5 << 16) | 339), null);
        check("fyt other car", OtherUnits.fytRefusal(281), "canbus id 281 is not PSA with a Raise box (339)");
        check("fyt unknown", OtherUnits.fytRefusal(-1), "car not identified yet");
        check("mtc psa raise", OtherUnits.microntekRefusal("50"), null);
        check("mtc psa raise spaces", OtherUnits.microntekRefusal(" 50\n"), null);
        check("mtc hiworld", OtherUnits.microntekRefusal("59"), "CAN box type 59 is not PSA with a Raise box (50)");
        check("mtc unknown", OtherUnits.microntekRefusal(null), "CAN box type unknown");
        System.out.println("OtherUnitsTest passed");
    }

    private static void bytes(String name, byte[] got, int... want) {
        byte[] w = new byte[want.length];
        for (int i = 0; i < want.length; i++) w[i] = (byte) want[i];
        if (!Arrays.equals(got, w)) {
            System.err.println(name + ": expected " + Arrays.toString(w) + " but got " + Arrays.toString(got));
            System.exit(1);
        }
    }

    private static void check(String name, Object got, Object want) {
        if (got == null ? want != null : !got.equals(want)) {
            System.err.println(name + ": expected " + want + " but got " + got);
            System.exit(1);
        }
    }
}
