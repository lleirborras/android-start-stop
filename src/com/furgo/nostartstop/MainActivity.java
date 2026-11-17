package com.furgo.nostartstop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
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
    private static final int ACCENT = 0xFF35D6A0;   // wanted state: stop-start off / service closed
    private static final int WARN = 0xFFFFB02E;     // stop-start still active, not locked, confirm, transient warnings
    private static final int LINK_BAD = 0xFFFF6B5E;
    private static final int ON_ACCENT = 0xFF04140E;
    private static final int PRIMARY = 0xFF187A5A;  // 4.5:1 against TEXT
    private static final int PRIMARY_PRESSED = 0xFF1F8F6C;
    private static final int NEUTRAL = 0xFF1D2430;
    private static final int NEUTRAL_PRESSED = 0xFF2B3544;

    private static final int B_PRIMARY = 0, B_NEUTRAL = 1, B_DISABLED = 2, B_ARMED = 3;

    private static final int MAX_LOG = 50;
    private static final long REQUERY_MS = 5000;
    private static final long STALE_MS = 12000;
    private static final String NO_ANSWER = "No answer from the car.";
    private static final long NOTE_MS = 3000, ARM_MS = 4000, ANSWER_MS = 3000, REVERT_MS = 4000, CONFIRM_MS = 10000;

    private static final int LINK_WAITING = 0, LINK_LIVE = 1, LINK_DOWN = -1;

    private final Handler handler = new Handler();
    private final ArrayDeque<String> log = new ArrayDeque<String>();

    private CanBox box;
    private boolean opened;
    private int link = LINK_WAITING;
    private Boolean enabled;   // last value read; null = never read
    private Boolean closed;    // last value read; null = never read
    private long lastFrameAt;
    private String knownAt = "";   // HH:mm of the last frame
    private Card pending;          // card whose command was sent and not yet answered by a frame
    private Card awaitCard;        // command whose effect the next frames should show
    private Boolean awaitFrom;     // value of that setting before the command
    private boolean awaitTarget;
    private long awaitUntil;

    private float density;
    private TextView linkView;
    private TextView updateView;
    private boolean updateBusy;
    private Updater.Release available;   // non-null once a newer release was found
    private Toggle bootSwitch;
    private boolean askingAboutDiagnostics;
    private Toggle telemetrySwitch;   // null when the build has no PostHog key
    private TextView lastAction;
    private TextView logView;
    private ScrollView logScroll;
    private Card stopCard;
    private Card serviceCard;

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

    private final Runnable noAnswer = new Runnable() {
        @Override
        public void run() {
            Card c = pending;
            pending = null;
            if (c != null) flash(c, NO_ANSWER);
            render();
        }
    };

    private final Runnable revertUpdate = new Runnable() {
        @Override
        public void run() {
            showIdleUpdate();
        }
    };

    private static final class Card {
        TextView state;
        TextView detail;
        TextView button;
        boolean armed;
        String note;   // replaces the detail line for a few seconds
        Runnable clearNote;
        Runnable disarm;
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
                + " (sdk " + Build.VERSION.SDK_INT + "), " + Build.MODEL);
        render();
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
        opened = box.open();
        if (!opened) link = LINK_DOWN;
        render();
        if (opened) box.query();
        handler.postDelayed(requery, REQUERY_MS);
        askAboutDiagnostics();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(requery);
        handler.removeCallbacks(noAnswer);
        pending = null;
        disarm(stopCard);
        disarm(serviceCard);
        box.close();
        opened = false;
        super.onPause();
    }

    @Override
    public void onState(boolean startStopEnabled, boolean permanentlyClosed) {
        enabled = startStopEnabled;
        closed = permanentlyClosed;
        link = LINK_LIVE;
        lastFrameAt = SystemClock.elapsedRealtime();
        knownAt = clock();
        if (awaitCard != null) {
            boolean value = awaitCard == stopCard ? enabled : closed;
            if (lastFrameAt > awaitUntil) {
                awaitCard = null;
            } else if (value == awaitTarget) {
                // Only report a change: the value may already have been at the target.
                if (awaitFrom == null || awaitFrom != awaitTarget) lastAction.setText((awaitCard == stopCard
                        ? (awaitTarget ? "Stop-start turned back on at " : "Stop-start turned off at ")
                        : (awaitTarget ? "Locked off at " : "Unlocked at ")) + knownAt);
                awaitCard = null;
            }
        }
        pending = null;
        handler.removeCallbacks(noAnswer);
        if (closed) disarm(serviceCard);
        for (Card c : new Card[]{stopCard, serviceCard}) {
            if (NO_ANSWER.equals(c.note)) {
                c.note = null;
                handler.removeCallbacks(c.clearNote);
            }
        }
        render();
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
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
    }

    private void flash(Card c, String message) {
        c.note = message;
        handler.removeCallbacks(c.clearNote);
        handler.postDelayed(c.clearNote, NOTE_MS);
        render();
    }

    private void onCardTap(Card c) {
        if (pending != null) return;
        if (link == LINK_DOWN) {
            flash(c, "Not connected to the car. Nothing sent.");
            return;
        }
        boolean needsConfirm = c == serviceCard && !Boolean.TRUE.equals(closed);
        if (needsConfirm && !c.armed) {
            c.armed = true;
            handler.postDelayed(c.disarm, ARM_MS);
            render();
            return;
        }
        boolean wasArmed = c.armed;
        disarm(c);
        // The state changed under an armed button: the second tap must not send something else.
        if (wasArmed && !needsConfirm) {
            render();
            return;
        }
        Boolean before = c == stopCard ? enabled : closed;
        boolean target = c == stopCard ? toggleStopStart() : toggleService();
        pending = c;
        awaitCard = c;
        awaitFrom = before;
        awaitTarget = target;
        awaitUntil = SystemClock.elapsedRealtime() + CONFIRM_MS;
        handler.postDelayed(noAnswer, ANSWER_MS);
        render();
    }

    private void disarm(Card c) {
        c.armed = false;
        handler.removeCallbacks(c.disarm);
    }

    // The other setting travels in the same command. Unless it was read from a live link, it is
    // sent as its safe value (locked, stop-start off), so a tap can never undo a lock on old data.
    private boolean toggleStopStart() {
        boolean target = enabled == null ? false : !enabled;
        boolean lockKnown = closed != null && link == LINK_LIVE;
        if (!lockKnown) onLog("service state unknown, sending it as closed");
        box.set(target, lockKnown ? closed : true);
        return target;
    }

    private boolean toggleService() {
        boolean target = closed == null ? true : !closed;
        boolean stopStartKnown = enabled != null && link == LINK_LIVE;
        if (!stopStartKnown) onLog("stop-start state unknown, sending it as off");
        box.set(stopStartKnown ? enabled : false, target);
        return target;
    }

    private void onUpdateClick() {
        if (updateBusy) return;
        updateBusy = true;
        final Updater.Release found = available;
        handler.removeCallbacks(revertUpdate);
        showUpdate(found == null ? "Checking…" : "Downloading " + found.tag + "…", MUTED, false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (found == null) {
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
        if (Version.isNewer(latest.tag, versionName())) {
            available = latest;
            onLog("update available: " + latest.tag);
            showIdleUpdate();
        } else {
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
        Prefs.setApplyAtBoot(this, !Prefs.applyAtBoot(this));
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
                        + "sent to the car and the car's replies.\n\n"
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
        String unknownDetail = link == LINK_DOWN ? "This head unit did not let the app reach the car."
                : "Waiting for the car…";
        String asOf = "As of " + knownAt + ". " + (link == LINK_DOWN ? "No link to the car." : "Reconnecting…");
        boolean fresh = link == LINK_LIVE;
        linkView.setText(link == LINK_LIVE ? "●  CONNECTED TO CAR"
                : link == LINK_DOWN ? "○  NO LINK TO CAR" : "○  CONNECTING…");
        linkView.setTextColor(link == LINK_LIVE ? ACCENT : link == LINK_DOWN ? LINK_BAD : MUTED);

        if (enabled == null) {
            setState(stopCard, "UNKNOWN", MUTED, unknownDetail);
            setButton(stopCard, "Turn off", B_PRIMARY);
        } else if (enabled) {
            setState(stopCard, "● ON", fresh ? WARN : MUTED, fresh ? "The engine stops at standstill." : asOf);
            setButton(stopCard, "Turn off", B_PRIMARY);
        } else {
            setState(stopCard, "● OFF", fresh ? ACCENT : MUTED,
                    fresh ? "The engine keeps running. The car turns it back on at the next start." : asOf);
            setButton(stopCard, "Turn back on", B_NEUTRAL);
        }

        if (closed == null) {
            setState(serviceCard, "UNKNOWN", MUTED, unknownDetail);
            setButton(serviceCard, "Lock off", B_PRIMARY);
        } else if (closed) {
            setState(serviceCard, "● LOCKED OFF", fresh ? ACCENT : MUTED,
                    fresh ? "The car is told to keep stop-start off after a restart." : asOf);
            setButton(serviceCard, "Unlock", B_NEUTRAL);
        } else {
            setState(serviceCard, "● NOT LOCKED", fresh ? WARN : MUTED,
                    fresh ? "The car can turn stop-start back on by itself." : asOf);
            setButton(serviceCard, "Lock off", B_PRIMARY);
        }
        renderBoot();
    }

    private void renderBoot() {
        renderSwitch(bootSwitch, Prefs.applyAtBoot(this));
        if (telemetrySwitch != null) renderSwitch(telemetrySwitch, Prefs.telemetry(this));
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

    private void setState(Card c, String text, int color, String detail) {
        c.state.setText(text);
        c.state.setTextColor(color);
        c.detail.setText(c.note != null ? c.note : detail);
        c.detail.setTextColor(c.note != null ? WARN : MUTED);
    }

    private void setButton(Card c, String label, int style) {
        if (pending != null) {
            if (c == pending) label = "Sending…";
            style = B_DISABLED;
        } else if (link == LINK_DOWN) {
            style = B_DISABLED;
        } else if (c.armed) {
            label = "Tap again to confirm";
            style = B_ARMED;
        }
        c.button.setText(label);
        c.button.setTextColor(style == B_PRIMARY ? TEXT : style == B_ARMED ? ON_ACCENT
                : style == B_DISABLED ? MUTED : TEXT);
        c.button.setBackgroundDrawable(style == B_PRIMARY ? pressable(PRIMARY, PRIMARY_PRESSED, 0)
                : style == B_ARMED ? pressable(WARN, 0xFFFFC966, 0)
                : pressable(NEUTRAL, NEUTRAL_PRESSED, 2));
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(32), dp(16), dp(32), dp(16));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, new LinearLayout.LayoutParams(0, -1, 3f));

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

        lastAction = text("", 16, MUTED, false);
        lastAction.setMinHeight(dp(20));
        LinearLayout.LayoutParams lastLp = new LinearLayout.LayoutParams(-1, -2);
        lastLp.topMargin = dp(4);
        left.addView(lastAction, lastLp);

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cardsLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cardsLp.topMargin = dp(8);
        left.addView(cards, cardsLp);

        stopCard = card("STOP-START", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onCardTap(stopCard);
            }
        });
        serviceCard = card("KEEP IT OFF", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onCardTap(serviceCard);
            }
        });
        cards.addView((View) stopCard.state.getParent(), new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -1, 1f);
        second.leftMargin = dp(16);
        cards.addView((View) serviceCard.state.getParent(), second);

        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.HORIZONTAL);
        bootSwitch = addSetting(settings, "Apply at boot",
                "When the head unit starts: turns stop-start off and locks it off.",
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
        settingsLp.topMargin = dp(12);
        left.addView(settings, settingsLp);

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

    private Card card(String title, View.OnClickListener onButton) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(18));
        box.setBackgroundDrawable(shape(CARD, 20, 1, CARD_EDGE));

        final Card c = new Card();
        c.clearNote = new Runnable() {
            @Override
            public void run() {
                c.note = null;
                render();
            }
        };
        c.disarm = new Runnable() {
            @Override
            public void run() {
                c.armed = false;
                render();
            }
        };
        TextView t = text(title, 14, MUTED, true);
        t.setLetterSpacing(0.14f);
        box.addView(t);

        c.state = text("", 32, MUTED, true);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        stateLp.topMargin = dp(8);
        c.state.setGravity(Gravity.BOTTOM);
        box.addView(c.state, stateLp);

        c.detail = text("", 16, MUTED, false);
        c.detail.setMinLines(2);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(4);
        box.addView(c.detail, detailLp);

        c.button = text("", 22, TEXT, true);
        c.button.setGravity(Gravity.CENTER);
        c.button.setClickable(true);
        c.button.setOnClickListener(onButton);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, dp(64));
        btnLp.topMargin = dp(14);
        box.addView(c.button, btnLp);
        return c;
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

    private int dp(float v) {
        return Math.round(v * density);
    }
}
