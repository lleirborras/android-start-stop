package com.furgo.nostartstop;

import java.util.Arrays;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class ProtocolTest {
    public static void main(String[] args) {
        bytes("query", Protocol.query(), 56, 0);

        bytes("set 0", Protocol.set(0), 13, 0x00);
        bytes("set 1", Protocol.set(1), 13, 0x01);
        bytes("set only keeps bit 0", Protocol.set(0xFF), 13, 0x01);

        echo("all zero", frame(0x00), false);
        echo("echo bit only", frame(0x40), true);
        echo("every other bit set", frame(0xBF), false);
        echo("all bits set", frame(0xFF), true);
        // Frames seen on the van: byte 5 was 0x10 idle and 0x50 after a command with bit 0 = 1.
        echo("van idle", van(0x10), false);
        echo("van after command", van(0x50), true);

        // Neighbouring bytes must not leak into the result.
        byte[] noise = new byte[12];
        Arrays.fill(noise, (byte) 0xFF);
        noise[5] = 0;
        echo("only neighbours set", noise, false);

        toggle("no frame yet", null, 1);
        toggle("frame too short", new byte[Protocol.MIN_STATUS_LENGTH - 1], 1);
        toggle("short frame with echo byte set", new byte[]{0, 0, 0, 0, 0, 0x40}, 1);
        toggle("echo clear", van(0x10), 1);
        toggle("echo set", van(0x50), 0);
        toggle("echo set, neighbours clear", frame(0x40), 0);
        toggle("only neighbours set", noise, 1);

        System.out.println("ProtocolTest passed");
    }

    private static byte[] frame(int byte5) {
        byte[] f = new byte[Protocol.MIN_STATUS_LENGTH];
        f[5] = (byte) byte5;
        return f;
    }

    private static byte[] van(int byte5) {
        byte[] f = {0x00, 0x4A, 0x6B, (byte) 0xF0, (byte) 0x99, 0, (byte) 0xC3, 0x1E, (byte) 0x80};
        f[5] = (byte) byte5;
        return f;
    }

    private static void bytes(String name, byte[] actual, int... expected) {
        byte[] want = new byte[expected.length];
        for (int i = 0; i < expected.length; i++) want[i] = (byte) expected[i];
        if (!Arrays.equals(actual, want)) {
            throw new AssertionError(name + ": expected " + Arrays.toString(want) + " but got " + Arrays.toString(actual));
        }
    }

    private static void echo(String name, byte[] frame, boolean want) {
        if (Protocol.echo(frame) != want) {
            throw new AssertionError(name + ": echo should be " + want);
        }
    }

    private static void toggle(String name, byte[] frame, int want) {
        int got = Protocol.toggleValue(frame);
        if (got != want) {
            throw new AssertionError(name + ": toggleValue should be " + want + " but got " + got);
        }
    }
}
