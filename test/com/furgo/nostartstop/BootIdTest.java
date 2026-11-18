package com.furgo.nostartstop;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class BootIdTest {
    public static void main(String[] args) {
        same("boot count wins over the clocks", BootId.of(7, 1_000_000, 5_000), BootId.of(7, 9_999_999, 1_000));
        differ("next boot count", BootId.of(7, 0, 0), BootId.of(8, 0, 0));
        differ("count and time never collide", BootId.of(1, 10_000, 0), BootId.of(-1, 10_000, 0));

        // Without a boot count: the same boot read later, with a little clock drift.
        long boot = 1_700_000_000_000L;
        same("same boot, later", BootId.of(-1, boot + 60_000, 60_000), BootId.of(-1, boot + 3_600_000, 3_600_000));
        same("same boot, 2 s drift", BootId.of(-1, boot + 60_000, 60_000), BootId.of(-1, boot + 62_000, 60_000));
        differ("a boot a minute later", BootId.of(-1, boot + 60_000, 60_000), BootId.of(-1, boot + 120_000, 60_000));

        System.out.println("BootIdTest passed");
    }

    private static void same(String name, String a, String b) {
        if (!a.equals(b)) throw new AssertionError(name + ": expected equal but got " + a + " and " + b);
    }

    private static void differ(String name, String a, String b) {
        if (a.equals(b)) throw new AssertionError(name + ": expected different but both were " + a);
    }
}
