package com.furgo.nostartstop;

import android.app.Activity;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;

public class MainActivity extends Activity implements CanBox.Listener {
    private static final int BG = 0xFF0B0E13;
    private static final int CARD = 0xFF151A22;
    private static final int CARD_EDGE = 0xFF232A35;
    private static final int TEXT = 0xFFEAEFF5;
    private static final int MUTED = 0xFF8792A2;
    private static final int ACCENT = 0xFF35D6A0;   // wanted state: stop-start off / service closed
    private static final int WARN = 0xFFFFB02E;     // stop-start still active
    private static final int ON_ACCENT = 0xFF04140E;

    private static final int MAX_LOG = 50;
    private static final long REQUERY_MS = 5000;
    private static final long STALE_MS = 12000;

    private static final int LINK_WAITING = 0, LINK_LIVE = 1, LINK_DOWN = -1;

    private final Handler handler = new Handler();
    private final ArrayDeque<String> log = new ArrayDeque<String>();

    private CanBox box;
    private boolean opened;
    private int link = LINK_WAITING;
    private Boolean enabled;   // null = unknown
    private Boolean closed;    // null = unknown
    private long lastFrameAt;

    private float density;
    private TextView linkView;
    private TextView bootPill;
    private TextView logView;
    private ScrollView logScroll;
    private Card stopCard;
    private Card serviceCard;

    private final Runnable requery = new Runnable() {
        @Override
        public void run() {
            if (link == LINK_LIVE && SystemClock.elapsedRealtime() - lastFrameAt > STALE_MS) {
                enabled = null;
                closed = null;
                link = LINK_WAITING;
                render();
            }
            if (opened) box.query();
            handler.postDelayed(this, REQUERY_MS);
        }
    };

    private static final class Card {
        TextView state;
        TextView detail;
        TextView button;
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
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enabled = null;
        closed = null;
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
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(requery);
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

    private void toggleStopStart() {
        boolean target = enabled == null ? false : !enabled;
        // Unknown service state is sent as closed so a tap can never undo a permanent close.
        if (closed == null) onLog("service state unknown, sending it as closed");
        box.set(target, closed == null ? true : closed);
    }

    private void toggleService() {
        boolean target = closed == null ? true : !closed;
        if (enabled == null) onLog("stop-start state unknown, sending it as off");
        box.set(enabled == null ? false : enabled, target);
    }

    private void toggleBoot() {
        Prefs.setApplyAtBoot(this, !Prefs.applyAtBoot(this));
        renderBoot();
    }

    private void render() {
        String unknownDetail = link == LINK_DOWN ? "CAN box unreachable" : "Waiting for CAN box";
        linkView.setText(link == LINK_LIVE ? "●  CAN BOX LIVE"
                : link == LINK_DOWN ? "○  CAN BOX UNREACHABLE" : "○  WAITING FOR CAN BOX");
        linkView.setTextColor(link == LINK_LIVE ? ACCENT : link == LINK_DOWN ? WARN : MUTED);

        if (enabled == null) {
            setState(stopCard, "UNKNOWN", MUTED, unknownDetail);
            setButton(stopCard.button, "Turn off", true);
        } else if (enabled) {
            setState(stopCard, "● ON", WARN, "Engine stops at standstill");
            setButton(stopCard.button, "Turn off", true);
        } else {
            setState(stopCard, "● OFF", ACCENT, "Engine keeps running. Resets at next ignition");
            setButton(stopCard.button, "Turn back on", false);
        }

        if (closed == null) {
            setState(serviceCard, "UNKNOWN", MUTED, unknownDetail);
            setButton(serviceCard.button, "Close permanently", true);
        } else if (closed) {
            setState(serviceCard, "● CLOSED", ACCENT, "Permanently closed. Car cannot re-enable stop-start");
            setButton(serviceCard.button, "Make controllable", false);
        } else {
            setState(serviceCard, "● CONTROLLABLE", WARN, "Car may re-enable stop-start");
            setButton(serviceCard.button, "Close permanently", true);
        }
        renderBoot();
    }

    private void renderBoot() {
        boolean on = Prefs.applyAtBoot(this);
        bootPill.setText(on ? "ON" : "OFF");
        bootPill.setTextColor(on ? ON_ACCENT : MUTED);
        bootPill.setBackgroundDrawable(shape(on ? ACCENT : 0xFF222A35, 999, 0, 0));
    }

    private void setState(Card c, String text, int color, String detail) {
        c.state.setText(text);
        c.state.setTextColor(color);
        c.detail.setText(detail);
    }

    private void setButton(TextView b, String label, boolean primary) {
        b.setText(label);
        b.setTextColor(primary ? ON_ACCENT : TEXT);
        b.setBackgroundDrawable(primary
                ? pressable(ACCENT, 0xFF7BEBC4, 0)
                : pressable(0xFF1D2430, 0xFF2B3544, 2));
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(32), dp(24), dp(32), dp(24));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, new LinearLayout.LayoutParams(0, -1, 2f));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("NO START-STOP", 22, TEXT, true);
        title.setLetterSpacing(0.12f);
        header.addView(title);
        String v = versionName();
        // Release builds are named like "0.3.0"; local builds are "dev".
        TextView version = text(Character.isDigit(v.charAt(0)) ? "v" + v : v, 14, MUTED, false);
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(0, -2, 1f);
        versionLp.leftMargin = dp(12);
        header.addView(version, versionLp);
        linkView = text("", 14, MUTED, true);
        header.addView(linkView);
        left.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cardsLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cardsLp.topMargin = dp(16);
        left.addView(cards, cardsLp);

        stopCard = card("ENGINE STOP-START", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleStopStart();
            }
        });
        serviceCard = card("STOP-START SERVICE", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleService();
            }
        });
        cards.addView((View) stopCard.state.getParent(), new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -1, 1f);
        second.leftMargin = dp(16);
        cards.addView((View) serviceCard.state.getParent(), second);

        LinearLayout boot = new LinearLayout(this);
        boot.setOrientation(LinearLayout.HORIZONTAL);
        boot.setGravity(Gravity.CENTER_VERTICAL);
        boot.setPadding(dp(24), dp(12), dp(24), dp(12));
        boot.setBackgroundDrawable(pressable(CARD, 0xFF1D2430, 1));
        boot.setClickable(true);
        boot.setMinimumHeight(dp(64));
        boot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleBoot();
            }
        });
        LinearLayout bootText = new LinearLayout(this);
        bootText.setOrientation(LinearLayout.VERTICAL);
        bootText.addView(text("Apply at boot", 18, TEXT, true));
        TextView bootHint = text("At boot, turns stop-start off and closes the service permanently.", 14, MUTED, false);
        bootText.addView(bootHint);
        boot.addView(bootText, new LinearLayout.LayoutParams(0, -2, 1f));
        bootPill = text("OFF", 18, MUTED, true);
        bootPill.setGravity(Gravity.CENTER);
        bootPill.setMinWidth(dp(88));
        bootPill.setPadding(dp(16), dp(10), dp(16), dp(10));
        boot.addView(bootPill);
        LinearLayout.LayoutParams bootLp = new LinearLayout.LayoutParams(-1, -2);
        bootLp.topMargin = dp(16);
        left.addView(boot, bootLp);

        LinearLayout logCard = new LinearLayout(this);
        logCard.setOrientation(LinearLayout.VERTICAL);
        logCard.setPadding(dp(20), dp(16), dp(20), dp(16));
        logCard.setBackgroundDrawable(shape(0xFF0F1319, 16, 1, CARD_EDGE));
        TextView logTitle = text("LOG", 12, MUTED, true);
        logTitle.setLetterSpacing(0.16f);
        logCard.addView(logTitle);
        TextView logPath = text(LogFile.file(this).getPath(), 10, MUTED, false);
        logCard.addView(logPath);
        logScroll = new ScrollView(this);
        logView = text("", 12, MUTED, false);
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
        box.setPadding(dp(24), dp(20), dp(24), dp(24));
        box.setBackgroundDrawable(shape(CARD, 20, 1, CARD_EDGE));

        Card c = new Card();
        TextView t = text(title, 14, MUTED, true);
        t.setLetterSpacing(0.14f);
        box.addView(t);

        c.state = text("", 32, MUTED, true);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        stateLp.topMargin = dp(8);
        c.state.setGravity(Gravity.BOTTOM);
        box.addView(c.state, stateLp);

        c.detail = text("", 16, MUTED, false);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(4);
        box.addView(c.detail, detailLp);

        c.button = text("", 22, TEXT, true);
        c.button.setGravity(Gravity.CENTER);
        c.button.setClickable(true);
        c.button.setOnClickListener(onButton);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, dp(72));
        btnLp.topMargin = dp(20);
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
