package com.furgo.nostartstop;

import android.app.Activity;
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

/** Asks which car this is. Nothing is sent to the car before a choice is confirmed here, see {@link CarSetup}. */
public class SetupActivity extends Activity {
    private static final int BG = 0xFF0B0E13;
    private static final int CARD = 0xFF151A22;
    private static final int CARD_EDGE = 0xFF232A35;
    private static final int TEXT = 0xFFEAEFF5;
    private static final int MUTED = 0xFF8792A2;
    private static final int ACCENT = 0xFF35D6A0;
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
        String confirmed = Prefs.setupChoice(this);
        selected = detection.equals(Prefs.setupDetection(this)) && offered(confirmed) ? confirmed
                : offered(CarSetup.suggestion(detection)) ? CarSetup.suggestion(detection) : null;
        setContentView(build());
    }

    private boolean offered(String choice) {
        for (String c : CarSetup.choices(detection)) {
            if (c.equals(choice)) return true;
        }
        return false;
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
        TextView hint = text("Pick your car. The app sends nothing to the car until you confirm.", 16, MUTED, false);
        hint.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(8);
        card.addView(hint, hintLp);
        for (String c : CarSetup.choices(detection)) addChoice(card, c);

        TextView confirm = text("Confirm", 20, selected != null ? TEXT : MUTED, true);
        confirm.setGravity(Gravity.CENTER);
        confirm.setBackgroundDrawable(selected != null ? pressable(PRIMARY, PRIMARY_PRESSED, 0)
                : pressable(NEUTRAL, NEUTRAL_PRESSED, 2));
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm();
            }
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(-1, dp(64));
        confirmLp.topMargin = dp(20);
        card.addView(confirm, confirmLp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = dp(8);
        page.addView(card, cardLp);

        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(page, new FrameLayout.LayoutParams(-1, -2));
        // The window's real width decides: split screen does not always come with a new configuration.
        scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r > l && (r - l < dp(NARROW_DP)) != narrow) {
                    narrow = !narrow;
                    redraw();
                }
            }
        });
        return scroll;
    }

    private void redraw() {
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) setContentView(build());
            }
        });
    }

    /** One selectable row with a round mark, filled when selected. */
    private void addChoice(LinearLayout card, final String choice) {
        boolean on = choice.equals(selected);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(64));
        row.setBackgroundDrawable(on ? pressable(NEUTRAL, NEUTRAL_PRESSED, 2, ACCENT)
                : pressable(CARD, NEUTRAL, 1, CARD_EDGE));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selected = choice;
                setContentView(build());
            }
        });
        View mark = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setStroke(dp(2), on ? ACCENT : OFF_EDGE);
        if (on) d.setColor(ACCENT);
        mark.setBackgroundDrawable(d);
        row.addView(mark, new LinearLayout.LayoutParams(dp(20), dp(20)));
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, -2, 1f);
        labelLp.leftMargin = dp(16);
        row.addView(text(CarSetup.choiceLabel(choice), 18, on ? TEXT : MUTED, on), labelLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        card.addView(row, lp);
    }

    private void confirm() {
        if (selected == null) return;
        Prefs.setSetup(this, selected, detection);
        if (CarSetup.profileCode(selected) != null && !Prefs.otherCars(this)) {
            Prefs.setOtherCars(this, true);
            LogFile.append(this, "beta: other cars on");
        }
        LogFile.append(this, "car setup: confirmed " + CarSetup.choiceLabel(selected) + " for "
                + CarSetup.detectionName(detection));
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
