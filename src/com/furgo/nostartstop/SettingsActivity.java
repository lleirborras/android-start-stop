package com.furgo.nostartstop;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

    private float density;
    private boolean narrow;
    private Toggle captureSwitch;
    private Toggle autoUpdateSwitch;
    private Toggle telemetrySwitch;   // null when the build has no PostHog key
    private Toggle withoutAdbSwitch;
    private Toggle otherUnitsSwitch;
    private Toggle otherCarsSwitch;
    private Toggle engineGuessSwitch;

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
        // Back from the car setup: the car card and the other cars switch may have changed.
        setContentView(build());
        renderSwitches();
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

        carCard(page);

        captureSwitch = addSetting(page, "Record car data",
                "Logs raw car data. May include vehicle details.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleCapture();
                    }
                });
        autoUpdateSwitch = addSetting(page, "Check for updates automatically",
                "Once a day. Never installs by itself.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleAutoUpdate();
                    }
                });
        if (Telemetry.configured()) {
            telemetrySwitch = addSetting(page, "Share diagnostics",
                    "Sends this app's log to the developer.",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            toggleTelemetry();
                        }
                    });
        }
        withoutAdbSwitch = addSetting(page, "Beta: reach the car without adb",
                "Try it if the card says NO LINK TO CAR.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleWithoutAdb();
                    }
                });
        otherUnitsSwitch = addSetting(page, "Beta: FYT and Microntek head units",
                "PSA with a Raise CAN box only. Untested.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleOtherUnits();
                    }
                });
        otherCarsSwitch = addSetting(page, "Beta: other cars",
                "VW, Honda, Haval, PSA on other boxes. Untested.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleOtherCars();
                    }
                });
        engineGuessSwitch = addSetting(page, "Beta: guess the engine start",
                "Switch 15 s after ignition on when the head unit gives no engine speed.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleEngineGuess();
                    }
                });

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

    private void carCard(LinearLayout page) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(16), dp(24), dp(20));
        card.setBackgroundDrawable(shape(CARD, 16, 1, CARD_EDGE));
        TextView t = text("CAR", 13, MUTED, true);
        t.setLetterSpacing(0.14f);
        card.addView(t);

        String detection = DeviceWatch.carDetection(this);
        String choice = Prefs.setupChoice(this);
        String confirmedFor = Prefs.setupDetection(this);
        int decision = CarSetup.decide(detection, choice, confirmedFor);
        TextView reports = text(CarSetup.detectionLabel(detection), 16, TEXT, false);
        reports.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams reportsLp = new LinearLayout.LayoutParams(-1, -2);
        reportsLp.topMargin = dp(8);
        card.addView(reports, reportsLp);
        String confirmed = choice == null ? "Not confirmed"
                : decision == CarSetup.ASK ? CarSetup.choiceLabel(choice) + ", confirmed for "
                + CarSetup.detectionName(confirmedFor) + ". Confirm again."
                : CarSetup.choiceLabel(choice);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.topMargin = dp(14);
        card.addView(text("Confirmed car", 16, TEXT, true), nameLp);
        TextView v = text(confirmed, 15, decision == CarSetup.ASK ? WARN : MUTED, false);
        v.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(-1, -2);
        vLp.topMargin = dp(2);
        card.addView(v, vLp);

        TextView change = text("Change car", 18, TEXT, true);
        change.setGravity(Gravity.CENTER);
        change.setPadding(dp(28), 0, dp(28), 0);
        change.setBackgroundDrawable(decision == CarSetup.ASK ? pressable(PRIMARY, PRIMARY_PRESSED, 0)
                : pressable(NEUTRAL, NEUTRAL_PRESSED, 2));
        change.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(SettingsActivity.this, SetupActivity.class));
            }
        });
        LinearLayout.LayoutParams changeLp = new LinearLayout.LayoutParams(-2, dp(48));
        changeLp.topMargin = dp(16);
        card.addView(change, changeLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        page.addView(card, lp);
    }

    private void log(String line) {
        LogFile.append(this, line);
    }

    private void toggleCapture() {
        boolean on = !Prefs.captureFrames(this);
        Prefs.setCaptureFrames(this, on);
        log(on ? "recording car data on" : "recording car data off");
        renderSwitches();
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

    private void toggleOtherUnits() {
        boolean on = !Prefs.otherUnits(this);
        Prefs.setOtherUnits(this, on);
        log(on ? "beta: FYT and Microntek head units on" : "beta: FYT and Microntek head units off");
        renderSwitches();
    }

    private void toggleOtherCars() {
        boolean on = !Prefs.otherCars(this);
        Prefs.setOtherCars(this, on);
        log(on ? "beta: other cars on" : "beta: other cars off");
        renderSwitches();
    }

    private void toggleEngineGuess() {
        boolean on = !Prefs.engineGuess(this);
        Prefs.setEngineGuess(this, on);
        log(on ? "beta: engine guess on" : "beta: engine guess off");
        renderSwitches();
    }

    private void renderSwitches() {
        renderSwitch(captureSwitch, Prefs.captureFrames(this));
        renderSwitch(autoUpdateSwitch, Prefs.autoUpdate(this));
        if (telemetrySwitch != null) renderSwitch(telemetrySwitch, Prefs.telemetry(this));
        renderSwitch(withoutAdbSwitch, Prefs.withoutAdb(this));
        renderSwitch(otherUnitsSwitch, Prefs.otherUnits(this));
        renderSwitch(otherCarsSwitch, Prefs.otherCars(this));
        renderSwitch(engineGuessSwitch, Prefs.engineGuess(this));
    }

    /** Adds a tappable setting card with a drawn switch to the page and returns the switch. */
    private Toggle addSetting(LinearLayout page, String title, String hint, View.OnClickListener onClick) {
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
        page.addView(card, cardLp);
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
