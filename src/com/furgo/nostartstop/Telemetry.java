package com.furgo.nostartstop;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Diagnostics the user agreed to on first launch: sends the app's log lines to PostHog. Lines are queued in a file so they
 * survive the head unit being offline, and go out the next time a line is logged with a connection. Uploads are batched:
 * at most one every few seconds, so a burst of log lines does not cost a request each.
 */
final class Telemetry {
    private static final String TAG = "NoStartStop";
    private static final String QUEUE = "telemetry-queue";
    private static final int MAX_QUEUED = 500;
    private static final long SEND_DELAY_S = 3;
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor();
    private static boolean sendScheduled;   // worker thread only

    /** False for builds made without a PostHog key, such as local builds. */
    static boolean configured() {
        return !BuildConfig.POSTHOG_KEY.isEmpty();
    }

    static void log(Context context, final String line) {
        if (!configured() || !Prefs.telemetry(context)) return;
        final Context app = context.getApplicationContext();
        final String timestamp = now();
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // The switch may have been turned off since this line was queued for the worker.
                    if (!Prefs.telemetry(app)) {
                        queueFile(app).delete();
                        return;
                    }
                    ArrayDeque<String> queued = read(app);
                    queued.addLast(event(app, line, timestamp).toString());
                    while (queued.size() > MAX_QUEUED) queued.removeFirst();
                    write(app, queued);
                    if (!sendScheduled) {
                        sendScheduled = true;
                        WORKER.schedule(new Runnable() {
                            @Override
                            public void run() {
                                sendScheduled = false;
                                sendQueued(app);
                            }
                        }, SEND_DELAY_S, TimeUnit.SECONDS);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "telemetry: " + e);
                }
            }
        });
    }

    private static void sendQueued(Context app) {
        try {
            if (!Prefs.telemetry(app)) {
                queueFile(app).delete();
                return;
            }
            ArrayDeque<String> queued = read(app);
            if (queued.isEmpty() || !send(queued)) return;
            queueFile(app).delete();
        } catch (Exception e) {
            Log.w(TAG, "telemetry: " + e);
        }
    }

    /** Drops anything not sent yet and forgets the install id; called when the user switches diagnostics off. */
    static void clear(Context context) {
        final Context app = context.getApplicationContext();
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                queueFile(app).delete();
                Prefs.clearInstallId(app);
            }
        });
    }

    private static JSONObject event(Context app, String line, String timestamp) throws JSONException {
        JSONObject properties = new JSONObject();
        properties.put("line", line);
        properties.put("app_version", MainActivity.versionName(app));
        properties.put("android_sdk", Build.VERSION.SDK_INT);
        properties.put("model", Build.MODEL);
        // Anonymous event: PostHog must not build a person profile from it.
        properties.put("$process_person_profile", false);
        JSONObject event = new JSONObject();
        event.put("event", "log");
        event.put("distinct_id", Prefs.installId(app));
        event.put("timestamp", timestamp);
        event.put("properties", properties);
        return event;
    }

    private static boolean send(ArrayDeque<String> queued) throws IOException, JSONException {
        JSONArray batch = new JSONArray();
        for (String item : queued) batch.put(new JSONObject(item));
        JSONObject body = new JSONObject();
        body.put("api_key", BuildConfig.POSTHOG_KEY);
        body.put("batch", batch);

        HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.POSTHOG_HOST + "/batch/").openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "nostartstop");
            c.setDoOutput(true);
            OutputStream out = c.getOutputStream();
            try {
                out.write(body.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return c.getResponseCode() == 200;
        } finally {
            c.disconnect();
        }
    }

    private static String now() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static File queueFile(Context app) {
        return new File(app.getFilesDir(), QUEUE);
    }

    private static ArrayDeque<String> read(Context app) {
        ArrayDeque<String> lines = new ArrayDeque<String>();
        try {
            BufferedReader r = new BufferedReader(new FileReader(queueFile(app)));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isEmpty()) lines.addLast(line);
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            // Nothing queued.
        }
        return lines;
    }

    private static void write(Context app, ArrayDeque<String> lines) throws IOException {
        FileWriter w = new FileWriter(queueFile(app), false);
        try {
            for (String line : lines) w.write(line + "\n");
        } finally {
            w.close();
        }
    }
}
