package com.furgo.nostartstop;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
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
import java.util.zip.ZipException;

/**
 * Diagnostics events kept on disk until sent: one file per UTC day of the event time, named yyyy-MM-dd, one JSON event
 * per line. Days other than the current one are gzip-compressed into yyyy-MM-dd.gz. Files are streamed chunk by chunk,
 * never loaded whole. Plain Java so it can be unit-tested; not thread-safe, Telemetry uses it from its single worker.
 */
class TelemetryStore {
    enum Outcome { SENT, REJECTED, FAILED }

    /** Uploads one chunk of events. */
    interface Sender {
        /** False once the user switched diagnostics off; checked before every request. */
        boolean enabled();

        Outcome send(List<String> events);
    }

    interface Warnings {
        void warn(String message);
    }

    static final class Limits {
        int maxFiles = 30;
        long maxBytes = 5L * 1024 * 1024;
        long maxTodayBytes = 1024 * 1024;
        long maxDayBytes = 8L * 1024 * 1024;   // uncompressed, for a compressed day
        int maxPerRequest = 500;
        int maxRequests = 20;
        long tidyEveryBytes = 64 * 1024;
    }

    private enum Stop { CAP, FAILED, OPTED_OUT }

    static final String GZ = ".gz";
    private static final String TMP = ".tmp";
    private static final long LAST_MS = 253402300799999L;   // 9999-12-31T23:59:59.999Z
    private static final int MAX_READ_FAILURES = 5;
    private static final long MIN_BACKOFF_MS = 60 * 1000;
    private static final long MAX_BACKOFF_MS = 15 * 60 * 1000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File dir;
    private final Limits limits;
    private final Warnings warnings;
    private final SimpleDateFormat dayFormat = utc("yyyy-MM-dd");
    private final SimpleDateFormat timeFormat = utc("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private String tidiedFor;
    private long appendedSinceTidy;
    private int requests;
    private int sent;
    private boolean truncated;
    private final Map<String, Integer> readFailures = new TreeMap<String, Integer>();

    TelemetryStore(File dir, Limits limits, Warnings warnings) {
        this.dir = dir;
        this.limits = limits;
        this.warnings = warnings;
    }

    private static SimpleDateFormat utc(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    /** A clock before 1970 or past year 9999 is clamped so day names keep four-digit years and sort. */
    static long clamp(long millis) {
        return Math.max(0, Math.min(millis, LAST_MS));
    }

    /** UTC day of a time. */
    String day(long millis) {
        return dayFormat.format(new Date(clamp(millis)));
    }

    /** Event timestamp of a time, clamped like day(). */
    String timestamp(long millis) {
        return timeFormat.format(new Date(clamp(millis)));
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

    /** 2xx is sent; 400, 413 and 422 mean this batch will never be accepted; anything else is worth a retry. */
    static Outcome outcome(int status) {
        if (status >= 200 && status < 300) return Outcome.SENT;
        if (status == 400 || status == 413 || status == 422) return Outcome.REJECTED;
        return Outcome.FAILED;
    }

    /** Wait before the next automatic send after a network failure: 1 min, doubling up to 15 min. */
    static long nextBackoff(long currentMs) {
        return currentMs <= 0 ? MIN_BACKOFF_MS : Math.min(currentMs * 2, MAX_BACKOFF_MS);
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

    /** Lines stored and not sent yet. A file that cannot be read counts for what was read of it. */
    int count() {
        int n = 0;
        for (String name : names()) {
            try {
                BufferedReader in = open(new File(dir, name));
                if (in == null) continue;
                try {
                    String line;
                    while ((line = readLine(in)) != null) {
                        if (!line.isEmpty()) n++;
                    }
                } finally {
                    in.close();
                }
            } catch (IOException | OutOfMemoryError e) {
                warnings.warn("not counted " + name + ": " + e);
            }
        }
        truncated = false;
        return n;
    }

    /** Appends an event; compresses finished days and applies the limits when the day changed or enough was written. */
    void append(String day, String line) throws IOException {
        if (!day.equals(tidiedFor) || appendedSinceTidy >= limits.tidyEveryBytes) tidy(day);
        add(day, line);
    }

    /** Appends without tidying. */
    void add(String day, String line) throws IOException {
        makeDir();
        File f = new File(dir, day);
        // A line cut short by a power loss must not swallow the next one.
        byte[] bytes = ((endsMidLine(f) ? "\n" : "") + line + "\n").getBytes(UTF8);
        OutputStream out = new FileOutputStream(f, true);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        appendedSinceTidy += bytes.length;
    }

    /**
     * Sends today's file, then older days from oldest to newest, in chunks of at most maxPerRequest lines and at most
     * maxRequests requests. Lines go once the request carrying them was sent or rejected. Returns the number of lines
     * sent, or -1 when a request failed (it and everything after it stay for the next attempt) or a file could not be
     * read (it is skipped, and dropped after MAX_READ_FAILURES attempts in a row). Deletes everything and returns 0 when
     * the sender is no longer enabled.
     */
    int send(Sender sender, String today) {
        tidy(today);
        requests = 0;
        sent = 0;
        boolean failed = false;
        for (String name : uploadOrder(names(), today)) {
            if (requests >= limits.maxRequests) break;
            Stop stop;
            try {
                stop = sendFile(name, sender);
            } catch (OutOfMemoryError e) {
                warnings.warn("deleted " + name + ": too big to read");
                drop(new File(dir, name));
                continue;
            } catch (IOException e) {
                failed = true;
                Integer before = readFailures.get(name);
                int n = before == null ? 1 : before + 1;
                if (n >= MAX_READ_FAILURES) {
                    warnings.warn("deleted " + name + " after " + n + " failed reads: " + e);
                    drop(new File(dir, name));
                    readFailures.remove(name);
                } else {
                    warnings.warn("skipped " + name + ": " + e);
                    readFailures.put(name, n);
                }
                continue;
            }
            readFailures.remove(name);
            if (stop == Stop.OPTED_OUT) {
                deleteAll();
                return 0;
            }
            if (stop == Stop.FAILED) {
                failed = true;
                break;
            }
            if (stop == Stop.CAP) break;
        }
        if (!failed) return sent;
        if (sent > 0) warnings.warn("sent " + sent + " lines before a failure");
        return -1;
    }

    /** Returns why it stopped before the end of the file, or null when all of it went. */
    private Stop sendFile(String name, Sender sender) throws IOException {
        File f = new File(dir, name);
        truncated = false;
        BufferedReader in = open(f);
        if (in == null) return null;
        boolean consumed = false;
        Stop stop = null;
        List<String> chunk;
        try {
            // A read error leaves the file as it is: lines already sent may go again, PostHog drops them by uuid.
            while (true) {
                chunk = nextChunk(in, name);
                if (chunk.isEmpty()) break;
                if (requests >= limits.maxRequests) {
                    stop = Stop.CAP;
                } else if (!sender.enabled()) {
                    stop = Stop.OPTED_OUT;
                } else {
                    requests++;
                    Outcome outcome = sender.send(chunk);
                    if (outcome == Outcome.FAILED) {
                        stop = Stop.FAILED;
                    } else {
                        if (outcome == Outcome.REJECTED) warnings.warn("dropped " + chunk.size() + " rejected lines");
                        else sent += chunk.size();
                        consumed = true;
                        continue;
                    }
                }
                break;
            }
            if (stop == Stop.OPTED_OUT) return stop;
            if (stop == null) {
                in.close();
                drop(f);
            } else if (consumed || truncated) {
                keepRest(f, chunk, in);
            }
            return stop;
        } finally {
            in.close();
        }
    }

    /** Next lines of a file; a damaged end counts as the end of the file. */
    private List<String> nextChunk(BufferedReader in, String name) throws IOException {
        List<String> chunk = new ArrayList<String>();
        if (truncated) return chunk;
        try {
            String line;
            while (chunk.size() < limits.maxPerRequest && (line = in.readLine()) != null) {
                if (!line.isEmpty()) chunk.add(line);
            }
        } catch (ZipException | EOFException e) {
            warnings.warn(name + " is damaged after its first readable lines: " + e);
            truncated = true;
        }
        return chunk;
    }

    /** Replaces a file with the unsent chunk and the lines after it; if that fails the file goes, so nothing is sent twice. */
    private void keepRest(File f, List<String> chunk, BufferedReader in) {
        File tmp = new File(dir, f.getName() + TMP);
        try {
            Writer w = openWriter(tmp, f.getName().endsWith(GZ));
            try {
                for (String line : chunk) writeLine(w, line);
                String line;
                while (!truncated && (line = readLine(in)) != null) {
                    if (!line.isEmpty()) writeLine(w, line);
                }
            } finally {
                closeWriter(w, tmp);
            }
            replace(tmp, f);
        } catch (IOException e) {
            warnings.warn("deleted " + f.getName() + ": cannot keep the unsent lines: " + e);
            tmp.delete();
            drop(f);
        }
    }

    private String readLine(BufferedReader in) throws IOException {
        try {
            return in.readLine();
        } catch (ZipException | EOFException e) {
            truncated = true;
            return null;
        }
    }

    /** Compresses finished days and applies the limits; a failure is only reported, so logging and sending go on. */
    private void tidy(String today) {
        tidiedFor = today;
        appendedSinceTidy = 0;
        try {
            rotate(today);
            enforceLimits(today);
        } catch (IOException e) {
            warnings.warn("" + e);
        }
    }

    /** Compresses every plain day file except today's, behind the lines of an existing compressed file for that day. */
    void rotate(String today) throws IOException {
        for (String name : names()) {
            if (name.endsWith(GZ) || name.equals(today)) continue;
            File plain = new File(dir, name);
            File gz = new File(dir, name + GZ);
            File merged = new File(dir, name + ".merge" + TMP);
            try {
                Writer w = openWriter(merged, false);
                try {
                    if (gz.exists()) copyLines(gz, w);
                    copyLines(plain, w);
                } finally {
                    closeWriter(w, merged);
                }
                trimHead(merged, limits.maxDayBytes);
                File tmp = new File(dir, name + GZ + TMP);
                compress(merged, tmp);
                replace(tmp, gz);
            } catch (OutOfMemoryError e) {
                warnings.warn("deleted " + name + GZ + ": too big to compress");
                drop(gz);
                // The plain lines get their own try next time, without the old compressed ones.
                continue;
            } finally {
                merged.delete();
            }
            drop(plain);
        }
    }

    private void copyLines(File src, Writer w) throws IOException {
        truncated = false;
        BufferedReader in = open(src);
        if (in == null) return;
        try {
            String line;
            while ((line = readLine(in)) != null) {
                if (!line.isEmpty()) writeLine(w, line);
            }
            if (truncated) warnings.warn(src.getName() + " is damaged after its first readable lines");
        } finally {
            in.close();
        }
    }

    /** Deletes the oldest day files while over the limits, and drops today's oldest lines when its file is too big. */
    void enforceLimits(String today) throws IOException {
        String[] all = dir.list();
        if (all != null) {
            // Left over when the power was cut during a write.
            for (String name : all) {
                if (name.endsWith(TMP)) new File(dir, name).delete();
            }
        }
        File current = new File(dir, today);
        // Trim below the cap so the next lines do not rewrite the whole file again.
        if (current.length() > limits.maxTodayBytes) trimHead(current, limits.maxTodayBytes * 3 / 4);
        Map<String, Long> sizes = new TreeMap<String, Long>();
        for (String name : names()) sizes.put(name, new File(dir, name).length());
        for (String name : prune(sizes, today, limits.maxFiles, limits.maxBytes)) new File(dir, name).delete();
    }

    /** Drops whole lines from the start of a plain file until at most target bytes are left. */
    private void trimHead(File f, long target) throws IOException {
        long length = f.length();
        if (length <= target) return;
        File tmp = new File(dir, f.getName() + ".trim" + TMP);
        RandomAccessFile in = new RandomAccessFile(f, "r");
        try {
            in.seek(length - target - 1);
            int b;
            do {
                b = in.read();
            } while (b != -1 && b != '\n');
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                out.getFD().sync();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        replace(tmp, f);
    }

    private static void compress(File src, File dest) throws IOException {
        InputStream in = new FileInputStream(src);
        try {
            FileOutputStream file = new FileOutputStream(dest);
            GZIPOutputStream out = new GZIPOutputStream(file);
            try {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                out.finish();
                file.getFD().sync();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    /** Reader for a day file, or null when it is not a readable file; such a file is deleted. Other errors are thrown. */
    private BufferedReader open(File f) throws IOException {
        InputStream in = input(f);
        try {
            if (f.getName().endsWith(GZ)) in = new GZIPInputStream(in);
        } catch (ZipException | EOFException e) {
            in.close();
            warnings.warn("deleted unreadable " + f.getName() + ": " + e);
            drop(f);
            return null;
        } catch (IOException e) {
            in.close();
            throw e;
        }
        return new BufferedReader(new InputStreamReader(in, UTF8));
    }

    /** Opens a file for reading; tests override it to simulate I/O errors. */
    InputStream input(File f) throws IOException {
        return new FileInputStream(f);
    }

    private static Writer openWriter(File f, boolean gzip) throws IOException {
        FileOutputStream file = new FileOutputStream(f);
        OutputStream out = file;
        if (gzip) {
            try {
                out = new GZIPOutputStream(file);
            } catch (IOException e) {
                file.close();
                throw e;
            }
        }
        return new BufferedWriter(new OutputStreamWriter(out, UTF8));
    }

    private static void writeLine(Writer w, String line) throws IOException {
        w.write(line);
        w.write('\n');
    }

    /** Closes a writer from openWriter; the data is on disk once it returns. */
    private static void closeWriter(Writer w, File f) throws IOException {
        w.close();
        RandomAccessFile sync = new RandomAccessFile(f, "rw");
        try {
            sync.getFD().sync();
        } finally {
            sync.close();
        }
    }

    private static void replace(File tmp, File f) throws IOException {
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("cannot replace " + f.getName());
        }
    }

    /** Deletes a file, or empties it when it cannot be deleted, so its lines cannot be sent or merged again. */
    private static void drop(File f) {
        if (f.delete() || !f.exists()) return;
        try {
            new FileOutputStream(f).close();
        } catch (IOException e) {
            // Nothing more can be done; the next attempt tries again.
        }
    }

    /** Deletes every stored file and the directory. */
    void deleteAll() {
        String[] all = dir.list();
        if (all != null) {
            for (String name : all) new File(dir, name).delete();
        }
        dir.delete();
        tidiedFor = null;
        appendedSinceTidy = 0;
        readFailures.clear();
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
}
