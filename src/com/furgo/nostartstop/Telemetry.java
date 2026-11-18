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
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Diagnostics the user agreed to on first launch: sends the app's log lines to PostHog. Lines are kept in one file per
 * day (see TelemetryStore) so they survive the head unit being offline, and go out the next time a line is logged with a
 * connection. Uploads are batched: at most one every few seconds, so a burst of log lines does not cost a request each.
 * Failures are only logged to logcat: logging them through LogFile would feed them back in here.
 */
final class Telemetry {
    private static final String TAG = "NoStartStop";
    private static final String DIR = "telemetry";
    private static final String OLD_QUEUE = "telemetry-queue";
    private static final int MAX_FILES = 30;
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int MAX_PER_REQUEST = 500;
    private static final int MAX_REQUESTS = 20;
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
        final long now = System.currentTimeMillis();
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // The switch may have been turned off since this line was queued for the worker.
                    if (!Prefs.telemetry(app)) {
                        deleteStored(app);
                        return;
                    }
                    TelemetryStore store = store(app);
                    migrate(app, store);
                    String day = TelemetryStore.day(now);
                    tidy(store, day);
                    store.append(day, event(app, line, timestamp(now)).toString());
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

    /**
     * Sends today's lines, then older days from oldest to newest, in requests of at most MAX_PER_REQUEST lines and at most
     * MAX_REQUESTS requests per call. Lines are removed once the request carrying them succeeded. Returns the number of
     * lines sent, or -1 if a request failed; it and everything after it stay for the next attempt.
     */
    private static int sendQueued(Context app) {
        try {
            if (!Prefs.telemetry(app)) {
                deleteStored(app);
                return 0;
            }
            TelemetryStore store = store(app);
            migrate(app, store);
            String today = TelemetryStore.day(System.currentTimeMillis());
            tidy(store, today);
            int sent = 0;
            int requests = 0;
            for (String name : TelemetryStore.uploadOrder(store.names(), today)) {
                if (requests == MAX_REQUESTS) break;
                List<String> lines = store.read(name);
                if (lines == null) {
                    warnDropped(name);
                    continue;
                }
                List<String> events = validEvents(name, lines);
                int done = 0;
                boolean failed = false;
                for (List<String> chunk : TelemetryStore.chunks(events, MAX_PER_REQUEST)) {
                    if (requests == MAX_REQUESTS) break;
                    requests++;
                    if (!send(chunk)) {
                        failed = true;
                        break;
                    }
                    done += chunk.size();
                }
                sent += done;
                if (done > 0 || events.size() != lines.size()) store.write(name, events.subList(done, events.size()));
                if (failed) return -1;
            }
            return sent;
        } catch (Exception e) {
            Log.w(TAG, "telemetry: " + e);
            return -1;
        }
    }

    /**
     * Sends what is stored, on the worker so it cannot race log(). Blocks until done; call it off the main thread.
     * Returns the number of lines sent, or -1 if sending failed.
     */
    static int flush(Context context) throws InterruptedException, ExecutionException {
        if (!configured()) return 0;
        final Context app = context.getApplicationContext();
        return WORKER.submit(new Callable<Integer>() {
            @Override
            public Integer call() {
                return sendQueued(app);
            }
        }).get();
    }

    /** Drops anything not sent yet and forgets the install id; called when the user switches diagnostics off. */
    static void clear(Context context) {
        final Context app = context.getApplicationContext();
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                deleteStored(app);
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

    private static boolean send(List<String> events) {
        try {
            JSONArray batch = new JSONArray();
            for (String item : events) batch.put(new JSONObject(item));
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
        } catch (IOException | JSONException e) {
            Log.w(TAG, "telemetry: " + e);
            return false;
        }
    }

    /** Lines that parse as JSON; the others are skipped so one bad line cannot hold up the rest. */
    private static List<String> validEvents(String name, List<String> lines) {
        List<String> events = new ArrayList<String>();
        for (String line : lines) {
            try {
                new JSONObject(line);
                events.add(line);
            } catch (JSONException e) {
                // Counted below.
            }
        }
        if (events.size() != lines.size()) {
            Log.w(TAG, "telemetry: skipped " + (lines.size() - events.size()) + " bad lines in " + name);
        }
        return events;
    }

    /** Moves the lines of the single queue file used by earlier versions into the day files, once. */
    private static void migrate(Context app, TelemetryStore store) throws IOException {
        File old = new File(app.getFilesDir(), OLD_QUEUE);
        if (!old.exists()) return;
        String today = TelemetryStore.day(System.currentTimeMillis());
        BufferedReader r = new BufferedReader(new FileReader(old));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    String day = TelemetryStore.dayOfTimestamp(new JSONObject(line).optString("timestamp"), today);
                    store.append(day, line);
                } catch (JSONException e) {
                    // Not an event: dropped.
                }
            }
        } finally {
            r.close();
        }
        old.delete();
    }

    /** Compresses finished days and applies the size limits; a failure here must not stop logging or sending. */
    private static void tidy(TelemetryStore store, String today) {
        try {
            for (String name : store.rotate(today)) warnDropped(name);
            store.enforceLimits(today);
        } catch (IOException e) {
            Log.w(TAG, "telemetry: " + e);
        }
    }

    private static void warnDropped(String name) {
        Log.w(TAG, "telemetry: deleted unreadable " + name);
    }

    private static void deleteStored(Context app) {
        store(app).deleteAll();
        new File(app.getFilesDir(), OLD_QUEUE).delete();
    }

    private static TelemetryStore store(Context app) {
        return new TelemetryStore(new File(app.getFilesDir(), DIR), MAX_FILES, MAX_BYTES);
    }

    private static String timestamp(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }
}
