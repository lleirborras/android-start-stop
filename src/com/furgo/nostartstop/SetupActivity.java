package com.furgo.nostartstop;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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

/** Asks which car this is. Nothing is sent to the car before a choice is confirmed here, see {@link CarSetup}. */
public class SetupActivity extends Activity {

    private static final int BG = 0xFF0B0E13;
    private static final int CARD = 0xFF151A22;
    private static final int CARD_EDGE = 0xFF232A35;
    private static final int TEXT = 0xFFEAEFF5;
    private static final int MUTED = 0xFF8792A2;
    private static final int ACCENT = 0xFF35D6A0;
    private static final int WARN = 0xFFFFB02E;
    private static final int PRIMARY = 0xFF187A5A;
    private static final int PRIMARY_PRESSED = 0xFF1F8F6C;
    private static final int NEUTRAL = 0xFF1D2430;
    private static final int NEUTRAL_PRESSED = 0xFF2B3544;
    private static final int OFF_EDGE = 0xFF5B6678;
    private static final int NARROW_DP = 900;

    private float density;
    private boolean narrow;
    private String detection;
    private String selected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        density = getResources().getDisplayMetrics().density;
        narrow = getResources().getConfiguration().screenWidthDp < NARROW_DP;
        detection = DeviceWatch.carDetection(this);
        selected = CarSetup.suggestion(detection, Prefs.setupChoice(this), CanBox.betaBuild(this));
        setContentView(build());
    }

    private View build() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(narrow ? 16 : 32), dp(8), dp(narrow ? 16 : 32), dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(44));
        TextView title = text("YOUR CAR", 22, TEXT, true);
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

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(16), dp(24), dp(20));
        card.setBackgroundDrawable(shape(CARD, 16, 1, CARD_EDGE));
        TextView reports = text(CarSetup.detectionLabel(detection), 18, TEXT, true);
        reports.setLineSpacing(0, 1.15f);
        card.addView(reports);
        TextView hint = text("Pick your CAN box. The app sends nothing to the car until you confirm.", 16, MUTED,
                false);
        hint.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(8);
        card.addView(hint, hintLp);
        addDropdown(card);
        String warning = CarSetup.mismatch(detection, selected);
        if (warning != null) {
            TextView w = text(warning, 16, WARN, false);
            w.setLineSpacing(0, 1.15f);
            LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(-1, -2);
            wLp.topMargin = dp(12);
            card.addView(w, wLp);
        }

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = dp(8);
        page.addView(card, cardLp);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page, new FrameLayout.LayoutParams(-1, -2));

        // Below the scrolling part, so the buttons stay on screen however many choices there are.
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(dp(narrow ? 16 : 32), dp(8), dp(narrow ? 16 : 32), dp(16));
        TextView confirm = text(selected == null ? "Choose a CAN box"
                        : CarSetup.NOT_LISTED.equals(selected) ? "Confirm: send nothing" : "Confirm",
                20, selected != null ? TEXT : MUTED, true);
        confirm.setGravity(Gravity.CENTER);
        confirm.setEnabled(selected != null);
        confirm.setBackgroundDrawable(selected != null ? pressable(PRIMARY, PRIMARY_PRESSED, 0)
                : pressable(NEUTRAL, NEUTRAL_PRESSED, 2));
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm();
            }
        });
        buttons.addView(confirm, new LinearLayout.LayoutParams(0, dp(64), 1f));
        TextView notNow = text("Not now", 20, MUTED, true);
        notNow.setGravity(Gravity.CENTER);
        notNow.setPadding(dp(32), 0, dp(32), 0);
        notNow.setBackgroundDrawable(pressable(NEUTRAL, NEUTRAL_PRESSED, 2));
        notNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams notNowLp = new LinearLayout.LayoutParams(-2, dp(64));
        notNowLp.leftMargin = dp(12);
        buttons.addView(notNow, notNowLp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        // The window's real width decides: split screen does not always come with a new configuration.
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r > l && (r - l < dp(NARROW_DP)) != narrow) {
                    narrow = !narrow;
                    redraw();
                }
            }
        });
        return root;
    }

    private void redraw() {
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) setContentView(build());
            }
        });
    }

    /** The chosen CAN box with a down arrow; a tap opens the list. An amber stripe marks an untested one. */
    private void addDropdown(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(12), dp(20), dp(12));
        row.setMinimumHeight(dp(64));
        Drawable bg = pressable(NEUTRAL, NEUTRAL_PRESSED, 2, selected == null ? OFF_EDGE : ACCENT);
        if (selected != null && CarSetup.beta(selected)) {
            // An amber stripe along the left edge: the row's own shape drawn over an amber one, shifted right.
            LayerDrawable striped = new LayerDrawable(new Drawable[]{shape(WARN, 16, 0, 0), bg});
            striped.setLayerInset(1, dp(6), 0, 0, 0);
            bg = striped;
        }
        row.setBackgroundDrawable(bg);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CanBoxPicker.show(SetupActivity.this, detection, selected, new CanBoxPicker.Done() {
                    @Override
                    public void picked(String choice) {
                        selected = choice;
                        setContentView(build());
                    }
                });
            }
        });
        LinearLayout label = new LinearLayout(this);
        label.setOrientation(LinearLayout.VERTICAL);
        label.addView(text(selected == null ? "Choose your CAN box" : CarSetup.choiceLabel(selected), 18,
                selected == null ? MUTED : TEXT, selected != null));
        if (selected != null && CarSetup.beta(selected)) {
            label.addView(text("Untested. It may do nothing or the wrong thing.", 16, MUTED, false));
        }
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(text("▾", 24, TEXT, true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(16);
        card.addView(row, lp);
    }

    private void confirm() {
        if (selected == null) return;
        Prefs.setSetup(this, selected, detection);
        LogFile.append(this, "car setup: confirmed " + CarSetup.choiceLabel(selected) + " for "
                + CarSetup.detectionName(detection)
                + (CarSetup.mismatch(detection, selected) != null ? ", which it does not match" : ""));
        finish();
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
        return pressable(normal, pressed, strokeDp, CARD_EDGE);
    }

    private StateListDrawable pressable(int normal, int pressed, int strokeDp, int edge) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, shape(pressed, 16, strokeDp, edge));
        s.addState(new int[]{}, shape(normal, 16, strokeDp, edge));
        return s;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
