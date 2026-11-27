package com.furgo.nostartstop;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
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
import android.text.Layout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
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
    private static final int OFF_EDGE = 0xFF5B6678;   // border of the card, and of a button, for stop-start off
    private static final int NEUTRAL_PRESSED = 0xFF2B3544;

    private static final int MAX_LOG = 50;
    private static final long REQUERY_MS = 5000;
    private static final long STALE_MS = 12000;
    private static final long NOTE_MS = 3000;

    private static final int LINK_WAITING = 0, LINK_LIVE = 1, LINK_DOWN = -1;

    private final Handler handler = new Handler();
    private final ArrayDeque<String> log = new ArrayDeque<String>();

    private CanBox box;
    private DeviceWatch watch;
    private boolean opened;
    private int link = LINK_WAITING;
    private long lastFrameAt;
    private static final long HOLD_MS = ManualSwitch.HOLD_MS;
    private final ManualSwitch manual = new ManualSwitch();
    private String result = "";    // outcome of the last switch command
    private int resultColor = MUTED;
    private String note;           // replaces the result line for a few seconds

    private float density;
    private TextView linkView;
    private Boolean startShown;      // the background start state last seen, to log a change made in Settings
    private Toggle bootSwitch;
    private boolean askingAboutDiagnostics;
    private boolean fromOwnScreen;   // back from a screen of this app: the car setup is not opened again
    private TextView logView;
    private TextView shareText;      // null when the build has no PostHog key
    private View shareDot;
    private View shareFill, shareRest;   // the rule above the line, filled as a send of several requests goes on
    private ObjectAnimator sharePulse;
    private boolean shareLive;       // resumed: a rebuild of the screen while paused must not start the pulse or a timer
    private ScrollView logScroll;
    private TextView feedback;
    private TextView switchButton;
    private TextView assumedWord;
    private TextView assumedHint;
    private TextView stateTag;       // ASSUMED, FROM THE CAR or SENT under the state word
    /** The state a tap's switch implies while the model is unknown, or UNKNOWN; sentShownFor is that switch's time. */
    private int sentShown = AssumedState.UNKNOWN;
    private long sentShownFor;
    private static final float WORD_SP = 44;
    private StateBadge badge;
    private LinearLayout stateCard;
    private TextView wrongButton;
    private LinearLayout setRow;      // shown instead of wrongButton while the state is unknown
    private TextView setOnButton, setOffButton;
    private long reportedAt = -HOLD_MS;
    /** Below this width (split screen) the screen is one scrolling column. */
    private static final int NARROW_DP = 900;
    private boolean narrow;
    private View rootView;

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

    private final Runnable phaseTimeout = new Runnable() {
        @Override
        public void run() {
            int outcome = manual.timeout();
            if (outcome == ManualSwitch.NO_FRAME) showResult("No answer from the car. Nothing sent.", WARN);
            else if (outcome == ManualSwitch.NO_ANSWER) showResult("No answer from the car.", WARN);
            else if (outcome == ManualSwitch.SENT) showSent();
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

    private final Runnable relayout = new Runnable() {
        @Override
        public void run() {
            boolean n = rootView.getWidth() < dp(NARROW_DP);
            if (n == narrow) return;
            narrow = n;
            setContentView(buildUi());
            renderLog();
            render();
            renderShare();
        }
    };

    private final Runnable shareChanged = new Runnable() {
        @Override
        public void run() {
            renderShare();
        }
    };

    private final Runnable clearNote = new Runnable() {
        @Override
        public void run() {
            note = null;
            render();
        }
    };

    private final Runnable jobDone = new Runnable() {
        @Override
        public void run() {
            log.clear();
            log.addAll(LogFile.tail(MainActivity.this, MAX_LOG));
            renderLog();
        }
    };

    /** The stop-start symbol: the "A" in a circular arrow, struck through when off; a dashed ring with "?" when unknown. */
    private final class StateBadge extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int state = AssumedState.UNKNOWN;
        private final RectF arc = new RectF();
        private DashPathEffect dash;

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float u = Math.min(w, h), r = u * 0.42f;
            arc.set(w / 2f - r, h / 2f - r, w / 2f + r, h / 2f + r);
            dash = new DashPathEffect(new float[]{u * 0.14f, u * 0.1f}, 0);
        }

        StateBadge(Context c) {
            super(c);
        }

        void setState(int s) {
            if (s == state) return;
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
                int color = state == AssumedState.ON ? ACCENT : TEXT;
                float r = u * 0.42f, sw = u * 0.07f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(sw);
                paint.setColor(color);
                c.drawArc(arc, 300, 285, false, paint);
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
                    float d = u * 0.43f;
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
                paint.setPathEffect(dash);
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
        box = new CanBox(this, this);
        narrow = getResources().getConfiguration().screenWidthDp < NARROW_DP;
        setContentView(buildUi());
        LogFile.append(this, "app opened, version " + versionName() + ", android " + Build.VERSION.RELEASE
                + " (sdk " + Build.VERSION.SDK_INT + "), " + Build.MODEL + ", " + display() + ", "
                + DeviceWatch.facts(this) + ", " + CarProfile.describe(DeviceWatch.vendorCarVersion(this)));
        watch = new DeviceWatch(this, this);
        watch.start();
        render();
    }

    @Override
    protected void onDestroy() {
        watch.stop();
        handler.removeCallbacksAndMessages(null);
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
        if (manual.resume(SystemClock.elapsedRealtime())) hold();
        else render();
        if (opened) box.query();
        handler.postDelayed(requery, REQUERY_MS);
        askAboutDiagnostics();
        // Once per return to the app: backing out of the setup must not open it again at once.
        boolean askSetup = !fromOwnScreen && CanBox.setupDecision(this) == CarSetup.ASK;
        fromOwnScreen = false;
        if (askSetup) openOwn(SetupActivity.class);
        UpdateJob.onResult = jobDone;
        Telemetry.onChange = shareChanged;
        shareLive = true;
        Telemetry.refresh(this);
        renderShare();
        // Settings offers the install; here only a notification for a release that is no longer newer goes.
        if (UpdateCheck.offerable(Prefs.latestTag(this), versionName()) == null) UpdateJob.cancelNotification(this);
        UpdateJob.schedule(this, true);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(requery);
        handler.removeCallbacks(phaseTimeout);
        BootService.onRunningChanged = null;
        Prefs.onAssumedChanged = null;
        UpdateJob.onResult = null;
        Telemetry.onChange = null;
        shareLive = false;
        handler.removeCallbacks(shareChanged);
        stopSharePulse();
        manual.pause();
        box.close();
        opened = false;
        super.onPause();
    }

    @Override
    public void onEcho(boolean echo) {
        link = LINK_LIVE;
        lastFrameAt = SystemClock.elapsedRealtime();
        int action = manual.frame(echo);
        if (action == ManualSwitch.SEND) {
            handler.removeCallbacks(phaseTimeout);
            send();
        } else if (action == ManualSwitch.CONFIRMED) {
            handler.removeCallbacks(phaseTimeout);
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
        boolean unknownBefore = Prefs.assumedState(this) == AssumedState.UNKNOWN;
        int bit = box.toggle();
        if (bit < 0) {
            showResult("Could not send to the car. Nothing sent.", WARN);
            return;
        }
        Prefs.recordSwitch(this, false);
        if (unknownBefore) {
            // The model does not count this switch; the car turned stop-start on at its start, so it is off now.
            sentShown = sentShown == AssumedState.OFF ? AssumedState.ON : AssumedState.OFF;
            sentShownFor = Prefs.lastSwitchAt(this);
        }
        if (manual.sent(bit, SystemClock.elapsedRealtime()) == ManualSwitch.HOLD) {
            hold();
            return;
        }
        handler.postDelayed(phaseTimeout, ManualSwitch.ANSWER_MS);
        render();
    }

    /** Runs the timer of a hold the phase machine has just entered. */
    private void hold() {
        handler.postDelayed(phaseTimeout, manual.holdLeft(SystemClock.elapsedRealtime()));
        render();
    }

    private void showSent() {
        showResult(Feedback.sent(System.currentTimeMillis()), ACCENT);
    }

    @Override
    public void onLog(String line) {
        log.addLast(LogFile.append(this, line));
        while (log.size() > MAX_LOG) log.removeFirst();
        renderLog();
        // The beta link learns the car only after it opens; the card must follow at once.
        if (line.startsWith("beta: ")) render();
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
        int action = manual.tap(BootService.running, shownLink() == LINK_DOWN);
        if (action == ManualSwitch.NOTHING) return;
        if (action == ManualSwitch.NO_LINK) {
            flash(CanBox.setupDecision(this) == CarSetup.ASK ? "Confirm your car first. Nothing sent."
                    : "Not connected to the car. Nothing sent.");
            return;
        }
        note = null;
        handler.removeCallbacks(clearNote);
        result = "";
        box.query();
        handler.postDelayed(phaseTimeout, ManualSwitch.ANSWER_MS);
        render();
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

    private void openOwn(Class<? extends Activity> screen) {
        fromOwnScreen = true;
        startActivity(new Intent(this, screen));
    }

    /** Asked once, on the first launch of a build that can share diagnostics. Nothing is sent before an answer. */
    private void askAboutDiagnostics() {
        if (!Telemetry.configured() || Prefs.telemetryAnswered(this) || askingAboutDiagnostics) return;
        askingAboutDiagnostics = true;
        new AlertDialog.Builder(this)
                .setTitle("Share this log with the developer?")
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
        Telemetry.refresh(this);
        renderShare();
    }

    /** The line under the log: what diagnostics sharing is doing right now. */
    private void renderShare() {
        if (shareText == null) return;
        handler.removeCallbacks(shareChanged);
        int n = Telemetry.waiting;
        long sentAt = Telemetry.lastSentAt;
        ShareStatus st = ShareStatus.of(Prefs.telemetry(this), Telemetry.sending, Telemetry.sendDone,
                Telemetry.sendTotal, Telemetry.failed, n, Telemetry.waitingDays, Telemetry.lastSent, sentAt,
                System.currentTimeMillis());
        if (st.recheckMs >= 0 && shareLive) handler.postDelayed(shareChanged, st.recheckMs);
        int dot = st.dot == ShareStatus.RING ? 0 : st.dot == ShareStatus.FAILED ? WARN
                : st.dot == ShareStatus.WAITING ? MUTED : ACCENT;
        boolean pulse = st.pulse;
        float fill = st.fill;
        shareText.setText(st.text);
        shareText.setTextColor(st.bright ? TEXT : MUTED);
        if (((LinearLayout.LayoutParams) shareFill.getLayoutParams()).weight != fill) {
            shareFill.setLayoutParams(new LinearLayout.LayoutParams(0, -1, fill));
            shareRest.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1 - fill));
        }
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        if (dot == 0) d.setStroke(dp(1.5f), OFF_EDGE);
        else d.setColor(dot);
        shareDot.setBackgroundDrawable(d);
        if (!pulse || !shareLive) {
            stopSharePulse();
        } else if (sharePulse == null) {
            sharePulse = ObjectAnimator.ofFloat(shareDot, "alpha", 1f, 0.35f);
            sharePulse.setDuration(700);
            sharePulse.setInterpolator(new LinearInterpolator());
            sharePulse.setRepeatCount(ValueAnimator.INFINITE);
            sharePulse.setRepeatMode(ValueAnimator.REVERSE);
            sharePulse.start();
        }
    }

    private void stopSharePulse() {
        if (sharePulse == null) return;
        sharePulse.cancel();
        sharePulse = null;
        shareDot.setAlpha(1f);
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

    /** The link as shown: down while the car is not supported, without forgetting the state behind it. */
    private int shownLink() {
        return box.unsupported() != null ? LINK_DOWN : link;
    }

    private void render() {
        boolean setup = CanBox.setupDecision(this) == CarSetup.ASK;
        boolean unsupported = !setup && box.unsupported() != null;
        int shown = setup ? LINK_DOWN : shownLink();
        linkView.setText(setup ? "○  SET UP YOUR CAR" : unsupported ? "○  CAR NOT SUPPORTED" : shown == LINK_LIVE ? "●  CONNECTED TO CAR"
                : shown == LINK_DOWN ? "○  NOT CONNECTED" : "○  CONNECTING…");
        linkView.setTextColor(setup || unsupported ? WARN : shown == LINK_LIVE ? ACCENT
                : shown == LINK_DOWN ? LINK_BAD : MUTED);
        linkView.setClickable(setup);
        linkView.setBackgroundDrawable(setup ? pressable(0, NEUTRAL_PRESSED, 0) : null);

        boolean bootBusy = BootService.running;
        long last = Prefs.lastSwitchAt(this);
        Feedback f = Feedback.of(note, bootBusy, result, manual.pending(), shown == LINK_DOWN, setup, unsupported,
                Prefs.bootSwitchFailed(this), last, Prefs.lastSwitchAtBoot(this));
        feedback.setText(f.text);
        if (f.color != Feedback.KEEP) {
            feedback.setTextColor(f.color == Feedback.WARN ? WARN : f.color == Feedback.MUTED ? MUTED : resultColor);
        }

        boolean pending = manual.pending();
        boolean usable = !pending && !bootBusy && shown != LINK_DOWN;
        switchButton.setText(pending ? "Sending…" : "Switch stop-start");
        switchButton.setTextColor(usable ? TEXT : MUTED);
        switchButton.setBackgroundDrawable(usable ? pressable(PRIMARY, PRIMARY_PRESSED, 0)
                : pressable(NEUTRAL, NEUTRAL_PRESSED, 2));

        // A beta car profile reads the setting back: show that, not the model.
        Boolean reported = box.reportedOn();
        int model = Prefs.assumedState(this);
        if (model != AssumedState.UNKNOWN || reported != null || Prefs.ignitionOff(this)
                || Prefs.lastSwitchAt(this) != sentShownFor) {
            sentShown = AssumedState.UNKNOWN;
        }
        boolean sent = sentShown != AssumedState.UNKNOWN;
        int assumed = reported != null ? (reported ? AssumedState.ON : AssumedState.OFF) : sent ? sentShown : model;
        int stateColor = assumed == AssumedState.OFF ? TEXT : assumed == AssumedState.ON ? ACCENT : MUTED;
        badge.setState(assumed);
        stateCard.setBackgroundDrawable(shape(CARD, 20, 2, assumed == AssumedState.UNKNOWN ? CARD_EDGE : assumed == AssumedState.OFF ? OFF_EDGE : ACCENT));
        assumedWord.setText(AssumedState.name(assumed).toUpperCase(Locale.ROOT));
        fitWord();
        assumedWord.setTextColor(stateColor);
        if (assumed == AssumedState.UNKNOWN) {
            stateTag.setVisibility(View.GONE);
        } else {
            int tagColor = reported != null ? ACCENT : sent ? MUTED : WARN;
            stateTag.setText(reported != null ? "FROM THE CAR" : sent ? "SENT" : "ASSUMED");
            stateTag.setTextColor(tagColor);
            stateTag.setBackgroundDrawable(shape(0, 14, 2, tagColor));
            stateTag.setVisibility(View.VISIBLE);
        }
        assumedHint.setText(Feedback.hint(assumed, reported != null, sent, usable, Prefs.applyAtBoot(this),
                Prefs.canSetAssumed(this)));
        boolean unknown = reported == null && model == AssumedState.UNKNOWN;
        boolean free = !pending && !bootBusy && SystemClock.elapsedRealtime() >= reportedAt + HOLD_MS;
        wrongButton.setVisibility(unknown || reported != null ? View.GONE : View.VISIBLE);
        setRow.setVisibility(unknown && reported == null ? View.VISIBLE : View.GONE);
        renderOutlined(wrongButton, !unknown && free, TEXT, 0xFF4A5568);
        boolean canSet = unknown && free && Prefs.canSetAssumed(this);
        // The colours of the state each one sets: green for on, white for off.
        renderOutlined(setOnButton, canSet, ACCENT, ACCENT);
        renderOutlined(setOffButton, canSet, TEXT, OFF_EDGE);
        renderBoot();
    }

    private void renderOutlined(TextView button, boolean enabled, int color, int edge) {
        button.setEnabled(enabled);
        button.setTextColor(enabled ? color : 0xFF5C6676);
        button.setBackgroundDrawable(enabled ? outlined(edge) : dashed());
    }

    /** Sets the unknown state to what the user reads on the instrument cluster. Sends nothing to the car. */
    private void onSetTap(int state) {
        long now = SystemClock.elapsedRealtime();
        if (now < reportedAt + HOLD_MS || manual.pending() || BootService.running) return;
        if (!Prefs.setAssumedByHand(this, state)) return;
        // The row turns into "Car shows the other state": a second tap in the same place must not flip it.
        reportedAt = now;
        handler.postDelayed(rerender, HOLD_MS);
        flash("Set by hand. Nothing sent to the car.");
    }

    /** Logs what led to the shown state and flips it. Sends nothing to the car. */
    private void onWrongTap() {
        long now = SystemClock.elapsedRealtime();
        // A double tap would flip it straight back.
        if (now < reportedAt + HOLD_MS || manual.pending() || BootService.running) return;
        int result = Prefs.reportAssumedWrong(this, WatchService.seenCentivolts, WatchService.seenCentivoltsAt,
                WatchService.seenPowerState, WatchService.seenPowerStateAt);
        if (result == Prefs.REPORT_REFUSED) return;
        reportedAt = now;
        handler.postDelayed(rerender, HOLD_MS);
        flash(result == Prefs.REPORT_LOGGED ? "Noted in the log. Nothing sent to the car."
                : "Changed, but not logged: too many reports today. Nothing sent to the car.");
    }

    /** Shrinks the state word to one line when the column is narrow or the font scale is large. */
    private void fitWord() {
        int avail = assumedWord.getWidth() - assumedWord.getPaddingLeft() - assumedWord.getPaddingRight();
        if (avail <= 0) return;
        float size = WORD_SP * getResources().getDisplayMetrics().scaledDensity;
        TextPaint tp = new TextPaint(assumedWord.getPaint());
        tp.setTextSize(size);
        CharSequence t = assumedWord.getText();
        while (size > 12 * density && Layout.getDesiredWidth(t, tp) > avail) {
            size -= density;
            tp.setTextSize(size);
        }
        if (assumedWord.getTextSize() != size) assumedWord.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    private void renderBoot() {
        boolean startOn = StartListener.enabled(this);
        if (startShown == null || startOn != startShown) {
            if (startShown != null) onLog(startOn ? "background start on" : "background start off");
            startShown = startOn;
        }
        renderSwitch(bootSwitch, Prefs.applyAtBoot(this));
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
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        // The switch card takes the space left over, but never less than its content: on a short screen the column
        // scrolls instead of squeezing the card's lower rows to nothing.
        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setFillViewport(true);
        leftScroll.addView(left, new FrameLayout.LayoutParams(-1, -2));

        addHeader(left);

        LinearLayout.LayoutParams cardLp = narrow ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(-1, 0, 1f);
        cardLp.topMargin = dp(8);
        left.addView(switchCard(), cardLp);

        LinearLayout row = settingsRow(left);
        bootSwitch = addSetting(row, "Switch off at engine start",
                "Sends one switch each time the engine starts.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleBoot();
                    }
                });

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
        if (narrow) {
            // Inside the scrolling column: without this the column takes the drag and the log cannot be scrolled.
            logScroll.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return false;
                }
            });
        }
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollLp.topMargin = dp(8);
        logCard.addView(logScroll, scrollLp);
        stopSharePulse();
        if (Telemetry.configured()) {
            LinearLayout rule = new LinearLayout(this);
            rule.setBackgroundColor(CARD_EDGE);
            shareFill = new View(this);
            shareFill.setBackgroundColor(ACCENT);
            shareRest = new View(this);
            rule.addView(shareFill, new LinearLayout.LayoutParams(0, -1, 0));
            rule.addView(shareRest, new LinearLayout.LayoutParams(0, -1, 1));
            LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(-1, dp(2));
            ruleLp.topMargin = dp(8);
            logCard.addView(rule, ruleLp);
            LinearLayout share = new LinearLayout(this);
            share.setGravity(Gravity.CENTER_VERTICAL);
            shareDot = new View(this);
            share.addView(shareDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
            shareText = text("", 12, MUTED, false);
            shareText.setSingleLine(true);
            shareText.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams shareTextLp = new LinearLayout.LayoutParams(0, -2, 1f);
            shareTextLp.leftMargin = dp(8);
            share.addView(shareText, shareTextLp);
            share.setPadding(0, dp(8), 0, 0);
            logCard.addView(share, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(BG);
        if (narrow) {
            root.setPadding(dp(16), dp(8), dp(16), dp(8));
            LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, dp(200));
            logLp.topMargin = dp(8);
            left.addView(logCard, logLp);
            root.addView(leftScroll, new LinearLayout.LayoutParams(-1, -1));
        } else {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setPadding(dp(32), dp(8), dp(32), dp(8));
            root.addView(leftScroll, new LinearLayout.LayoutParams(0, -1, 3f));
            LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(0, -1, 1f);
            logLp.leftMargin = dp(24);
            root.addView(logCard, logLp);
        }
        // The window's real width decides: split screen does not always come with a new configuration.
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r > l && (r - l < dp(NARROW_DP)) != narrow) {
                    handler.removeCallbacks(relayout);
                    handler.post(relayout);
                }
            }
        });
        rootView = root;
        return root;
    }

    /** One row when wide; when narrow the title and the link state, then the version, Help and Settings. */
    private void addHeader(LinearLayout left) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(48));
        TextView title = text("NO START-STOP", 22, TEXT, true);
        title.setLetterSpacing(0.12f);
        title.setSingleLine(true);
        header.addView(title);
        String v = versionName();
        // Release builds are named like "0.3.0"; local builds are "dev".
        LinearLayout about = new LinearLayout(this);
        about.setOrientation(LinearLayout.VERTICAL);
        TextView version = text(Character.isDigit(v.charAt(0)) ? "v" + v : v, 14, MUTED, false);
        version.setSingleLine(true);
        about.addView(version);
        TextView by = text("by Lleïr Borràs", 12, MUTED, false);
        by.setSingleLine(true);
        by.setEllipsize(TextUtils.TruncateAt.END);
        about.addView(by);

        TextView helpView = headerLink("Help");
        helpView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openOwn(HelpActivity.class);
            }
        });
        TextView settingsView = headerLink("Settings");
        settingsView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openOwn(SettingsActivity.class);
            }
        });
        linkView = text("", 16, MUTED, true);
        linkView.setSingleLine(true);
        linkView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        linkView.setPadding(dp(12), 0, dp(12), 0);
        linkView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (CanBox.setupDecision(MainActivity.this) == CarSetup.ASK) openOwn(SetupActivity.class);
            }
        });
        LinearLayout.LayoutParams aboutLp = new LinearLayout.LayoutParams(0, -2, 1f);
        aboutLp.leftMargin = dp(12);
        if (narrow) {
            header.addView(linkView, new LinearLayout.LayoutParams(0, dp(48), 1f));
            left.addView(header, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout second = new LinearLayout(this);
            second.setGravity(Gravity.CENTER_VERTICAL);
            aboutLp.leftMargin = 0;
            second.addView(about, aboutLp);
            second.addView(helpView, new LinearLayout.LayoutParams(-2, dp(48)));
            second.addView(settingsView, new LinearLayout.LayoutParams(-2, dp(48)));
            left.addView(second, new LinearLayout.LayoutParams(-1, -2));
        } else {
            header.addView(about, aboutLp);
            header.addView(helpView, new LinearLayout.LayoutParams(-2, dp(48)));
            header.addView(settingsView, new LinearLayout.LayoutParams(-2, dp(48)));
            LinearLayout.LayoutParams linkLp = new LinearLayout.LayoutParams(-2, dp(48));
            linkLp.leftMargin = dp(12);
            header.addView(linkView, linkLp);
            left.addView(header, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private TextView headerLink(String label) {
        TextView v = text(label, 16, TEXT, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(20), 0, dp(20), 0);
        v.setMinHeight(dp(48));
        v.setBackgroundDrawable(pressable(0, NEUTRAL_PRESSED, 0));
        return v;
    }

    private LinearLayout settingsRow(LinearLayout left) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        left.addView(row, lp);
        return row;
    }

    private View switchCard() {
        LinearLayout box = new LinearLayout(this);
        stateCard = box;
        box.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        box.setPadding(dp(narrow ? 16 : 24), dp(12), dp(narrow ? 16 : 20), dp(12));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        box.addView(words, narrow ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -1, 1.4f));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        badge = new StateBadge(this);
        head.addView(badge, new LinearLayout.LayoutParams(dp(60), dp(60)));
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        TextView t = text("STOP-START", 14, MUTED, true);
        t.setLetterSpacing(0.14f);
        stack.addView(t);
        assumedWord = text("", WORD_SP, TEXT, true);
        assumedWord.setIncludeFontPadding(false);
        assumedWord.setSingleLine(true);
        assumedWord.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                fitWord();
            }
        });
        stack.addView(assumedWord);
        stateTag = text("", 16, MUTED, true);
        stateTag.setLetterSpacing(0.08f);
        stateTag.setSingleLine(true);
        stateTag.setPadding(dp(12), dp(2), dp(12), dp(2));
        stateTag.setVisibility(View.GONE);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(-2, -2);
        tagLp.topMargin = dp(6);
        stack.addView(stateTag, tagLp);
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
        LinearLayout.LayoutParams feedbackLp = narrow ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(-1, 0, 1f);
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
        actions.addView(switchButton, narrow ? new LinearLayout.LayoutParams(-1, dp(64))
                : new LinearLayout.LayoutParams(-1, 0, 1f));
        wrongButton = text("Car shows the other state", 16, MUTED, true);
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

        setRow = new LinearLayout(this);
        setOnButton = setButton("Started", AssumedState.ON);
        setOffButton = setButton("Stopped", AssumedState.OFF);
        setRow.addView(setOnButton, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams offLp = new LinearLayout.LayoutParams(0, -1, 1f);
        offLp.leftMargin = dp(8);
        setRow.addView(setOffButton, offLp);
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(-1, dp(48));
        setLp.topMargin = dp(8);
        actions.addView(setRow, setLp);

        if (narrow) {
            LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
            actionsLp.topMargin = dp(12);
            box.addView(actions, actionsLp);
        } else {
            LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(0, -1, 1f);
            actionsLp.leftMargin = dp(24);
            box.addView(actions, actionsLp);
        }
        return box;
    }

    private TextView setButton(String label, final int state) {
        TextView b = text(label, 16, MUTED, true);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSetTap(state);
            }
        });
        return b;
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

    private StateListDrawable outlined(int edge) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, shape(NEUTRAL_PRESSED, 16, 2, edge));
        s.addState(new int[]{}, shape(NEUTRAL, 16, 2, edge));
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
