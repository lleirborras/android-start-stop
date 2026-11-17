package com.furgo.nostartstop;

import java.util.Arrays;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class ProtocolTest {
    public static void main(String[] args) {
        bytes("query", Protocol.query(), 56, 0);

        bytes("set off, controllable", Protocol.set(false, false), 13, 0x00);
        bytes("set on, controllable", Protocol.set(true, false), 13, 0x01);
        bytes("set off, closed", Protocol.set(false, true), 13, 0x80);
        bytes("set on, closed", Protocol.set(true, true), 13, 0x81);

        status("all zero", frame(0x00, 0x00), false, false);
        status("enabled bit only", frame(0x40, 0x00), true, false);
        status("closed bit only", frame(0x00, 0x40), false, true);
        status("both bits", frame(0x40, 0x40), true, true);
        status("every other bit set", frame(0xBF, 0xBF), false, false);
        status("all bits set", frame(0xFF, 0xFF), true, true);

        // Neighbouring bytes must not leak into the result.
        byte[] noise = new byte[12];
        Arrays.fill(noise, (byte) 0xFF);
        noise[5] = 0;
        noise[8] = 0;
        status("only neighbours set", noise, false, false);

        System.out.println("ProtocolTest passed");
    }

    private static byte[] frame(int byte5, int byte8) {
        byte[] f = new byte[Protocol.MIN_STATUS_LENGTH];
        f[5] = (byte) byte5;
        f[8] = (byte) byte8;
        return f;
    }

    private static void bytes(String name, byte[] actual, int... expected) {
        byte[] want = new byte[expected.length];
        for (int i = 0; i < expected.length; i++) want[i] = (byte) expected[i];
        if (!Arrays.equals(actual, want)) {
            throw new AssertionError(name + ": expected " + Arrays.toString(want) + " but got " + Arrays.toString(actual));
        }
    }

    private static void status(String name, byte[] frame, boolean enabled, boolean closed) {
        if (Protocol.startStopEnabled(frame) != enabled) {
            throw new AssertionError(name + ": startStopEnabled should be " + enabled);
        }
        if (Protocol.permanentlyClosed(frame) != closed) {
            throw new AssertionError(name + ": permanentlyClosed should be " + closed);
        }
    }
}
