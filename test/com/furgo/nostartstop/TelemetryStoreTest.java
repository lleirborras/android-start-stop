package com.furgo.nostartstop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class TelemetryStoreTest {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    public static void main(String[] args) throws IOException {
        days();
        order();
        pruning();
        trimming();
        chunking();

        File root = Files.createTempDirectory("telemetry-test").toFile();
        try {
            rotation(new File(root, "rotation"));
            partialSend(new File(root, "partial"));
            corrupt(new File(root, "corrupt"));
            limits(new File(root, "limits"));
        } finally {
            deleteTree(root);
        }
        System.out.println("TelemetryStoreTest passed");
    }

    private static void days() {
        check("epoch", TelemetryStore.day(0).equals("1970-01-01"));
        check("last ms of a day", TelemetryStore.day(DAY_MS - 1).equals("1970-01-01"));
        check("next day", TelemetryStore.day(DAY_MS).equals("1970-01-02"));
        check("2026", TelemetryStore.day(1_795_000_000_000L).equals("2026-11-18"));
        check("before 1970 clamps", TelemetryStore.day(-5 * DAY_MS).equals("1970-01-01"));
        check("far future clamps", TelemetryStore.day(Long.MAX_VALUE).equals("9999-12-31"));
        check("timestamp day", TelemetryStore.dayOfTimestamp("2025-03-04T05:06:07Z", "x").equals("2025-03-04"));
        check("bad timestamp", TelemetryStore.dayOfTimestamp("yesterday", "x").equals("x"));
        check("empty timestamp", TelemetryStore.dayOfTimestamp("", "x").equals("x"));
        check("null timestamp", TelemetryStore.dayOfTimestamp(null, "x").equals("x"));
        check("plain name", "2026-01-02".equals(TelemetryStore.dayOf("2026-01-02")));
        check("gz name", "2026-01-02".equals(TelemetryStore.dayOf("2026-01-02.gz")));
        check("tmp is no day", TelemetryStore.dayOf("2026-01-02.gz.tmp") == null);
        check("old queue is no day", TelemetryStore.dayOf("telemetry-queue") == null);
    }

    private static void order() {
        List<String> got = TelemetryStore.uploadOrder(Arrays.asList(
                "2026-11-17.gz", "2026-11-18", "x.tmp", "1970-01-01.gz", "2026-11-18.gz", "2099-01-01.gz", "2026-01-05.gz"),
                "2026-11-18");
        check("today first, then oldest to newest: " + got, got.equals(Arrays.asList(
                "2026-11-18", "1970-01-01.gz", "2026-01-05.gz", "2026-11-17.gz", "2026-11-18.gz", "2099-01-01.gz")));
        check("no file today", TelemetryStore.uploadOrder(Arrays.asList("2026-11-02.gz", "2026-11-01.gz"), "2026-11-18")
                .equals(Arrays.asList("2026-11-01.gz", "2026-11-02.gz")));
    }

    private static void pruning() {
        Map<String, Long> sizes = new HashMap<String, Long>();
        sizes.put("2026-11-18", 10L);
        sizes.put("2026-11-17.gz", 10L);
        sizes.put("2026-11-15.gz", 10L);
        sizes.put("2026-11-16.gz", 10L);
        check("within limits", TelemetryStore.prune(sizes, "2026-11-18", 4, 40).isEmpty());
        check("too many files", TelemetryStore.prune(sizes, "2026-11-18", 3, 40)
                .equals(Collections.singletonList("2026-11-15.gz")));
        check("too many bytes", TelemetryStore.prune(sizes, "2026-11-18", 30, 25)
                .equals(Arrays.asList("2026-11-15.gz", "2026-11-16.gz")));
        check("today kept even when alone over", TelemetryStore.prune(sizes, "2026-11-18", 30, 5)
                .equals(Arrays.asList("2026-11-15.gz", "2026-11-16.gz", "2026-11-17.gz")));
        // Clock set back to 1970: today sorts first but is still kept.
        sizes.put("1970-01-01", 10L);
        check("today oldest by name", TelemetryStore.prune(sizes, "1970-01-01", 2, 1000)
                .equals(Arrays.asList("2026-11-15.gz", "2026-11-16.gz", "2026-11-17.gz")));
    }

    private static void trimming() {
        List<String> lines = Arrays.asList("aaaa", "bbbb", "cccc");   // 5 bytes each with the newline
        check("fits", TelemetryStore.dropCount(lines, 15) == 0);
        check("one over", TelemetryStore.dropCount(lines, 14) == 1);
        check("only the newest fits", TelemetryStore.dropCount(lines, 5) == 2);
        check("nothing fits", TelemetryStore.dropCount(lines, 4) == 3);
        check("utf-8 bytes", TelemetryStore.dropCount(Arrays.asList("é", "x"), 3) == 1);
    }

    private static void chunking() {
        List<Integer> items = new ArrayList<Integer>();
        for (int i = 0; i < 1001; i++) items.add(i);
        List<List<Integer>> chunks = TelemetryStore.chunks(items, 500);
        check("three chunks", chunks.size() == 3);
        check("sizes", chunks.get(0).size() == 500 && chunks.get(1).size() == 500 && chunks.get(2).size() == 1);
        check("order", chunks.get(1).get(0) == 500 && chunks.get(2).get(0) == 1000);
        check("exact multiple", TelemetryStore.chunks(items.subList(0, 500), 500).size() == 1);
        check("empty", TelemetryStore.chunks(new ArrayList<Integer>(), 500).isEmpty());
    }

    private static void rotation(File dir) throws IOException {
        TelemetryStore s = new TelemetryStore(dir, 30, 1 << 20);
        s.append("2026-11-17", "{\"a\":1}");
        s.append("2026-11-17", "{\"a\":2}");
        check("nothing to rotate on the same day", s.rotate("2026-11-17").isEmpty());
        check("plain while current", new File(dir, "2026-11-17").isFile());

        // First write of a later day.
        check("rotated cleanly", s.rotate("2026-11-18").isEmpty());
        s.append("2026-11-18", "{\"b\":1}");
        check("old plain removed", !new File(dir, "2026-11-17").exists());
        check("compressed", new File(dir, "2026-11-17.gz").isFile());
        check("really gzip", firstBytes(new File(dir, "2026-11-17.gz"))[0] == 0x1f);
        check("gzip round trip", Arrays.asList("{\"a\":1}", "{\"a\":2}").equals(s.read("2026-11-17.gz")));
        check("no temp file left", new File(dir, "2026-11-17.gz.tmp").exists() == false);

        // A late line for a finished day (clock change) is merged behind the compressed lines.
        s.append("2026-11-17", "{\"a\":3}");
        s.rotate("2026-11-18");
        check("merged", Arrays.asList("{\"a\":1}", "{\"a\":2}", "{\"a\":3}").equals(s.read("2026-11-17.gz")));
        check("today untouched", Collections.singletonList("{\"b\":1}").equals(s.read("2026-11-18")));

        // A line cut short by a power loss does not swallow the next one.
        FileOutputStream out = new FileOutputStream(new File(dir, "2026-11-18"), true);
        out.write("{\"cut".getBytes("UTF-8"));
        out.close();
        s.append("2026-11-18", "{\"b\":2}");
        check("cut line kept apart", Arrays.asList("{\"b\":1}", "{\"cut", "{\"b\":2}").equals(s.read("2026-11-18")));

        s.deleteAll();
        check("deleteAll removes the directory", !dir.exists());
    }

    private static void partialSend(File dir) throws IOException {
        TelemetryStore s = new TelemetryStore(dir, 30, 1 << 20);
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < 1200; i++) lines.add("{\"n\":" + i + "}");
        s.write("2026-11-10.gz", lines);
        List<List<String>> chunks = TelemetryStore.chunks(s.read("2026-11-10.gz"), 500);
        // First chunk sent, second failed: the rest is rewritten, compressed.
        s.write("2026-11-10.gz", lines.subList(chunks.get(0).size(), lines.size()));
        List<String> left = s.read("2026-11-10.gz");
        check("remainder kept", left.size() == 700 && left.get(0).equals("{\"n\":500}") && left.get(699).equals("{\"n\":1199}"));
        // All sent: the file goes.
        s.write("2026-11-10.gz", new ArrayList<String>());
        check("fully sent file deleted", !new File(dir, "2026-11-10.gz").exists());

        s.append("2026-11-18", "{\"t\":1}");
        s.append("2026-11-18", "{\"t\":2}");
        s.write("2026-11-18", s.read("2026-11-18").subList(1, 2));
        check("today rewritten without sent lines", Collections.singletonList("{\"t\":2}").equals(s.read("2026-11-18")));
        check("today stays plain", firstBytes(new File(dir, "2026-11-18"))[0] == '{');
    }

    private static void corrupt(File dir) throws IOException {
        TelemetryStore s = new TelemetryStore(dir, 30, 1 << 20);
        dir.mkdirs();
        FileOutputStream out = new FileOutputStream(new File(dir, "2026-11-01.gz"));
        out.write("not gzip at all".getBytes("UTF-8"));
        out.close();
        check("bad gzip unreadable", s.read("2026-11-01.gz") == null);
        check("bad gzip deleted", !new File(dir, "2026-11-01.gz").exists());

        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < 2000; i++) lines.add("{\"n\":" + i + ",\"pad\":\"" + i * 7919 + "\"}");
        s.write("2026-11-02.gz", lines);
        File gz = new File(dir, "2026-11-02.gz");
        byte[] whole = Files.readAllBytes(gz.toPath());
        Files.write(gz.toPath(), Arrays.copyOf(whole, whole.length / 2));
        check("truncated gzip unreadable", s.read("2026-11-02.gz") == null);
        check("truncated gzip deleted", !gz.exists());

        // A bad compressed file next to a plain one for the same day: the plain lines still get compressed.
        out = new FileOutputStream(new File(dir, "2026-11-03.gz"));
        out.write(new byte[] {0x1f, (byte) 0x8b, 8, 0});
        out.close();
        s.append("2026-11-03", "{\"late\":1}");
        List<String> dropped = s.rotate("2026-11-18");
        check("dropped reported", dropped.equals(Collections.singletonList("2026-11-03.gz")));
        check("plain lines survive", Collections.singletonList("{\"late\":1}").equals(s.read("2026-11-03.gz")));
        s.deleteAll();
    }

    private static void limits(File dir) throws IOException {
        TelemetryStore s = new TelemetryStore(dir, 3, 100);
        for (int d = 1; d <= 5; d++) s.write("2026-11-0" + d + ".gz", Collections.singletonList("{\"d\":" + d + "}"));
        s.append("2026-11-06", "{\"today\":1}");
        new FileOutputStream(new File(dir, "2026-11-05.gz.tmp")).close();
        s.enforceLimits("2026-11-06");
        List<String> names = s.names();
        Collections.sort(names);
        check("oldest pruned to 3 files: " + names,
                names.equals(Arrays.asList("2026-11-04.gz", "2026-11-05.gz", "2026-11-06")));
        check("stray temp file removed", !new File(dir, "2026-11-05.gz.tmp").exists());

        // Today alone over the byte limit: older days go, then today's oldest lines.
        for (int i = 0; i < 20; i++) s.append("2026-11-06", "{\"line\":" + (100 + i) + "}");
        s.enforceLimits("2026-11-06");
        names = s.names();
        check("only today left: " + names, names.equals(Collections.singletonList("2026-11-06")));
        List<String> today = s.read("2026-11-06");
        check("today trimmed", new File(dir, "2026-11-06").length() <= 75);
        check("newest line kept", today.get(today.size() - 1).equals("{\"line\":119}"));
        check("oldest line dropped", !today.contains("{\"today\":1}"));
        s.deleteAll();
    }

    private static byte[] firstBytes(File f) throws IOException {
        return Arrays.copyOf(Files.readAllBytes(f.toPath()), 1);
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteTree(c);
        }
        f.delete();
    }

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
    }
}
