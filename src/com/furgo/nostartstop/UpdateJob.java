package com.furgo.nostartstop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.concurrent.ExecutionException;

/**
 * Background job, run by JobScheduler only while some network is up: sends queued diagnostics and, with the
 * automatic check on, asks GitHub for the latest release. It never downloads or installs anything.
 */
public class UpdateJob extends JobService {
    private static final int PERIODIC_ID = 1;
    private static final int SOON_ID = 2;
    private static final long PERIOD_MS = 24 * 60 * 60 * 1000L;
    private static final long BACKOFF_MS = 15 * 60 * 1000L;
    private static final String CHANNEL = "updates";
    private static final int NOTIFICATION_ID = 2;   // 1 is BootService's foreground notification
    private static final Object LOCK = new Object();

    /** Set by MainActivity while it is shown; run on the main thread after each job run. */
    static volatile Runnable onResult;

    private final HashMap<Integer, Thread> running = new HashMap<Integer, Thread>();

    /**
     * Keeps the periodic job scheduled while it has something to do, and cancels both jobs otherwise.
     * With soon, also schedules a one-off run for as soon as there is a network, when one is useful.
     */
    static void schedule(Context context, boolean soon) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        boolean updates = Prefs.autoUpdate(context);
        boolean diagnostics = Telemetry.configured() && Prefs.telemetry(context);
        if (!updates && !diagnostics) {
            js.cancel(PERIODIC_ID);
            js.cancel(SOON_ID);
            return;
        }
        // Scheduling again under the same id would restart the period, or replace a run waiting out its backoff.
        boolean periodic = false, pendingSoon = false;
        for (JobInfo job : js.getAllPendingJobs()) {
            if (job.getId() == PERIODIC_ID) periodic = true;
            if (job.getId() == SOON_ID) pendingSoon = true;
        }
        if (!periodic) js.schedule(job(context, PERIODIC_ID).setPeriodic(PERIOD_MS).build());
        boolean useful = diagnostics || UpdateCheck.due(System.currentTimeMillis(), Prefs.updateCheckedAt(context));
        if (soon && useful && !pendingSoon) js.schedule(job(context, SOON_ID).build());
    }

    private static JobInfo.Builder job(Context context, int id) {
        return new JobInfo.Builder(id, new ComponentName(context, UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
    }

    /** Stores the result of any successful check, by this job or by the button. */
    static void remember(Context context, String tag) {
        if (UpdateCheck.validTag(tag)) Prefs.setUpdateChecked(context, tag, System.currentTimeMillis());
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context app = getApplicationContext();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean retry = false;
                try {
                    retry = !work(app);
                } catch (InterruptedException e) {
                    // Stopped by onStopJob, which already asked JobScheduler to run the job again.
                } catch (RuntimeException e) {
                    LogFile.append(app, "update job failed: " + UpdateCheck.reason(e));
                } finally {
                    finish(params, retry);
                }
            }
        }, "update-job");
        synchronized (running) {
            running.put(params.getJobId(), worker);
        }
        worker.start();
        return true;
    }

    private void finish(JobParameters params, boolean retry) {
        synchronized (running) {
            // Gone or replaced when onStopJob came first: JobScheduler then no longer expects jobFinished from this thread.
            if (running.get(params.getJobId()) != Thread.currentThread()) return;
            running.remove(params.getJobId());
        }
        jobFinished(params, retry);
        Runnable r = onResult;
        if (r != null) new Handler(Looper.getMainLooper()).post(r);
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Thread worker;
        synchronized (running) {
            worker = running.remove(params.getJobId());
        }
        if (worker != null) worker.interrupt();
        return true;
    }

    /** Returns false when something failed and the job should be retried after JobScheduler's backoff. */
    private static boolean work(Context app) throws InterruptedException {
        // The periodic and the one-off job may start together; the second then finds the check already done.
        synchronized (LOCK) {
            boolean ok = true;
            if (Telemetry.configured() && Prefs.telemetry(app)) {
                int sent;
                try {
                    sent = Telemetry.flush(app);
                } catch (ExecutionException e) {
                    sent = -1;
                }
                if (sent > 0) LogFile.append(app, "diagnostics: sent " + sent + " queued lines");
                if (sent < 0) ok = false;
            }
            if (Thread.interrupted()) throw new InterruptedException();
            if (Prefs.autoUpdate(app) && UpdateCheck.due(System.currentTimeMillis(), Prefs.updateCheckedAt(app))) {
                ok &= check(app);
            }
            return ok;
        }
    }

    private static boolean check(Context app) {
        String installed = MainActivity.versionName(app);
        Updater.Release latest;
        try {
            latest = Updater.latest();
        } catch (Updater.NoApkException e) {
            // A release without an APK is an answer, not a failure: wait for the next check.
            completedWithout(app, "release has no APK");
            return true;
        } catch (Exception e) {
            logFailure(app, UpdateCheck.reason(e));
            return false;
        }
        if (!UpdateCheck.validTag(latest.tag)) {
            completedWithout(app, "unexpected tag");
            return true;
        }
        remember(app, latest.tag);
        if (UpdateCheck.offerable(latest.tag, installed) == null) {
            LogFile.append(app, "update check: up to date (" + latest.tag + ")");
            return true;
        }
        LogFile.append(app, "update check: " + latest.tag + " available");
        if (UpdateCheck.shouldNotify(latest.tag, installed, Prefs.notifiedTag(app))) {
            notifyUpdate(app, latest.tag);
            Prefs.setNotifiedTag(app, latest.tag);
        }
        return true;
    }

    private static void logFailure(Context app, String reason) {
        if (reason.equals(Prefs.updateFailure(app))) return;
        Prefs.setUpdateFailure(app, reason);
        LogFile.append(app, "update check failed: " + reason);
    }

    /** Counts the check as done, keeping the stored tag, so it is not repeated before the next one is due. */
    private static void completedWithout(Context app, String reason) {
        logFailure(app, reason);
        Prefs.setUpdateCheckedAt(app, System.currentTimeMillis());
    }

    static void cancelNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
    }

    private static void notifyUpdate(Context app, String tag) {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(app, CHANNEL);
        } else {
            b = new Notification.Builder(app);
        }
        Intent open = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(app, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Update " + tag + " available")
                .setContentText("Open No Start-Stop and tap Install " + tag + ".")
                .setContentIntent(tap)
                .setAutoCancel(true);
        nm.notify(NOTIFICATION_ID, b.build());
    }
}
