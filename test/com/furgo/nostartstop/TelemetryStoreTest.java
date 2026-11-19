package com.furgo.nostartstop;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Plain-Java test, no test framework: run by test.sh, exits non-zero on the first failure. */
public class TelemetryStoreTest {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final String TODAY = "2026-11-18";
    private static final List<String> WARNINGS = new ArrayList<String>();
    private static File root;

    public static void main(String[] args) throws IOException {
        root = Files.createTempDirectory("telemetry-test").toFile();
        try {
            days();
            order();
            pruning();
            outcomes();
            backoff();
            rotation();
            tidyOnlyWhenNeeded();
            cutLine();
            sendOrderAndChunks();
            partialFailure();
            rejectedChunk();
            requestCap();
            optOut();
            failedRewrite();
            damagedFiles();
            unreadableIsSkipped();
            readErrorAfterSending();
            limits();
            mergedDayCap();
        } finally {
            deleteTree(root);
        }
        System.out.println("TelemetryStoreTest passed");
    }

    private static void days() {
        TelemetryStore s = store("days", new TelemetryStore.Limits());
        check("epoch", s.day(0).equals("1970-01-01"));
        check("last ms of a day", s.day(DAY_MS - 1).equals("1970-01-01"));
        check("next day", s.day(DAY_MS).equals("1970-01-02"));
        check("2026", s.day(1_795_000_000_000L).equals(TODAY));
        check("before 1970 clamps", s.day(-5 * DAY_MS).equals("1970-01-01"));
        check("far future clamps", s.day(Long.MAX_VALUE).equals("9999-12-31"));
        check("timestamp", s.timestamp(DAY_MS + 1000).equals("1970-01-02T00:00:01Z"));
        check("timestamp before 1970 clamps", s.timestamp(-1).equals("1970-01-01T00:00:00Z"));
        check("timestamp far future clamps", s.timestamp(Long.MAX_VALUE).equals("9999-12-31T23:59:59Z"));
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
                "2026-11-17.gz", TODAY, "x.tmp", "1970-01-01.gz", "2026-11-18.gz", "2099-01-01.gz", "2026-01-05.gz"), TODAY);
        check("today first, then oldest to newest: " + got, got.equals(Arrays.asList(
                TODAY, "1970-01-01.gz", "2026-01-05.gz", "2026-11-17.gz", "2026-11-18.gz", "2099-01-01.gz")));
        check("no file today", TelemetryStore.uploadOrder(Arrays.asList("2026-11-02.gz", "2026-11-01.gz"), TODAY)
                .equals(Arrays.asList("2026-11-01.gz", "2026-11-02.gz")));
    }

    private static void pruning() {
        Map<String, Long> sizes = new HashMap<String, Long>();
        sizes.put(TODAY, 10L);
        sizes.put("2026-11-17.gz", 10L);
        sizes.put("2026-11-15.gz", 10L);
        sizes.put("2026-11-16.gz", 10L);
        check("within limits", TelemetryStore.prune(sizes, TODAY, 4, 40).isEmpty());
        check("too many files", TelemetryStore.prune(sizes, TODAY, 3, 40)
                .equals(Collections.singletonList("2026-11-15.gz")));
        check("too many bytes", TelemetryStore.prune(sizes, TODAY, 30, 25)
                .equals(Arrays.asList("2026-11-15.gz", "2026-11-16.gz")));
        check("today kept even when alone over", TelemetryStore.prune(sizes, TODAY, 30, 5)
                .equals(Arrays.asList("2026-11-15.gz", "2026-11-16.gz", "2026-11-17.gz")));
        // Clock set back to 1970: today sorts first but is still kept.
        sizes.put("1970-01-01", 10L);
        check("today oldest by name", TelemetryStore.prune(sizes, "1970-01-01", 2, 1000)
                .equals(Arrays.asList("2026-11-15.gz", "2026-11-16.gz", "2026-11-17.gz")));
    }

    private static void outcomes() {
        check("200", TelemetryStore.outcome(200) == TelemetryStore.Outcome.SENT);
        check("204", TelemetryStore.outcome(204) == TelemetryStore.Outcome.SENT);
        check("400", TelemetryStore.outcome(400) == TelemetryStore.Outcome.REJECTED);
        check("413", TelemetryStore.outcome(413) == TelemetryStore.Outcome.REJECTED);
        check("422", TelemetryStore.outcome(422) == TelemetryStore.Outcome.REJECTED);
        check("401", TelemetryStore.outcome(401) == TelemetryStore.Outcome.FAILED);
        check("403", TelemetryStore.outcome(403) == TelemetryStore.Outcome.FAILED);
        check("404", TelemetryStore.outcome(404) == TelemetryStore.Outcome.FAILED);
        check("408", TelemetryStore.outcome(408) == TelemetryStore.Outcome.FAILED);
        check("429", TelemetryStore.outcome(429) == TelemetryStore.Outcome.FAILED);
        check("500", TelemetryStore.outcome(500) == TelemetryStore.Outcome.FAILED);
        check("503", TelemetryStore.outcome(503) == TelemetryStore.Outcome.FAILED);
        check("302", TelemetryStore.outcome(302) == TelemetryStore.Outcome.FAILED);
        check("-1", TelemetryStore.outcome(-1) == TelemetryStore.Outcome.FAILED);
    }

    private static void backoff() {
        long b = TelemetryStore.nextBackoff(0);
        check("starts at 1 min", b == 60_000);
        check("doubles", TelemetryStore.nextBackoff(b) == 120_000);
        for (int i = 0; i < 10; i++) b = TelemetryStore.nextBackoff(b);
        check("caps at 15 min", b == 15 * 60_000);
    }

    private static void rotation() throws IOException {
        TelemetryStore s = store("rotation", new TelemetryStore.Limits());
        File dir = new File(root, "rotation");
        s.append("2026-11-17", "{\"a\":1}");
        s.append("2026-11-17", "{\"a\":2}");
        check("plain while current", new File(dir, "2026-11-17").isFile());

        // First write of a later day.
        s.append(TODAY, "{\"b\":1}");
        check("old plain removed", !new File(dir, "2026-11-17").exists());
        File gz = new File(dir, "2026-11-17.gz");
        check("really gzip", Files.readAllBytes(gz.toPath())[0] == 0x1f);
        check("gzip round trip", Arrays.asList("{\"a\":1}", "{\"a\":2}").equals(lines(gz)));
        check("no temp file left", tmpFiles(dir).isEmpty());

        // A late line for a finished day (clock change) is merged behind the compressed lines.
        s.add("2026-11-17", "{\"a\":3}");
        s.rotate(TODAY);
        check("merged", Arrays.asList("{\"a\":1}", "{\"a\":2}", "{\"a\":3}").equals(lines(gz)));
        check("today untouched", Collections.singletonList("{\"b\":1}").equals(lines(new File(dir, TODAY))));
        check("count, plain and compressed", s.count() == 4);
        check("days", s.days() == 2);

        s.deleteAll();
        check("deleteAll removes the directory", !dir.exists());
        check("count of nothing", s.count() == 0);
        check("no days", s.days() == 0);
    }

    private static void tidyOnlyWhenNeeded() throws IOException {
        TelemetryStore.Limits l = new TelemetryStore.Limits();
        l.tidyEveryBytes = 100;
        TelemetryStore s = store("tidy", l);
        File dir = new File(root, "tidy");
        s.append(TODAY, "{\"first\":1}");   // first append tidies
        s.add("2026-11-10", "{\"old\":1}");
        s.append(TODAY, "{\"x\":1}");
        check("same day, few bytes: not rotated", new File(dir, "2026-11-10").exists());
        for (int i = 0; i < 10; i++) s.append(TODAY, "{\"padding\":" + i + "}");
        check("rotated after enough bytes", !new File(dir, "2026-11-10").exists()
                && new File(dir, "2026-11-10.gz").exists());
        s.add("2026-11-11", "{\"old\":2}");
        s.append("2026-11-19", "{\"tomorrow\":1}");
        check("rotated on a day change", new File(dir, "2026-11-11.gz").exists() && new File(dir, TODAY + ".gz").exists());
    }

    private static void cutLine() throws IOException {
        TelemetryStore s = store("cut", new TelemetryStore.Limits());
        File f = new File(new File(root, "cut"), TODAY);
        s.append(TODAY, "{\"b\":1}");
        OutputStream out = new FileOutputStream(f, true);
        out.write("{\"cut".getBytes("UTF-8"));
        out.close();
        s.append(TODAY, "{\"b\":2}");
        check("cut line kept apart", Arrays.asList("{\"b\":1}", "{\"cut", "{\"b\":2}").equals(lines(f)));
    }

    private static void sendOrderAndChunks() throws IOException {
        TelemetryStore s = store("order-send", new TelemetryStore.Limits());
        File dir = new File(root, "order-send");
        writeGz(new File(dir, "2026-11-10.gz"), events("ten", 1200));
        writeGz(new File(dir, "2026-11-05.gz"), events("five", 2));
        for (String e : events("today", 3)) s.add(TODAY, e);
        FakeSender sender = new FakeSender();
        int sent = s.send(sender, TODAY);
        check("all sent: " + sent, sent == 1205);
        check("today first, then oldest: " + sender.sizes(), sender.sizes().equals(Arrays.asList(3, 2, 500, 500, 200)));
        check("today's lines", sender.chunks.get(0).get(0).equals("{\"today\":0}"));
        check("then the 5th", sender.chunks.get(1).get(0).equals("{\"five\":0}"));
        check("chunks in order", sender.chunks.get(3).get(0).equals("{\"ten\":500}"));
        check("all files gone", s.names().isEmpty());
    }

    private static void partialFailure() throws IOException {
        TelemetryStore s = store("partial", new TelemetryStore.Limits());
        File dir = new File(root, "partial");
        writeGz(new File(dir, "2026-11-10.gz"), events("ten", 1200));
        writeGz(new File(dir, "2026-11-11.gz"), events("eleven", 5));
        for (String e : events("today", 3)) s.add(TODAY, e);
        FakeSender sender = new FakeSender(TelemetryStore.Outcome.SENT, TelemetryStore.Outcome.SENT,
                TelemetryStore.Outcome.FAILED);
        WARNINGS.clear();
        check("failure returns -1", s.send(sender, TODAY) == -1);
        check("sent count logged", WARNINGS.contains("sent 503 lines before a failure"));
        check("stopped at the failure", sender.chunks.size() == 3);
        check("today gone", !new File(dir, TODAY).exists());
        List<String> left = lines(new File(dir, "2026-11-10.gz"));
        check("remainder kept compressed: " + left.size(), left.size() == 700 && left.get(0).equals("{\"ten\":500}")
                && left.get(699).equals("{\"ten\":1199}"));
        check("later day untouched", lines(new File(dir, "2026-11-11.gz")).size() == 5);

        FakeSender retry = new FakeSender();
        check("next attempt sends the rest", s.send(retry, TODAY) == 705);
        check("starting where it stopped", retry.chunks.get(0).get(0).equals("{\"ten\":500}"));

        // A failure on the first chunk of today's file leaves it as it was.
        for (String e : events("t", 3)) s.add(TODAY, e);
        check("failed at once", s.send(new FakeSender(TelemetryStore.Outcome.FAILED), TODAY) == -1);
        check("today kept plain", lines(new File(dir, TODAY)).equals(events("t", 3))
                && Files.readAllBytes(new File(dir, TODAY).toPath())[0] == '{');
    }

    private static void rejectedChunk() throws IOException {
        TelemetryStore.Limits l = new TelemetryStore.Limits();
        l.maxPerRequest = 2;
        TelemetryStore s = store("rejected", l);
        File dir = new File(root, "rejected");
        for (String e : events("today", 5)) s.add(TODAY, e);
        FakeSender sender = new FakeSender(TelemetryStore.Outcome.SENT, TelemetryStore.Outcome.REJECTED,
                TelemetryStore.Outcome.SENT);
        check("rejected lines not counted", s.send(sender, TODAY) == 3);
        check("went on after the rejection", sender.chunks.size() == 3);
        check("rejected lines dropped", !new File(dir, TODAY).exists());
    }

    private static void requestCap() throws IOException {
        TelemetryStore.Limits l = new TelemetryStore.Limits();
        l.maxPerRequest = 10;
        l.maxRequests = 20;
        TelemetryStore s = store("cap", l);
        File dir = new File(root, "cap");
        for (String e : events("today", 15)) s.add(TODAY, e);
        writeGz(new File(dir, "2026-11-01.gz"), events("one", 250));
        writeGz(new File(dir, "2026-11-02.gz"), events("two", 5));
        FakeSender sender = new FakeSender();
        check("cap is not a failure", s.send(sender, TODAY) == 195);
        check("20 requests", sender.chunks.size() == 20);
        List<String> left = lines(new File(dir, "2026-11-01.gz"));
        check("rest kept for later: " + left.size(), left.size() == 70 && left.get(0).equals("{\"one\":180}"));
        check("untouched day kept", lines(new File(dir, "2026-11-02.gz")).size() == 5);
    }

    private static void optOut() throws IOException {
        TelemetryStore.Limits l = new TelemetryStore.Limits();
        l.maxPerRequest = 2;
        TelemetryStore s = store("optout", l);
        File dir = new File(root, "optout");
        for (String e : events("today", 6)) s.add(TODAY, e);
        writeGz(new File(dir, "2026-11-01.gz"), events("one", 4));
        FakeSender sender = new FakeSender();
        sender.enabledFor = 2;
        check("opt-out returns 0", s.send(sender, TODAY) == 0);
        check("no request after the opt-out", sender.chunks.size() == 2);
        check("everything deleted", !dir.exists());
    }

    private static void failedRewrite() throws IOException {
        TelemetryStore.Limits l = new TelemetryStore.Limits();
        l.maxPerRequest = 2;
        TelemetryStore s = store("rewrite", l);
        File dir = new File(root, "rewrite");
        writeGz(new File(dir, "2026-11-01.gz"), events("one", 6));
        // The temporary file cannot be created where a directory is in the way.
        File blocker = new File(dir, "2026-11-01.gz.tmp");
        check("blocker", blocker.mkdirs() && new File(blocker, "x").createNewFile());
        FakeSender sender = new FakeSender(TelemetryStore.Outcome.SENT, TelemetryStore.Outcome.FAILED);
        check("failed", s.send(sender, "2026-11-02") == -1);
        check("file dropped rather than sent twice", !new File(dir, "2026-11-01.gz").exists());
    }

    private static void damagedFiles() throws IOException {
        TelemetryStore s = store("damaged", new TelemetryStore.Limits());
        File dir = new File(root, "damaged");
        dir.mkdirs();
        write(new File(dir, "2026-11-01.gz"), "not gzip at all".getBytes("UTF-8"));
        write(new File(dir, "2026-11-02.gz"), new byte[] {0x1f, (byte) 0x8b, 8, 0});
        File truncated = new File(dir, "2026-11-03.gz");
        List<String> many = new ArrayList<String>();
        for (int i = 0; i < 3000; i++) many.add("{\"n\":" + i + ",\"pad\":\"" + (i * 7919L * 104729L) + "\"}");
        writeGz(truncated, many);
        byte[] whole = Files.readAllBytes(truncated.toPath());
        write(truncated, Arrays.copyOf(whole, whole.length / 2));

        FakeSender sender = new FakeSender();
        int sent = s.send(sender, TODAY);
        check("bad header files deleted", !new File(dir, "2026-11-01.gz").exists() && !new File(dir, "2026-11-02.gz").exists());
        check("readable start of a truncated file sent: " + sent, sent > 500 && sent < 3000);
        check("in order", sender.chunks.get(0).get(0).startsWith("{\"n\":0,"));
        check("truncated file gone after sending", !truncated.exists());

        // Truncated and a later chunk fails: the unsent readable lines are kept in a valid file.
        write(truncated, Arrays.copyOf(whole, whole.length / 2));
        check("failed", s.send(new FakeSender(TelemetryStore.Outcome.SENT, TelemetryStore.Outcome.FAILED), TODAY) == -1);
        List<String> kept = lines(truncated);
        check("readable lines kept: " + kept.size(), kept.size() == sent - 500);

        // A bad compressed file next to a plain one for the same day: the plain lines still get compressed.
        write(new File(dir, "2026-11-04.gz"), new byte[] {0x1f, (byte) 0x8b, 8, 0});
        s.add("2026-11-04", "{\"late\":1}");
        s.rotate(TODAY);
        check("plain lines survive", Collections.singletonList("{\"late\":1}").equals(lines(new File(dir, "2026-11-04.gz"))));
    }

    private static void unreadableIsSkipped() throws IOException {
        File dir = new File(root, "unreadable");
        TelemetryStore s = new FailingStore(dir, "2026-11-01.gz", 0);
        writeGz(new File(dir, "2026-11-01.gz"), events("one", 3));
        writeGz(new File(dir, "2026-11-02.gz"), events("two", 2));
        FakeSender sender = new FakeSender();
        check("unreadable day fails the flush", s.send(sender, TODAY) == -1);
        check("later day still sent", sender.chunks.size() == 1 && sender.chunks.get(0).equals(events("two", 2)));
        check("unreadable day kept", new File(dir, "2026-11-01.gz").exists());
        for (int attempt = 2; attempt <= 4; attempt++) {
            check("attempt " + attempt + " fails", s.send(new FakeSender(), TODAY) == -1);
        }
        check("kept after 4 failed reads", new File(dir, "2026-11-01.gz").exists());
        check("5th attempt fails", s.send(new FakeSender(), TODAY) == -1);
        check("dropped after 5 failed reads", !new File(dir, "2026-11-01.gz").exists());
        check("then nothing left to fail", s.send(new FakeSender(), TODAY) == 0);
    }

    private static void readErrorAfterSending() throws IOException {
        File dir = new File(root, "readerror");
        // BufferedReader reads 8 KB at a time: the first chunks are read before the error.
        TelemetryStore s = new FailingStore(dir, TODAY, 8192);
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < 300; i++) lines.add("{\"n\":" + i + ",\"pad\":\"" + new String(new char[80]).replace('\0', 'x') + "\"}");
        for (String line : lines) s.add(TODAY, line);
        FakeSender sender = new FakeSender();
        check("read error fails the flush", s.send(sender, TODAY) == -1);
        check("some chunks went first: " + sender.chunks.size(), sender.chunks.size() > 0 && sender.chunks.size() < 30);
        check("file kept whole for the next attempt", lines(new File(dir, TODAY)).equals(lines));
    }

    /** Store whose reads of one file fail with a plain I/O error after the given number of bytes. */
    private static final class FailingStore extends TelemetryStore {
        private final String failing;
        private final int okBytes;

        FailingStore(File dir, String failing, int okBytes) {
            super(dir, limitsFor(okBytes), new TelemetryStore.Warnings() {
                @Override
                public void warn(String message) {
                    WARNINGS.add(message);
                }
            });
            this.failing = failing;
            this.okBytes = okBytes;
        }

        private static TelemetryStore.Limits limitsFor(int okBytes) {
            TelemetryStore.Limits l = new TelemetryStore.Limits();
            if (okBytes > 0) l.maxPerRequest = 10;
            return l;
        }

        @Override
        InputStream input(File f) throws IOException {
            if (!f.getName().equals(failing)) return super.input(f);
            if (okBytes == 0) throw new IOException("simulated read error");
            final InputStream in = super.input(f);
            return new InputStream() {
                private int read;

                @Override
                public int read() throws IOException {
                    if (read >= okBytes) throw new IOException("simulated read error");
                    read++;
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (read >= okBytes) throw new IOException("simulated read error");
                    int n = in.read(b, off, Math.min(len, okBytes - read));
                    if (n > 0) read += n;
                    return n;
                }

                @Override
                public void close() throws IOException {
                    in.close();
                }
            };
        }
    }

    private static void limits() throws IOException {
        TelemetryStore.Limits l = new TelemetryStore.Limits();
        l.maxFiles = 3;
        l.maxBytes = 1000;
        l.maxTodayBytes = 100;
        TelemetryStore s = store("limits", l);
        File dir = new File(root, "limits");
        for (int d = 1; d <= 5; d++) writeGz(new File(dir, "2026-11-0" + d + ".gz"), events("d" + d, 1));
        s.add("2026-11-06", "{\"today\":1}");
        new FileOutputStream(new File(dir, "2026-11-05.gz.tmp")).close();
        s.enforceLimits("2026-11-06");
        List<String> names = s.names();
        Collections.sort(names);
        check("oldest pruned to 3 files: " + names,
                names.equals(Arrays.asList("2026-11-04.gz", "2026-11-05.gz", "2026-11-06")));
        check("stray temp file removed", tmpFiles(dir).isEmpty());

        // Today over its own cap: its oldest lines go, older days stay.
        for (int i = 0; i < 20; i++) s.add("2026-11-06", "{\"line\":" + (100 + i) + "}");
        s.enforceLimits("2026-11-06");
        File today = new File(dir, "2026-11-06");
        List<String> kept = lines(today);
        check("today trimmed: " + today.length(), today.length() <= 75 && today.length() > 50);
        check("newest line kept", kept.get(kept.size() - 1).equals("{\"line\":119}"));
        check("whole lines only", kept.get(0).startsWith("{\"line\":"));
        check("oldest line dropped", !kept.contains("{\"today\":1}"));
        check("older days kept", new File(dir, "2026-11-05.gz").exists());
    }

    private static void mergedDayCap() throws IOException {
        TelemetryStore.Limits l = new TelemetryStore.Limits();
        l.maxDayBytes = 50;
        TelemetryStore s = store("daycap", l);
        File dir = new File(root, "daycap");
        writeGz(new File(dir, "2026-11-01.gz"), events("old", 5));
        for (String e : events("new", 5)) s.add("2026-11-01", e);
        s.rotate(TODAY);
        List<String> kept = lines(new File(dir, "2026-11-01.gz"));
        check("newest lines kept: " + kept, kept.equals(events("new", 5)));
    }

    private static final class FakeSender implements TelemetryStore.Sender {
        final List<List<String>> chunks = new ArrayList<List<String>>();
        final List<TelemetryStore.Outcome> script;
        int enabledFor = Integer.MAX_VALUE;

        FakeSender(TelemetryStore.Outcome... script) {
            this.script = Arrays.asList(script);
        }

        @Override
        public boolean enabled() {
            return chunks.size() < enabledFor;
        }

        @Override
        public TelemetryStore.Outcome send(List<String> events) {
            chunks.add(new ArrayList<String>(events));
            int i = chunks.size() - 1;
            return i < script.size() ? script.get(i) : TelemetryStore.Outcome.SENT;
        }

        List<Integer> sizes() {
            List<Integer> sizes = new ArrayList<Integer>();
            for (List<String> c : chunks) sizes.add(c.size());
            return sizes;
        }
    }

    private static TelemetryStore store(String name, TelemetryStore.Limits limits) {
        return new TelemetryStore(new File(root, name), limits, new TelemetryStore.Warnings() {
            @Override
            public void warn(String message) {
                WARNINGS.add(message);
            }
        });
    }

    private static List<String> events(String key, int n) {
        List<String> events = new ArrayList<String>();
        for (int i = 0; i < n; i++) events.add("{\"" + key + "\":" + i + "}");
        return events;
    }

    private static void writeGz(File f, List<String> lines) throws IOException {
        f.getParentFile().mkdirs();
        OutputStream out = new GZIPOutputStream(new FileOutputStream(f));
        try {
            for (String line : lines) out.write((line + "\n").getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static void write(File f, byte[] bytes) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), bytes);
    }

    private static List<String> lines(File f) throws IOException {
        List<String> lines = new ArrayList<String>();
        InputStream in = new FileInputStream(f);
        try {
            if (f.getName().endsWith(".gz")) in = new GZIPInputStream(in);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        } finally {
            in.close();
        }
        return lines;
    }

    private static List<String> tmpFiles(File dir) {
        List<String> tmp = new ArrayList<String>();
        String[] all = dir.list();
        if (all != null) {
            for (String name : all) {
                if (name.endsWith(".tmp")) tmp.add(name);
            }
        }
        return tmp;
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
