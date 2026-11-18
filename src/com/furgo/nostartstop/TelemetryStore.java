package com.furgo.nostartstop;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Diagnostics events kept on disk until sent: one file per UTC day of the event time, named yyyy-MM-dd, one JSON event
 * per line. Days other than the current one are gzip-compressed into yyyy-MM-dd.gz. Plain Java so it can be unit-tested;
 * not thread-safe, Telemetry uses it from its single worker thread.
 */
final class TelemetryStore {
    static final String GZ = ".gz";
    private static final String TMP = ".tmp";
    private static final long LAST_MS = 253402300799999L;   // 9999-12-31T23:59:59.999Z
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File dir;
    private final int maxFiles;
    private final long maxBytes;

    TelemetryStore(File dir, int maxFiles, long maxBytes) {
        this.dir = dir;
        this.maxFiles = maxFiles;
        this.maxBytes = maxBytes;
    }

    /** UTC day of a time; a clock before 1970 or past year 9999 is clamped so names keep four-digit years and sort. */
    static String day(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(Math.max(0, Math.min(millis, LAST_MS))));
    }

    /** Day of an event's "timestamp" value, or fallback when it does not start with one. */
    static String dayOfTimestamp(String timestamp, String fallback) {
        if (timestamp != null && timestamp.length() >= 10 && isDay(timestamp.substring(0, 10))) {
            return timestamp.substring(0, 10);
        }
        return fallback;
    }

    /** Day a file name stands for, or null when it is not a day file. */
    static String dayOf(String name) {
        String day = name.endsWith(GZ) ? name.substring(0, name.length() - GZ.length()) : name;
        return isDay(day) ? day : null;
    }

    private static boolean isDay(String s) {
        return s.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    /** Today's plain file first, then the other day files from oldest to newest. */
    static List<String> uploadOrder(Collection<String> names, String today) {
        List<String> order = new ArrayList<String>();
        List<String> rest = new ArrayList<String>();
        for (String name : names) {
            if (dayOf(name) == null) continue;
            if (name.equals(today)) order.add(name);
            else rest.add(name);
        }
        Collections.sort(rest);
        order.addAll(rest);
        return order;
    }

    /** Day files to delete, oldest first, until at most maxFiles remain holding at most maxBytes. Never today's plain file. */
    static List<String> prune(Map<String, Long> sizes, String today, int maxFiles, long maxBytes) {
        TreeMap<String, Long> sorted = new TreeMap<String, Long>(sizes);
        long total = 0;
        for (long size : sorted.values()) total += size;
        int count = sorted.size();
        List<String> drop = new ArrayList<String>();
        for (Map.Entry<String, Long> e : sorted.entrySet()) {
            if (count <= maxFiles && total <= maxBytes) break;
            if (e.getKey().equals(today)) continue;
            drop.add(e.getKey());
            count--;
            total -= e.getValue();
        }
        return drop;
    }

    /** How many of the oldest lines to drop so the rest, newlines included, take at most maxBytes. */
    static int dropCount(List<String> lines, long maxBytes) {
        long total = 0;
        for (String line : lines) total += bytes(line);
        int n = 0;
        while (total > maxBytes && n < lines.size()) total -= bytes(lines.get(n++));
        return n;
    }

    static <T> List<List<T>> chunks(List<T> items, int max) {
        List<List<T>> chunks = new ArrayList<List<T>>();
        for (int i = 0; i < items.size(); i += max) {
            chunks.add(items.subList(i, Math.min(i + max, items.size())));
        }
        return chunks;
    }

    private static long bytes(String line) {
        return line.getBytes(UTF8).length + 1;
    }

    /** Day file names, plain and compressed, in no particular order. */
    List<String> names() {
        List<String> names = new ArrayList<String>();
        String[] all = dir.list();
        if (all == null) return names;
        for (String name : all) {
            if (dayOf(name) != null) names.add(name);
        }
        return names;
    }

    void append(String day, String line) throws IOException {
        makeDir();
        File f = new File(dir, day);
        // A line cut short by a power loss must not swallow the next one.
        String text = (endsMidLine(f) ? "\n" : "") + line + "\n";
        OutputStream out = new FileOutputStream(f, true);
        try {
            out.write(text.getBytes(UTF8));
        } finally {
            out.close();
        }
    }

    private void makeDir() throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
    }

    private static boolean endsMidLine(File f) throws IOException {
        if (f.length() == 0) return false;
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            r.seek(r.length() - 1);
            return r.read() != '\n';
        } finally {
            r.close();
        }
    }

    /**
     * Compresses every plain day file except today's, merged after the lines of an existing compressed file for that day.
     * Returns the names of files dropped because they could not be read.
     */
    List<String> rotate(String today) throws IOException {
        List<String> dropped = new ArrayList<String>();
        for (String name : names()) {
            if (name.endsWith(GZ) || name.equals(today)) continue;
            List<String> plain = read(name);
            if (plain == null) {
                dropped.add(name);
                continue;
            }
            List<String> lines = new ArrayList<String>();
            if (new File(dir, name + GZ).exists()) {
                List<String> old = read(name + GZ);
                if (old == null) dropped.add(name + GZ);
                else lines.addAll(old);
            }
            lines.addAll(plain);
            write(name + GZ, lines);
            if (!new File(dir, name).delete()) throw new IOException("cannot delete " + name);
        }
        return dropped;
    }

    /** Deletes the oldest day files while over the limits; if today's file alone is too big, drops its oldest lines. */
    void enforceLimits(String today) throws IOException {
        String[] all = dir.list();
        if (all != null) {
            // Left over when the power was cut during a write.
            for (String name : all) {
                if (name.endsWith(TMP)) new File(dir, name).delete();
            }
        }
        Map<String, Long> sizes = new TreeMap<String, Long>();
        for (String name : names()) sizes.put(name, new File(dir, name).length());
        for (String name : prune(sizes, today, maxFiles, maxBytes)) new File(dir, name).delete();
        if (new File(dir, today).length() > maxBytes) {
            List<String> lines = read(today);
            // Trim well below the limit so the next lines do not rewrite the whole file each time.
            if (lines != null) write(today, lines.subList(dropCount(lines, maxBytes * 3 / 4), lines.size()));
        }
    }

    /** Non-empty lines of a day file, or null when it cannot be read; such a file is deleted so it cannot block the queue. */
    List<String> read(String name) {
        File f = new File(dir, name);
        List<String> lines = new ArrayList<String>();
        try {
            InputStream in = new FileInputStream(f);
            try {
                if (name.endsWith(GZ)) in = new GZIPInputStream(in);
                BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF8));
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isEmpty()) lines.add(line);
                }
            } finally {
                in.close();
            }
        } catch (IOException e) {
            f.delete();
            return null;
        }
        return lines;
    }

    /** Replaces a day file through a temporary file and a rename; no lines deletes it. */
    void write(String name, List<String> lines) throws IOException {
        File f = new File(dir, name);
        if (lines.isEmpty()) {
            if (f.exists() && !f.delete()) throw new IOException("cannot delete " + name);
            return;
        }
        makeDir();
        File tmp = new File(dir, name + TMP);
        try {
            FileOutputStream file = new FileOutputStream(tmp);
            try {
                OutputStream out = name.endsWith(GZ) ? new GZIPOutputStream(file) : file;
                Writer w = new BufferedWriter(new OutputStreamWriter(out, UTF8));
                for (String line : lines) {
                    w.write(line);
                    w.write('\n');
                }
                w.flush();
                if (out instanceof GZIPOutputStream) ((GZIPOutputStream) out).finish();
                file.getFD().sync();
            } finally {
                file.close();
            }
            if (!tmp.renameTo(f)) throw new IOException("cannot replace " + name);
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
    }

    /** Deletes every stored file and the directory. */
    void deleteAll() {
        String[] all = dir.list();
        if (all != null) {
            for (String name : all) new File(dir, name).delete();
        }
        dir.delete();
    }
}
