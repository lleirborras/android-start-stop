package com.furgo.nostartstop;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
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
import java.util.List;
import java.util.UUID;
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
    private static final long SEND_DELAY_S = 3;
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor();
    // Worker thread only.
    private static boolean sendScheduled;
    private static TelemetryStore store;
    private static long backoffMs;
    private static long retryAt;   // elapsedRealtime

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
                    TelemetryStore s = store(app);
                    migrate(app, s);
                    s.append(s.day(now), event(app, line, s.timestamp(now)).toString());
                    // After a network failure, new lines wait for the backoff or for flush().
                    if (!sendScheduled && SystemClock.elapsedRealtime() >= retryAt) {
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

    /** Returns the number of lines sent, or -1 if sending failed and the rest stays stored. */
    private static int sendQueued(final Context app) {
        int sent;
        try {
            if (!Prefs.telemetry(app)) {
                deleteStored(app);
                return 0;
            }
            TelemetryStore s = store(app);
            migrate(app, s);
            sent = s.send(new TelemetryStore.Sender() {
                @Override
                public boolean enabled() {
                    return Prefs.telemetry(app);
                }

                @Override
                public TelemetryStore.Outcome send(List<String> events) {
                    return post(events);
                }
            }, s.day(System.currentTimeMillis()));
            if (!Prefs.telemetry(app)) deleteStored(app);
        } catch (Exception e) {
            Log.w(TAG, "telemetry: " + e);
            sent = -1;
        }
        if (sent < 0) {
            backoffMs = TelemetryStore.nextBackoff(backoffMs);
            retryAt = SystemClock.elapsedRealtime() + backoffMs;
        } else {
            backoffMs = 0;
            retryAt = 0;
        }
        return sent;
    }

    /**
     * Sends what is stored, on the worker so it cannot race log(), whatever the backoff. Blocks until done; call it off
     * the main thread. Returns the number of lines sent, or -1 if sending failed.
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
        // Lets PostHog drop the copy when a batch is sent twice.
        event.put("uuid", UUID.randomUUID().toString());
        event.put("distinct_id", Prefs.installId(app));
        event.put("timestamp", timestamp);
        event.put("properties", properties);
        return event;
    }

    private static TelemetryStore.Outcome post(List<String> events) {
        JSONArray batch = new JSONArray();
        int bad = 0;
        for (String item : events) {
            try {
                batch.put(new JSONObject(item));
            } catch (JSONException e) {
                bad++;
            }
        }
        if (bad > 0) Log.w(TAG, "telemetry: skipped " + bad + " bad lines");
        if (batch.length() == 0) return TelemetryStore.Outcome.REJECTED;
        try {
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
                int status = c.getResponseCode();
                TelemetryStore.Outcome outcome = TelemetryStore.outcome(status);
                if (outcome != TelemetryStore.Outcome.SENT) Log.w(TAG, "telemetry: HTTP " + status);
                return outcome;
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            Log.w(TAG, "telemetry: " + e);
            return TelemetryStore.Outcome.FAILED;
        } catch (JSONException e) {
            Log.w(TAG, "telemetry: " + e);
            return TelemetryStore.Outcome.REJECTED;
        }
    }

    /**
     * Moves the lines of the single queue file used by earlier versions into the day files. The file is renamed first and
     * deleted whatever happens, so a failure part way loses the rest of it instead of adding the same lines twice.
     */
    private static void migrate(Context app, TelemetryStore s) {
        File old = new File(app.getFilesDir(), OLD_QUEUE);
        File moving = new File(app.getFilesDir(), OLD_QUEUE + ".moving");
        try {
            // A leftover moving file was cut short by a crash and is partly stored already: finally deletes it unread.
            if (!old.exists()) return;
            if (!old.renameTo(moving)) {
                old.delete();
                return;
            }
            String today = s.day(System.currentTimeMillis());
            BufferedReader r = new BufferedReader(new FileReader(moving));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    try {
                        s.add(TelemetryStore.dayOfTimestamp(new JSONObject(line).optString("timestamp"), today), line);
                    } catch (JSONException e) {
                        // Not an event: dropped.
                    }
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "telemetry: migrating the old queue: " + e);
        } finally {
            moving.delete();
        }
    }

    private static void deleteStored(Context app) {
        store(app).deleteAll();
        new File(app.getFilesDir(), OLD_QUEUE).delete();
        new File(app.getFilesDir(), OLD_QUEUE + ".moving").delete();
    }

    private static TelemetryStore store(Context app) {
        if (store == null) {
            store = new TelemetryStore(new File(app.getFilesDir(), DIR), new TelemetryStore.Limits(),
                    new TelemetryStore.Warnings() {
                        @Override
                        public void warn(String message) {
                            Log.w(TAG, "telemetry: " + message);
                        }
                    });
        }
        return store;
    }
}
