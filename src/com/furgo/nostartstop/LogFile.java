package com.furgo.nostartstop;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Log kept on shared storage so it can be copied off the head unit without adb. */
final class LogFile {
    private static final String NAME = "nostartstop.log";
    private static final long MAX_BYTES = 512 * 1024;

    static File file(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new File(dir, NAME);
    }

    /** Appends a timestamped line and returns it as written. */
    static String append(Context c, String line) {
        String stamped = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + line;
        try {
            File f = file(c);
            if (f.length() > MAX_BYTES) f.renameTo(new File(f.getPath() + ".1"));
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(stamped + "\n");
            } finally {
                w.close();
            }
        } catch (IOException e) {
            Log.w("NoStartStop", "log file: " + e);
        }
        Telemetry.log(c, line);
        return stamped;
    }

    static ArrayDeque<String> tail(Context c, int max) {
        ArrayDeque<String> lines = new ArrayDeque<String>();
        try {
            BufferedReader r = new BufferedReader(new FileReader(file(c)));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.addLast(line);
                    if (lines.size() > max) lines.removeFirst();
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            // No log yet.
        }
        return lines;
    }
}
