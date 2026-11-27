package com.furgo.nostartstop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * The car setup and the switches that are not needed every drive. The main screen closes its link while this is
 * shown and reads the switches again when it comes back, so a switch here only stores and logs.
 */
public class SettingsActivity extends Activity {
    private static final int BG = 0xFF0B0E13;
    private static final int CARD = 0xFF151A22;
    private static final int CARD_EDGE = 0xFF232A35;
    private static final int TEXT = 0xFFEAEFF5;
    private static final int MUTED = 0xFF8792A2;
    private static final int WARN = 0xFFFFB02E;
    private static final int PRIMARY = 0xFF187A5A;
    private static final int PRIMARY_PRESSED = 0xFF1F8F6C;
    private static final int NEUTRAL = 0xFF1D2430;
    private static final int NEUTRAL_PRESSED = 0xFF2B3544;
    private static final int OFF_EDGE = 0xFF5B6678;
    private static final int NARROW_DP = 900;
    private static final long REVERT_MS = 4000;

    private final Handler handler = new Handler();
    private float density;
    private boolean narrow;
    private Toggle startSwitch;
    private Toggle captureSwitch;
    private Toggle autoUpdateSwitch;
    private Toggle betaSwitch;
    private Toggle telemetrySwitch;   // null when the build has no PostHog key
    private Toggle withoutAdbSwitch;
    private Toggle engineGuessSwitch;
    private AlertDialog startDialog;

    private LinearLayout updateCard;
    private TextView updateView;
    private boolean updateBusy;
    private String updateLabel;      // what the update card shows, kept across a rebuild of the screen
    private int updateColor;
    private boolean updatePrimary;
    private Updater.Release available;   // non-null once a newer release was found

    private final Runnable revertUpdate = new Runnable() {
        @Override
        public void run() {
            showIdleUpdate();
        }
    };

    private final Runnable jobDone = new Runnable() {
        @Override
        public void run() {
            showStoredUpdate();
        }
    };

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
        narrow = getResources().getConfiguration().screenWidthDp < NARROW_DP;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the car setup or Android's notification access screen: the cards may have changed.
        setContentView(build());
        renderSwitches();
        UpdateJob.onResult = jobDone;
        showStoredUpdate();
    }

    @Override
    protected void onPause() {
        if (UpdateJob.onResult == jobDone) UpdateJob.onResult = null;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (startDialog != null) startDialog.dismiss();
        super.onDestroy();
    }

    private View build() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(narrow ? 16 : 32), dp(8), dp(narrow ? 16 : 32), dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(44));
        TextView title = text("SETTINGS", 22, TEXT, true);
        title.setLetterSpacing(0.12f);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = text("Close", 16, TEXT, true);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(28), 0, dp(28), 0);
        close.setBackgroundDrawable(shape(NEUTRAL, 16, 2, OFF_EDGE));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(-2, dp(48)));
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // Two columns of cards on a wide screen, one below NARROW_DP.
        LinearLayout first = new LinearLayout(this);
        first.setOrientation(LinearLayout.VERTICAL);
        LinearLayout second = first;
        if (!narrow) {
            LinearLayout columns = new LinearLayout(this);
            second = new LinearLayout(this);
            second.setOrientation(LinearLayout.VERTICAL);
            columns.addView(first, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams secondLp = new LinearLayout.LayoutParams(0, -2, 1f);
            secondLp.leftMargin = dp(24);
            columns.addView(second, secondLp);
            page.addView(columns, new LinearLayout.LayoutParams(-1, -2));
        } else {
            page.addView(first, new LinearLayout.LayoutParams(-1, -2));
        }

        section(first, "CAR");
        carCard(first);

        section(first, "STARTING");
        startSwitch = addSetting(first, "Background start", StartListener.available(this)
                        ? "Starts the app when the head unit powers on." : "Not allowed by this head unit.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onStartTap();
                    }
                });

        section(first, "UPDATES");
        updateView = actionCard(first, "Installed: " + versionLabel(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onUpdateClick();
            }
        });
        autoUpdateSwitch = addSetting(first, "Check for updates automatically",
                "Once a day. Never installs by itself.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleAutoUpdate();
                    }
                });
        betaSwitch = addSetting(first, "Beta program",
                "Offers test builds before release. Leaving does not go back: you stay on the beta until the "
                        + "next stable version.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleBetaProgram();
                    }
                });

        section(second, "DATA");
        if (Telemetry.configured()) {
            telemetrySwitch = addSetting(second, "Share diagnostics",
                    "Sends this app's log to the developer.",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            toggleTelemetry();
                        }
                    });
        }
        captureSwitch = addSetting(second, "Record car data",
                "Logs raw car data. May include vehicle details.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleCapture();
                    }
                });

        section(second, "BETA");
        withoutAdbSwitch = addSetting(second, "Beta: reach the car without adb",
                "Try it if the card says NOT CONNECTED.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleWithoutAdb();
                    }
                });
        if (CanBox.betaBuild(this)) engineGuessSwitch = addSetting(second, "Beta: guess the engine start",
                "Switch 15 s after ignition on when the head unit gives no engine speed.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleEngineGuess();
                    }
                });

        if (updateLabel == null) showIdleUpdate();
        else drawUpdate();

        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(page, new FrameLayout.LayoutParams(-1, -2));
        // The window's real width decides: split screen does not always come with a new configuration.
        scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r > l && (r - l < dp(NARROW_DP)) != narrow) {
                    narrow = !narrow;
                    scroll.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            setContentView(build());
                            renderSwitches();
                        }
                    });
                }
            }
        });
        return scroll;
    }

    private String versionLabel() {
        String v = MainActivity.versionName(this);
        // Release builds are named like "0.3.0" or "0.21.0-beta.3"; local builds are "dev".
        return Version.label(v);
    }

    /** A small caps title above a group of cards. */
    private void section(LinearLayout column, String name) {
        TextView t = text(name, 13, MUTED, true);
        t.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(column.getChildCount() == 0 ? 8 : 20);
        column.addView(t, lp);
    }

    private void carCard(LinearLayout column) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(16), dp(24), dp(20));
        card.setBackgroundDrawable(shape(CARD, 16, 1, CARD_EDGE));

        final String detection = DeviceWatch.carDetection(this);
        String choice = Prefs.setupChoice(this);
        boolean beta = CanBox.betaBuild(this);
        int decision = CarSetup.decide(detection, choice, Prefs.setupDetection(this), beta);
        final String shown = decision == CarSetup.ASK ? CarSetup.suggestion(detection, choice, beta) : choice;
        TextView reports = text(CarSetup.detectionLabel(detection), 16, TEXT, false);
        reports.setLineSpacing(0, 1.15f);
        card.addView(reports, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.topMargin = dp(14);
        card.addView(text("CAN box", 16, TEXT, true), nameLp);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setMinimumHeight(dp(52));
        row.setBackgroundDrawable(pressable(NEUTRAL, NEUTRAL_PRESSED, 2));
        TextView label = text(shown == null ? "Choose your CAN box" : CarSetup.choiceLabel(shown), 16,
                shown == null ? MUTED : TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(text("\u25BE", 22, TEXT, true));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                CanBoxPicker.show(SettingsActivity.this, detection, shown, new CanBoxPicker.Done() {
                    @Override
                    public void picked(String picked) {
                        Prefs.setSetup(SettingsActivity.this, picked, detection);
                        log("car setup: confirmed " + CarSetup.choiceLabel(picked) + " for "
                                + CarSetup.detectionName(detection)
                                + (CarSetup.mismatch(detection, picked) != null ? ", which it does not match" : ""));
                        setContentView(build());
                    }
                });
            }
        });
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(6);
        card.addView(row, rowLp);

        String state = decision == CarSetup.ASK ? "Not confirmed: nothing is sent. Pick it to confirm."
                : CarSetup.mismatch(detection, choice) != null ? "Confirmed, although the head unit reports another."
                : null;
        if (state != null) {
            TextView v = text(state, 15, WARN, false);
            v.setLineSpacing(0, 1.15f);
            LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(-1, -2);
            vLp.topMargin = dp(8);
            card.addView(v, vLp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        column.addView(card, lp);
    }

    private void log(String line) {
        LogFile.append(this, line);
    }

    /** Explains the notification access the background start needs, then opens Android's screen for it. */
    private void onStartTap() {
        if (!StartListener.available(this)) {
            Toast.makeText(this, "This head unit does not allow a background start.", Toast.LENGTH_LONG).show();
            return;
        }
        boolean on = StartListener.enabled(this);
        startDialog = new AlertDialog.Builder(this)
                .setTitle(on ? "Background start is on" : "Start with the head unit?")
                .setMessage("Android can start this app in the background when the head unit powers on or wakes, "
                        + "so Switch off at engine start also works when another app was on screen.\n\n"
                        + "It uses Android's notification access, because Android starts apps that have it. "
                        + "This app does not read any notification.\n\n"
                        + (on ? "To turn it off, switch off No Start-Stop on the next screen."
                        : "On the next screen, switch on No Start-Stop."))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                            log("background start: opened notification access settings");
                        } catch (RuntimeException e) {
                            log("background start: no notification access settings: " + e);
                            Toast.makeText(SettingsActivity.this, "This head unit has no notification access screen.",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .show();
    }

    private void onUpdateClick() {
        if (updateBusy) return;
        updateBusy = true;
        final Updater.Release found = available;
        handler.removeCallbacks(revertUpdate);
        showUpdate(found == null || found.apkUrl == null ? "Checking…" : "Downloading " + Version.label(found.tag) + "…", MUTED, false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (found == null || found.apkUrl == null) {
                        final boolean beta = Prefs.betaProgram(getApplicationContext());
                        final Updater.Release latest = Updater.latest(beta);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                onLatest(latest, beta);
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

    private void onLatest(Updater.Release latest, boolean beta) {
        if (isDestroyed()) return;
        updateBusy = false;
        if (Prefs.betaProgram(this) != beta) {
            // The beta program was switched during the check: ask again for the channel now chosen.
            available = null;
            onUpdateClick();
            return;
        }
        String installed = MainActivity.versionName(this);
        UpdateJob.remember(this, latest.tag);
        if (UpdateCheck.offerable(latest.tag, installed, beta) != null) {
            boolean installNow = available != null && available.tag.equals(latest.tag);
            available = latest;
            log("update available: " + latest.tag);
            showIdleUpdate();
            if (installNow) onUpdateClick();
        } else {
            available = null;
            log("no update: latest release is " + latest.tag + ", installed is " + installed);
            showUpdate("Up to date", TEXT, false);
            handler.postDelayed(revertUpdate, REVERT_MS);
        }
    }

    private void finishUpdate(final String logLine, final String label, final int color, final boolean done) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (isDestroyed()) {
                    LogFile.append(getApplicationContext(), logLine);
                    return;
                }
                updateBusy = false;
                // After a failure the found release stays, so the card offers the install again.
                if (done) available = null;
                log(logLine);
                showUpdate(label, color, false);
                handler.postDelayed(revertUpdate, REVERT_MS);
            }
        });
    }

    /** Offers the release found by the background check, if it is still newer than the installed version. */
    private void showStoredUpdate() {
        if (updateBusy) return;
        String installed = MainActivity.versionName(this);
        if (available != null && !Version.isNewer(available.tag, installed)) available = null;
        String tag = UpdateCheck.offerable(Prefs.latestTag(this), installed, Prefs.betaProgram(this));
        if (tag == null) UpdateJob.cancelNotification(this);
        // Its APK URL is not stored: tapping Install fetches the release again.
        if (tag != null && (available == null || Version.isNewer(tag, available.tag))) {
            available = new Updater.Release(tag, null);
        }
        showIdleUpdate();
    }

    private void showIdleUpdate() {
        if (available != null) showUpdate("Install " + Version.label(available.tag), TEXT, true);
        else showUpdate("Check for updates now", TEXT, false);
    }

    private void showUpdate(String label, int color, boolean primary) {
        handler.removeCallbacks(revertUpdate);
        updateLabel = label;
        updateColor = color;
        updatePrimary = primary;
        drawUpdate();
    }

    private void drawUpdate() {
        updateView.setText(updateLabel);
        updateView.setTextColor(updateColor);
        updateCard.setBackgroundDrawable(updatePrimary ? pressable(PRIMARY, PRIMARY_PRESSED, 0)
                : pressable(CARD, NEUTRAL, 1));
    }

    private void toggleCapture() {
        boolean on = !Prefs.captureFrames(this);
        Prefs.setCaptureFrames(this, on);
        log(on ? "recording car data on" : "recording car data off");
        renderSwitches();
    }

    private void toggleBetaProgram() {
        boolean on = !Prefs.betaProgram(this);
        Prefs.setBetaProgram(this, on);
        String installed = MainActivity.versionName(this);
        if (on) log("beta program joined: checking for test builds");
        else if (Version.isBeta(installed)) {
            log("beta program left: stays on " + Version.label(installed) + " until a newer stable version");
        } else log("beta program left");
        renderSwitches();
        UpdateJob.schedule(this, true);
        if (updateBusy) return;
        // Checks at once, so the card shows what the chosen channel offers.
        UpdateJob.cancelNotification(this);
        available = null;
        onUpdateClick();
    }

    private void toggleAutoUpdate() {
        boolean on = !Prefs.autoUpdate(this);
        Prefs.setAutoUpdate(this, on);
        log(on ? "automatic update check on" : "automatic update check off");
        UpdateJob.schedule(this, true);
        renderSwitches();
    }

    private void toggleTelemetry() {
        boolean on = !Prefs.telemetry(this);
        Prefs.setTelemetry(this, on);
        if (on) {
            log("diagnostics sharing on");
        } else {
            log("diagnostics sharing off");
            Telemetry.clear(this);
        }
        UpdateJob.schedule(this, false);
        Telemetry.refresh(this);
        renderSwitches();
    }

    private void toggleWithoutAdb() {
        boolean on = !Prefs.withoutAdb(this);
        Prefs.setWithoutAdb(this, on);
        if (!on) {
            log("beta: reach the car without adb off, from the next app start");
        } else {
            String failure = HiddenApi.exempt();
            log(failure == null ? "beta: reach the car without adb on"
                    : "beta: reach the car without adb failed: " + failure);
        }
        renderSwitches();
    }

    private void toggleEngineGuess() {
        boolean on = !Prefs.engineGuess(this);
        Prefs.setEngineGuess(this, on);
        log(on ? "beta: engine guess on" : "beta: engine guess off");
        renderSwitches();
    }

    private void renderSwitches() {
        renderSwitch(startSwitch, StartListener.enabled(this));
        renderSwitch(captureSwitch, Prefs.captureFrames(this));
        renderSwitch(autoUpdateSwitch, Prefs.autoUpdate(this));
        renderSwitch(betaSwitch, Prefs.betaProgram(this));
        if (telemetrySwitch != null) renderSwitch(telemetrySwitch, Prefs.telemetry(this));
        renderSwitch(withoutAdbSwitch, Prefs.withoutAdb(this));
        if (engineGuessSwitch != null) renderSwitch(engineGuessSwitch, Prefs.engineGuess(this));
    }

    /** A tappable card with a title and a hint, without a switch. Returns the title, which the caller rewrites. */
    private TextView actionCard(LinearLayout column, String hint, View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(this);
        updateCard = card;
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20), dp(12), dp(20), dp(12));
        card.setClickable(true);
        card.setMinimumHeight(dp(64));
        card.setOnClickListener(onClick);
        TextView title = text("", 18, TEXT, true);
        card.addView(title);
        card.addView(text(hint, 16, MUTED, false));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = dp(8);
        column.addView(card, cardLp);
        return title;
    }

    /** Adds a tappable setting card with a drawn switch to the column and returns the switch. */
    private Toggle addSetting(LinearLayout column, String title, String hint, View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20), dp(12), dp(20), dp(12));
        card.setBackgroundDrawable(pressable(CARD, NEUTRAL, 1));
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

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = dp(8);
        column.addView(card, cardLp);
        return t;
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
