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

        bytes("query function", Protocol.query(57), 57, 0);
        check("frame 51 left out", Protocol.noisyFrame(51), true);
        check("frame 81 left out", Protocol.noisyFrame(81), true);
        check("frame 125 left out", Protocol.noisyFrame(125), true);
        check("frame 52 kept", Protocol.noisyFrame(52), false);

        same("field enabled", Protocol.stopStartField(new byte[]{0x05, 0x12, 0x01, 0x01}), 1);
        same("field disabled", Protocol.stopStartField(new byte[]{0x05, 0x12, 0x02}), 2);
        same("field not reported", Protocol.stopStartField(new byte[]{0x05, 0x12, 0x00}), 0);
        same("field ignores high bits", Protocol.stopStartField(new byte[]{0, 0, (byte) 0xFE}), 2);
        same("field too short", Protocol.stopStartField(new byte[]{0x05, 0x12}), -1);
        same("field empty", Protocol.stopStartField(new byte[0]), -1);
        same("field null", Protocol.stopStartField(null), -1);

        same("battery 12.34 V", Protocol.batteryCentivolts(new byte[]{0x04, (byte) 0xD2}), 1234);
        same("battery 14.40 V", Protocol.batteryCentivolts(new byte[]{0x05, (byte) 0xA0, 0x77}), 1440);
        same("battery invalid flag", Protocol.batteryCentivolts(new byte[]{(byte) 0x84, (byte) 0xD2}), -1);
        same("battery too short", Protocol.batteryCentivolts(new byte[]{0x04}), -1);
        same("battery null", Protocol.batteryCentivolts(null), -1);

        same("rpm idle", Protocol.rpm(780), 780);
        same("rpm stopped", Protocol.rpm(0), 0);
        same("rpm invalid", Protocol.rpm(65535), -1);
        same("rpm negative", Protocol.rpm(-5), -1);

        check("engine first line", Protocol.engineChanged(false, 0, 0, 800, 0), true);
        check("engine small change", Protocol.engineChanged(true, 800, 0, 900, 5), false);
        check("engine rpm change", Protocol.engineChanged(true, 800, 0, 1100, 0), true);
        check("engine stopped", Protocol.engineChanged(true, 120, 0, 0, 0), true);
        check("engine started", Protocol.engineChanged(true, 0, 0, 150, 0), true);
        check("engine rpm became invalid", Protocol.engineChanged(true, 800, 0, -1, 0), true);
        check("engine still invalid", Protocol.engineChanged(true, -1, 30, -1, 35), false);
        check("engine speed change", Protocol.engineChanged(true, 1500, 40, 1500, 50), true);

        check("battery first line", Protocol.batteryChanged(-1, 1234), true);
        check("battery small change", Protocol.batteryChanged(1234, 1250), false);
        check("battery charging", Protocol.batteryChanged(1240, 1440), true);
        check("battery drop", Protocol.batteryChanged(1240, 1220), true);

        text("volts", Protocol.volts(1234), "12.34");
        text("volts with leading zero", Protocol.volts(1205), "12.05");
        text("volts round", Protocol.volts(1400), "14.00");

        System.out.println("ProtocolTest passed");
    }

    private static void same(String name, int got, int want) {
        if (got != want) throw new AssertionError(name + ": expected " + want + " but got " + got);
    }

    private static void check(String name, boolean got, boolean want) {
        if (got != want) throw new AssertionError(name + ": expected " + want);
    }

    private static void text(String name, String got, String want) {
        if (!want.equals(got)) throw new AssertionError(name + ": expected " + want + " but got " + got);
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
