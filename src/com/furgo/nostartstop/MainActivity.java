package com.furgo.nostartstop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements CanBox.Listener {
    private static final int BG = 0xFF0B0E13;
    private static final int CARD = 0xFF151A22;
    private static final int CARD_EDGE = 0xFF232A35;
    private static final int TEXT = 0xFFEAEFF5;
    private static final int MUTED = 0xFF8792A2;
    private static final int ACCENT = 0xFF35D6A0;   // link up, command answered
    private static final int WARN = 0xFFFFB02E;     // no answer, nothing sent
    private static final int LINK_BAD = 0xFFFF6B5E;
    private static final int PRIMARY = 0xFF187A5A;  // 4.5:1 against TEXT
    private static final int PRIMARY_PRESSED = 0xFF1F8F6C;
    private static final int NEUTRAL = 0xFF1D2430;
    private static final int NEUTRAL_PRESSED = 0xFF2B3544;

    private static final int MAX_LOG = 50;
    private static final long REQUERY_MS = 5000;
    private static final long STALE_MS = 12000;
    private static final long NOTE_MS = 3000, ANSWER_MS = 3000, REVERT_MS = 4000;

    private static final int LINK_WAITING = 0, LINK_LIVE = 1, LINK_DOWN = -1;

    private final Handler handler = new Handler();
    private final ArrayDeque<String> log = new ArrayDeque<String>();

    private CanBox box;
    private DeviceWatch watch;
    private boolean opened;
    private int link = LINK_WAITING;
    private long lastFrameAt;
    // A tap first waits for a fresh status frame to pick the bit, then for the echo of the command.
    // After any send the button stays busy for HOLD_MS, so a bounce or double tap cannot flip stop-start back.
    private static final int IDLE = 0, AWAIT_FRAME = 1, AWAIT_ECHO = 2, HOLD = 3;
    private static final long HOLD_MS = 2000;
    private int phase = IDLE;
    private long sentAt;
    private String result = "";    // outcome of the last switch command
    private int resultColor = MUTED;
    private String note;           // replaces the result line for a few seconds

    private float density;
    private TextView linkView;
    private TextView updateView;
    private boolean updateBusy;
    private Updater.Release available;   // non-null once a newer release was found
    private Toggle bootSwitch;
    private boolean askingAboutDiagnostics;
    private Toggle telemetrySwitch;   // null when the build has no PostHog key
    private Toggle captureSwitch;
    private Toggle autoUpdateSwitch;
    private TextView logView;
    private ScrollView logScroll;
    private TextView feedback;
    private TextView switchButton;
    private TextView assumedWord;
    private TextView assumedHint;
    private StateBadge badge;
    private LinearLayout stateCard;
    private TextView wrongButton;
    private long reportedAt = -HOLD_MS;

    private final Runnable requery = new Runnable() {
        @Override
        public void run() {
            if (link == LINK_LIVE && SystemClock.elapsedRealtime() - lastFrameAt > STALE_MS) {
                link = LINK_WAITING;
                render();
            }
            if (opened) box.query();
            handler.postDelayed(this, REQUERY_MS);
        }
    };

    private final Runnable noFrame = new Runnable() {
        @Override
        public void run() {
            phase = IDLE;
            showResult("No answer from the car. Nothing sent.", WARN);
        }
    };

    private final Runnable settle = new Runnable() {
        @Override
        public void run() {
            phase = IDLE;
            showSent();
        }
    };

    private final Runnable noAnswer = new Runnable() {
        @Override
        public void run() {
            phase = IDLE;
            showResult("No answer from the car.", WARN);
        }
    };

    private final Runnable rerender = new Runnable() {
        @Override
        public void run() {
            render();
        }
    };

    private final Runnable assumedChanged = new Runnable() {
        @Override
        public void run() {
            log.clear();
            log.addAll(LogFile.tail(MainActivity.this, MAX_LOG));
            renderLog();
            render();
        }
    };

    private final Runnable clearNote = new Runnable() {
        @Override
        public void run() {
            note = null;
            render();
        }
    };

    private final Runnable revertUpdate = new Runnable() {
        @Override
        public void run() {
            showIdleUpdate();
        }
    };

    private final Runnable jobDone = new Runnable() {
        @Override
        public void run() {
            log.clear();
            log.addAll(LogFile.tail(MainActivity.this, MAX_LOG));
            renderLog();
            showStoredUpdate();
        }
    };

    /** The stop-start symbol: the "A" in a circular arrow, struck through when off; a dashed ring with "?" when unknown. */
    private final class StateBadge extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int state = AssumedState.UNKNOWN;

        StateBadge(Context c) {
            super(c);
        }

        void setState(int s) {
            state = s;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), u = Math.min(w, h), cx = w / 2, cy = h / 2;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setPathEffect(null);
            if (state != AssumedState.UNKNOWN) {
                int color = state == AssumedState.ON ? WARN : TEXT;
                float r = u * 0.42f, sw = u * 0.07f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(sw);
                paint.setColor(color);
                c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), 300, 285, false, paint);
                double end = Math.toRadians(225);
                float px = cx + r * (float) Math.cos(end), py = cy + r * (float) Math.sin(end);
                float tx = -(float) Math.sin(end), ty = (float) Math.cos(end), nx = ty, ny = -tx;
                float len = u * 0.22f, half = u * 0.15f;
                path.reset();
                path.moveTo(px + tx * len, py + ty * len);
                path.lineTo(px + nx * half, py + ny * half);
                path.lineTo(px - nx * half, py - ny * half);
                path.close();
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                paint.setStrokeWidth(sw * 0.4f);
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextSize(u * 0.56f);
                c.drawText("A", cx, cy + u * 0.2f, paint);
                if (state == AssumedState.OFF) {
                    float d = u * 0.46f;
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(sw * 1.6f);
                    paint.setColor(CARD);
                    c.drawLine(cx - d, cy + d * 0.55f, cx + d, cy - d * 0.55f, paint);
                    paint.setStrokeWidth(sw * 0.9f);
                    paint.setColor(color);
                    c.drawLine(cx - d, cy + d * 0.55f, cx + d, cy - d * 0.55f, paint);
                }
            } else {
                float sw = u * 0.07f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(sw);
                paint.setColor(MUTED);
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setPathEffect(new DashPathEffect(new float[]{u * 0.14f, u * 0.1f}, 0));
                c.drawCircle(cx, cy, u / 2 - sw, paint);
                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextSize(u * 0.5f);
                c.drawText("?", cx, cy + u * 0.18f, paint);
            }
        }
    }

    private static final class Toggle {
        FrameLayout track;
        View knob;
        TextView word;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        density = getResources().getDisplayMetrics().density;
        box = new CanBox(this);
        setContentView(buildUi());
        LogFile.append(this, "app opened, version " + versionName() + ", android " + Build.VERSION.RELEASE
                + " (sdk " + Build.VERSION.SDK_INT + "), " + Build.MODEL + ", " + display() + ", "
                + DeviceWatch.facts(this));
        watch = new DeviceWatch(this, this);
        watch.start();
        render();
    }

    @Override
    protected void onDestroy() {
        watch.stop();
        super.onDestroy();
    }

    /** For example "display 1920x720, 240 dpi": the head units' panels and densities differ. */
    private String display() {
        DisplayMetrics m = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        return "display " + m.widthPixels + "x" + m.heightPixels + ", " + m.densityDpi + " dpi";
    }

    private String versionName() {
        return versionName(this);
    }

    static String versionName(Context c) {
        try {
            String name = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return name == null || name.isEmpty() ? "unknown" : name;
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        link = LINK_WAITING;
        // Reload from the file so lines written by the boot service show up too.
        log.clear();
        log.addAll(LogFile.tail(this, MAX_LOG));
        renderLog();
        result = "";
        BootService.onRunningChanged = rerender;
        Prefs.onAssumedChanged = assumedChanged;
        WatchService.start(this, PowerWatch.BY_APP);
        box.setCaptureFrames(Prefs.captureFrames(this));
        opened = box.open();
        if (!opened) link = LINK_DOWN;
        // A brief overlay (reverse camera, volume panel) must not cut the hold short.
        if (sentAt != 0 && SystemClock.elapsedRealtime() < sentAt + HOLD_MS) hold();
        else render();
        if (opened) box.query();
        handler.postDelayed(requery, REQUERY_MS);
        askAboutDiagnostics();
        UpdateJob.onResult = jobDone;
        showStoredUpdate();
        UpdateJob.schedule(this, true);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(requery);
        handler.removeCallbacks(noFrame);
        handler.removeCallbacks(noAnswer);
        handler.removeCallbacks(settle);
        BootService.onRunningChanged = null;
        Prefs.onAssumedChanged = null;
        UpdateJob.onResult = null;
        phase = IDLE;
        box.close();
        opened = false;
        super.onPause();
    }

    @Override
    public void onEcho(boolean echo) {
        link = LINK_LIVE;
        lastFrameAt = SystemClock.elapsedRealtime();
        if (phase == AWAIT_FRAME) {
            handler.removeCallbacks(noFrame);
            phase = IDLE;
            send();
        } else if (phase == AWAIT_ECHO && echo) {
            handler.removeCallbacks(noAnswer);
            hold();
        } else {
            render();
        }
    }

    /** Called with a status frame that has just arrived, so the bit is picked from the car's current echo. */
    private void send() {
        if (BootService.running) {
            render();
            return;
        }
        int bit = box.toggle();
        if (bit < 0) {
            showResult("Could not send to the car. Nothing sent.", WARN);
            return;
        }
        Prefs.recordSwitch(this, false);
        sentAt = SystemClock.elapsedRealtime();
        if (bit == 0) {
            // A cleared echo looks the same as no answer, so it cannot confirm this command.
            hold();
            return;
        }
        phase = AWAIT_ECHO;
        handler.postDelayed(noAnswer, ANSWER_MS);
        render();
    }

    private void hold() {
        phase = HOLD;
        handler.postDelayed(settle, Math.max(0, sentAt + HOLD_MS - SystemClock.elapsedRealtime()));
        render();
    }

    private void showSent() {
        showResult("Sent at " + clock() + ". Check the instrument cluster.", ACCENT);
    }

    @Override
    public void onLog(String line) {
        log.addLast(LogFile.append(this, line));
        while (log.size() > MAX_LOG) log.removeFirst();
        renderLog();
    }

    private void renderLog() {
        StringBuilder sb = new StringBuilder();
        for (String s : log) sb.append(s.length() > 11 ? s.substring(11) : s).append('\n');
        logView.setText(sb.toString().trim());
        logScroll.post(new Runnable() {
            @Override
            public void run() {
                logScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private static String clock() {
        return clock(System.currentTimeMillis());
    }

    private static String clock(long wallMs) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(wallMs));
    }

    private void showResult(String text, int color) {
        result = text;
        resultColor = color;
        render();
    }

    private void flash(String message) {
        note = message;
        handler.removeCallbacks(clearNote);
        handler.postDelayed(clearNote, NOTE_MS);
        render();
    }

    private void onSwitchTap() {
        // A second command while one is pending would flip stop-start straight back.
        if (phase != IDLE || BootService.running) return;
        if (link == LINK_DOWN) {
            flash("Not connected to the car. Nothing sent.");
            return;
        }
        note = null;
        handler.removeCallbacks(clearNote);
        result = "";
        phase = AWAIT_FRAME;
        box.query();
        handler.postDelayed(noFrame, ANSWER_MS);
        render();
    }

    private void onUpdateClick() {
        if (updateBusy) return;
        updateBusy = true;
        final Updater.Release found = available;
        handler.removeCallbacks(revertUpdate);
        showUpdate(found == null || found.apkUrl == null ? "Checking…" : "Downloading " + found.tag + "…", MUTED, false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (found == null || found.apkUrl == null) {
                        final Updater.Release latest = Updater.latest();
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                onLatest(latest);
                            }
                        });
                    } else {
                        Updater.install(getApplicationContext(), found);
                        finishUpdate("download done, waiting for Android's install prompt", "Waiting for Android…", TEXT, true);
                    }
                } catch (final Throwable e) {
                    boolean offline = e instanceof UnknownHostException || e instanceof ConnectException
                            || e instanceof SocketTimeoutException;
                    finishUpdate("update failed: " + e, offline ? "No internet" : "Update failed", WARN, false);
                }
            }
        }).start();
    }

    private void onLatest(Updater.Release latest) {
        updateBusy = false;
        UpdateJob.remember(this, latest.tag);
        if (Version.isNewer(latest.tag, versionName())) {
            boolean installNow = available != null && available.tag.equals(latest.tag);
            available = latest;
            onLog("update available: " + latest.tag);
            showIdleUpdate();
            if (installNow) onUpdateClick();
        } else {
            available = null;
            onLog("no update: latest release is " + latest.tag + ", installed is " + versionName());
            showUpdate("Up to date", TEXT, false);
            handler.postDelayed(revertUpdate, REVERT_MS);
        }
    }

    private void finishUpdate(final String logLine, final String label, final int color, final boolean done) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                updateBusy = false;
                // After a failure the found release stays, so the button offers the install again.
                if (done) available = null;
                onLog(logLine);
                showUpdate(label, color, false);
                handler.postDelayed(revertUpdate, REVERT_MS);
            }
        });
    }

    /** Offers the release found by the background check, if it is still newer than the installed version. */
    private void showStoredUpdate() {
        if (updateBusy) return;
        String installed = versionName();
        if (available != null && !Version.isNewer(available.tag, installed)) available = null;
        String tag = UpdateCheck.offerable(Prefs.latestTag(this), installed);
        if (tag == null) UpdateJob.cancelNotification(this);
        // Its APK URL is not stored: tapping Install fetches the release again.
        if (tag != null && (available == null || Version.isNewer(tag, available.tag))) {
            available = new Updater.Release(tag, null);
        }
        showIdleUpdate();
    }

    private void showIdleUpdate() {
        if (available != null) showUpdate("Install " + available.tag, TEXT, true);
        else showUpdate("Check for updates", MUTED, false);
    }

    private void showUpdate(String label, int color, boolean primary) {
        handler.removeCallbacks(revertUpdate);
        updateView.setText(label);
        updateView.setTextColor(color);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) updateView.getLayoutParams();
        lp.height = dp(primary ? 44 : 36);
        updateView.setLayoutParams(lp);
        updateView.setBackgroundDrawable(primary
                ? pressable(PRIMARY, PRIMARY_PRESSED, 0)
                : pressable(0, NEUTRAL_PRESSED, 0));
    }

    private void toggleBoot() {
        boolean on = !Prefs.applyAtBoot(this);
        Prefs.setApplyAtBoot(this, on);
        onLog(on ? "automatic switch on" : "automatic switch off");
        if (on) {
            WatchService.start(this, PowerWatch.BY_APP);
        } else {
            stopService(new Intent(this, WatchService.class));
            Prefs.assumedEnd(this, "automatic switch off");
        }
        render();
    }

    private void toggleCapture() {
        boolean on = !Prefs.captureFrames(this);
        Prefs.setCaptureFrames(this, on);
        box.setCaptureFrames(on);
        onLog(on ? "recording car data on" : "recording car data off");
        renderBoot();
    }

    private void toggleAutoUpdate() {
        boolean on = !Prefs.autoUpdate(this);
        Prefs.setAutoUpdate(this, on);
        onLog(on ? "automatic update check on" : "automatic update check off");
        UpdateJob.schedule(this, true);
        renderBoot();
    }

    private void toggleTelemetry() {
        setTelemetry(!Prefs.telemetry(this));
    }

    /** Asked once, on the first launch of a build that can share diagnostics. Nothing is sent before an answer. */
    private void askAboutDiagnostics() {
        if (!Telemetry.configured() || Prefs.telemetryAnswered(this) || askingAboutDiagnostics) return;
        askingAboutDiagnostics = true;
        new AlertDialog.Builder(this)
                .setTitle("Help fix problems?")
                .setMessage("Send this app's log to the developer? Each line includes the app version, "
                        + "Android version, head unit model and a random ID. The log holds the commands "
                        + "sent to the car and the car's replies. With Record car data on, it also holds raw "
                        + "car data that may include vehicle details.\n\n"
                        + "You can change this later with the Share diagnostics switch.")
                .setCancelable(false)
                .setPositiveButton("Share", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        askingAboutDiagnostics = false;
                        setTelemetry(true);
                    }
                })
                .setNegativeButton("Don't share", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        askingAboutDiagnostics = false;
                        setTelemetry(false);
                    }
                })
                .show();
    }

    private void setTelemetry(boolean on) {
        Prefs.setTelemetry(this, on);
        if (on) {
            onLog("diagnostics sharing on");
        } else {
            onLog("diagnostics sharing off");
            Telemetry.clear(this);
        }
        UpdateJob.schedule(this, false);
        renderBoot();
    }

    /** Adds a tappable setting card with a drawn switch to a horizontal row and returns the switch. */
    private Toggle addSetting(LinearLayout row, String title, String hint, View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20), dp(12), dp(20), dp(12));
        card.setBackgroundDrawable(pressable(CARD, 0xFF1D2430, 1));
        card.setClickable(true);
        card.setMinimumHeight(dp(64));
        card.setOnClickListener(onClick);
        LinearLayout label = new LinearLayout(this);
        label.setOrientation(LinearLayout.VERTICAL);
        label.addView(text(title, 18, TEXT, true));
        label.addView(text(hint, 16, MUTED, false));
        card.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        Toggle t = new Toggle();
        t.word = text("Off", 16, MUTED, true);
        t.word.setGravity(Gravity.RIGHT);
        t.word.setMinWidth(dp(32));
        LinearLayout.LayoutParams wordLp = new LinearLayout.LayoutParams(-2, -2);
        wordLp.leftMargin = dp(12);
        card.addView(t.word, wordLp);
        t.track = new FrameLayout(this);
        t.knob = new View(this);
        t.track.addView(t.knob);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(dp(56), dp(32));
        trackLp.leftMargin = dp(8);
        card.addView(t.track, trackLp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, -1, 1f);
        if (row.getChildCount() > 0) cardLp.leftMargin = dp(16);
        row.addView(card, cardLp);
        return t;
    }

    private void render() {
        linkView.setText(link == LINK_LIVE ? "●  CONNECTED TO CAR"
                : link == LINK_DOWN ? "○  NO LINK TO CAR" : "○  CONNECTING…");
        linkView.setTextColor(link == LINK_LIVE ? ACCENT : link == LINK_DOWN ? LINK_BAD : MUTED);

        boolean bootBusy = BootService.running;
        long last = Prefs.lastSwitchAt(this);
        if (note != null) {
            feedback.setText(note);
            feedback.setTextColor(WARN);
        } else if (bootBusy) {
            feedback.setText("Switching…");
            feedback.setTextColor(MUTED);
        } else if (result.isEmpty() && phase != IDLE) {
            feedback.setText("");
        } else if (result.isEmpty() && link == LINK_DOWN) {
            feedback.setText("This head unit did not let the app reach the car.");
            feedback.setTextColor(MUTED);
        } else if (result.isEmpty() && Prefs.bootSwitchFailed(this)) {
            feedback.setText("Automatic switch failed. Tap to switch.");
            feedback.setTextColor(WARN);
        } else if (result.isEmpty() && last != 0) {
            feedback.setText("Last switch: " + clock(last) + (Prefs.lastSwitchAtBoot(this) ? ", automatic" : ""));
            feedback.setTextColor(MUTED);
        } else {
            feedback.setText(result);
            feedback.setTextColor(resultColor);
        }

        boolean pending = phase != IDLE;
        boolean usable = !pending && !bootBusy && link != LINK_DOWN;
        switchButton.setText(pending ? "Sending…" : "Switch stop-start");
        switchButton.setTextColor(usable ? TEXT : MUTED);
        switchButton.setBackgroundDrawable(usable ? pressable(PRIMARY, PRIMARY_PRESSED, 0)
                : pressable(NEUTRAL, NEUTRAL_PRESSED, 2));

        int assumed = Prefs.assumedState(this);
        int stateColor = assumed == AssumedState.OFF ? TEXT : assumed == AssumedState.ON ? WARN : MUTED;
        badge.setState(assumed);
        stateCard.setBackgroundDrawable(shape(CARD, 20, 2, assumed == AssumedState.UNKNOWN ? CARD_EDGE : assumed == AssumedState.OFF ? 0xFF5B6678 : WARN));
        String word = assumed == AssumedState.UNKNOWN ? "UNKNOWN" : AssumedState.name(assumed).toUpperCase(Locale.ROOT);
        SpannableStringBuilder sb = new SpannableStringBuilder(word);
        if (assumed != AssumedState.UNKNOWN) {
            int from = sb.length();
            sb.append("  assumed");
            sb.setSpan(new AbsoluteSizeSpan(20, true), from, sb.length(), 0);
            sb.setSpan(new ForegroundColorSpan(MUTED), from, sb.length(), 0);
            sb.setSpan(new StyleSpan(Typeface.NORMAL), from, sb.length(), 0);
        }
        assumedWord.setText(sb);
        assumedWord.setTextColor(stateColor);
        assumedHint.setText(assumed == AssumedState.OFF ? "Not read from the car. Check the instrument cluster."
                : assumed == AssumedState.ON ? "Not read from the car. Tap Switch to turn it off."
                : "The app has not seen this drive start. Check the instrument cluster.");
        boolean canReport = assumed != AssumedState.UNKNOWN && !pending && !bootBusy
                && SystemClock.elapsedRealtime() >= reportedAt + HOLD_MS;
        wrongButton.setEnabled(canReport);
        wrongButton.setTextColor(canReport ? TEXT : 0xFF5C6676);
        wrongButton.setBackgroundDrawable(canReport ? outlined()
                : dashed());
        renderBoot();
    }

    /** Logs what led to the shown state and flips it. Sends nothing to the car. */
    private void onWrongTap() {
        long now = SystemClock.elapsedRealtime();
        // A double tap would flip it straight back.
        if (now < reportedAt + HOLD_MS || phase != IDLE || BootService.running) return;
        int result = Prefs.reportAssumedWrong(this, WatchService.seenCentivolts, WatchService.seenCentivoltsAt,
                WatchService.seenPowerState, WatchService.seenPowerStateAt);
        if (result == Prefs.REPORT_REFUSED) return;
        reportedAt = now;
        handler.postDelayed(rerender, HOLD_MS);
        flash(result == Prefs.REPORT_LOGGED ? "Noted in the log. Nothing sent to the car."
                : "Changed, but not logged: too many reports today. Nothing sent to the car.");
    }

    private void renderBoot() {
        renderSwitch(bootSwitch, Prefs.applyAtBoot(this));
        renderSwitch(captureSwitch, Prefs.captureFrames(this));
        if (telemetrySwitch != null) renderSwitch(telemetrySwitch, Prefs.telemetry(this));
        renderSwitch(autoUpdateSwitch, Prefs.autoUpdate(this));
    }

    private void renderSwitch(Toggle t, boolean on) {
        t.word.setText(on ? "On" : "Off");
        t.word.setTextColor(on ? TEXT : MUTED);
        t.track.setBackgroundDrawable(shape(on ? PRIMARY_PRESSED : NEUTRAL_PRESSED, 16, 0, 0));
        t.knob.setBackgroundDrawable(shape(on ? TEXT : MUTED, 12, 0, 0));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(24), dp(24));
        lp.gravity = Gravity.CENTER_VERTICAL | (on ? Gravity.RIGHT : Gravity.LEFT);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        t.knob.setLayoutParams(lp);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(32), dp(8), dp(32), dp(8));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        // The switch card takes the space left over, but never less than its content: on a short screen the column
        // scrolls instead of squeezing the card's lower rows to nothing.
        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setFillViewport(true);
        leftScroll.addView(left, new FrameLayout.LayoutParams(-1, -2));
        root.addView(leftScroll, new LinearLayout.LayoutParams(0, -1, 3f));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(44));
        TextView title = text("NO START-STOP", 22, TEXT, true);
        title.setLetterSpacing(0.12f);
        header.addView(title);
        String v = versionName();
        // Release builds are named like "0.3.0"; local builds are "dev".
        TextView version = text(Character.isDigit(v.charAt(0)) ? "v" + v : v, 14, MUTED, false);
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(0, -2, 1f);
        versionLp.leftMargin = dp(12);
        header.addView(version, versionLp);
        updateView = text("", 14, MUTED, true);
        updateView.setGravity(Gravity.CENTER);
        updateView.setPadding(dp(20), 0, dp(20), 0);
        updateView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onUpdateClick();
            }
        });
        LinearLayout.LayoutParams updateLp = new LinearLayout.LayoutParams(-2, dp(36));
        updateLp.rightMargin = dp(24);
        header.addView(updateView, updateLp);
        showIdleUpdate();
        linkView = text("", 14, MUTED, true);
        header.addView(linkView);
        left.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cardLp.topMargin = dp(8);
        left.addView(switchCard(), cardLp);

        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.HORIZONTAL);
        bootSwitch = addSetting(settings, "Switch off at engine start",
                "One switch each time the car starts.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleBoot();
                    }
                });
        if (Telemetry.configured()) {
            telemetrySwitch = addSetting(settings, "Share diagnostics",
                    "Sends this app's log to the developer.",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            toggleTelemetry();
                        }
                    });
        }
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(-1, -2);
        settingsLp.topMargin = dp(8);
        left.addView(settings, settingsLp);

        LinearLayout capture = new LinearLayout(this);
        captureSwitch = addSetting(capture, "Record car data",
                "Logs raw car data. May include vehicle details.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleCapture();
                    }
                });
        autoUpdateSwitch = addSetting(capture, "Check for updates automatically",
                "Once a day. Never installs by itself.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleAutoUpdate();
                    }
                });
        LinearLayout.LayoutParams captureLp = new LinearLayout.LayoutParams(-1, -2);
        captureLp.topMargin = dp(8);
        left.addView(capture, captureLp);

        LinearLayout logCard = new LinearLayout(this);
        logCard.setOrientation(LinearLayout.VERTICAL);
        logCard.setPadding(dp(20), dp(16), dp(20), dp(16));
        logCard.setBackgroundDrawable(shape(0xFF0F1319, 16, 1, CARD_EDGE));
        TextView logTitle = text("LOG", 12, MUTED, true);
        logTitle.setLetterSpacing(0.16f);
        logCard.addView(logTitle);
        logScroll = new ScrollView(this);
        logView = text("", 13, MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(0, 1.2f);
        logScroll.addView(logView);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollLp.topMargin = dp(8);
        logCard.addView(logScroll, scrollLp);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(0, -1, 1f);
        logLp.leftMargin = dp(24);
        root.addView(logCard, logLp);

        return root;
    }

    private View switchCard() {
        LinearLayout box = new LinearLayout(this);
        stateCard = box;
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(24), dp(12), dp(20), dp(12));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        box.addView(words, new LinearLayout.LayoutParams(0, -1, 1.4f));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        badge = new StateBadge(this);
        head.addView(badge, new LinearLayout.LayoutParams(dp(60), dp(60)));
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        TextView t = text("STOP-START", 14, MUTED, true);
        t.setLetterSpacing(0.14f);
        stack.addView(t);
        assumedWord = text("", 44, TEXT, true);
        assumedWord.setIncludeFontPadding(false);
        stack.addView(assumedWord);
        LinearLayout.LayoutParams stackLp = new LinearLayout.LayoutParams(0, -2, 1f);
        stackLp.leftMargin = dp(16);
        head.addView(stack, stackLp);
        words.addView(head, new LinearLayout.LayoutParams(-1, -2));

        assumedHint = text("", 18, TEXT, false);
        assumedHint.setLineSpacing(0, 1.1f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(8);
        words.addView(assumedHint, hintLp);

        feedback = text("", 18, MUTED, true);
        feedback.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams feedbackLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        feedbackLp.topMargin = dp(4);
        words.addView(feedback, feedbackLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        switchButton = text("", 24, TEXT, true);
        switchButton.setGravity(Gravity.CENTER);
        switchButton.setClickable(true);
        switchButton.setMinHeight(dp(48));
        switchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSwitchTap();
            }
        });
        actions.addView(switchButton, new LinearLayout.LayoutParams(-1, 0, 1f));
        wrongButton = text("Car shows the opposite", 16, MUTED, true);
        wrongButton.setGravity(Gravity.CENTER);
        wrongButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onWrongTap();
            }
        });
        LinearLayout.LayoutParams wrongLp = new LinearLayout.LayoutParams(-1, dp(48));
        wrongLp.topMargin = dp(8);
        actions.addView(wrongButton, wrongLp);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(0, -1, 1f);
        actionsLp.leftMargin = dp(24);
        box.addView(actions, actionsLp);
        return box;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return v;
    }

    private GradientDrawable shape(int fill, int radiusDp, int strokeDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private StateListDrawable pressable(int normal, int pressed, int strokeDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, shape(pressed, 16, strokeDp, CARD_EDGE));
        s.addState(new int[]{}, shape(normal, 16, strokeDp, CARD_EDGE));
        return s;
    }

    private StateListDrawable outlined() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, shape(NEUTRAL_PRESSED, 16, 2, 0xFF5B6678));
        s.addState(new int[]{}, shape(NEUTRAL, 16, 2, 0xFF4A5568));
        return s;
    }

    private StateListDrawable dashed() {
        GradientDrawable d = shape(0, 16, 0, 0);
        d.setStroke(dp(2), 0xFF39414F, dp(6), dp(5));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{}, d);
        return s;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
