package com.furgo.nostartstop;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** What the controls and the status lines of the main screen mean. Text only, readable with no network. */
public class HelpActivity extends Activity {
    private static final int BG = 0xFF0B0E13;
    private static final int CARD = 0xFF151A22;
    private static final int CARD_EDGE = 0xFF232A35;
    private static final int TEXT = 0xFFEAEFF5;
    private static final int MUTED = 0xFF8792A2;
    private static final int ACCENT = 0xFF35D6A0;
    private static final int WARN = 0xFFFFB02E;
    private static final int OFF_EDGE = 0xFF5B6678;
    private static final int NEUTRAL = 0xFF1D2430;
    private static final int NARROW_DP = 900;

    private float density;
    private boolean narrow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        density = getResources().getDisplayMetrics().density;
        narrow = getResources().getConfiguration().screenWidthDp < NARROW_DP;
        setContentView(build());
    }

    private View build() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(narrow ? 16 : 32), dp(8), dp(narrow ? 16 : 32), dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(44));
        TextView title = text("HELP", 22, TEXT, true);
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

        LinearLayout first = column();
        LinearLayout second = narrow ? first : column();
        stateCard(first);
        engineStart(first);
        backgroundStart(first);
        headUnit(first);
        car(first);
        if (Telemetry.configured()) {
            diagnostics(second);
            sharing(second);
        }
        logCard(second);
        updates(second);
        troubleshooting(second);

        if (narrow) {
            page.addView(first, new LinearLayout.LayoutParams(-1, -2));
        } else {
            LinearLayout columns = new LinearLayout(this);
            columns.addView(first, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams secondLp = new LinearLayout.LayoutParams(0, -2, 1f);
            secondLp.leftMargin = dp(24);
            columns.addView(second, secondLp);
            page.addView(columns, new LinearLayout.LayoutParams(-1, -2));
        }

        TextView by = text("No Start-Stop " + MainActivity.versionName(this) + ", by Lleïr Borràs\n"
                + "github.com/lleirborras/android-start-stop", 14, MUTED, false);
        by.setGravity(Gravity.CENTER);
        by.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams byLp = new LinearLayout.LayoutParams(-1, -2);
        byLp.topMargin = dp(20);
        page.addView(by, byLp);

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
                            if (!isFinishing()) setContentView(build());
                        }
                    });
                }
            }
        });
        return scroll;
    }

    private void stateCard(LinearLayout column) {
        LinearLayout card = card(column, "Stop-start card");
        para(card, "The car does not report whether stop-start is on. The app works it out: the car turns stop-start on "
                + "at every engine start, and each switch sent flips it. That is why the card says \"assumed\". "
                + "The instrument cluster is always right.");
        item(card, "ON / OFF", "What the app assumes.");
        item(card, "UNKNOWN", "The app did not follow the ignition: Switch off at engine start is off, the ignition "
                + "was switched off, the head unit restarted, or the app gets no power messages from the head unit.");
        item(card, "Switch stop-start", "Sends one switch to the car, the same command as the car's own menu. Check "
                + "the instrument cluster after it.");
        item(card, "Started / Stopped", "Shown while the state is unknown. Tap what the instrument cluster shows: "
                + "Started if stop-start is on, Stopped if it is off. Sends nothing to the car. Greyed out while the app "
                + "cannot follow the ignition.");
        item(card, "Car shows the opposite", "Tap it when the instrument cluster disagrees with the card. It flips "
                + "the shown state and writes a line to the log, up to a daily limit. Sends nothing to the car.");
    }

    private void engineStart(LinearLayout column) {
        LinearLayout card = card(column, "Switch off at engine start");
        para(card, "The car turns stop-start on at every engine start. With this on, the app sends one switch after "
                + "each engine start, which turns it off.");
        item(card, "When it sends", "Once the engine runs: a battery reading of 13.0 V or more, at least 5 seconds "
                + "after the start. One switch per engine start, and none if you already switched from the screen "
                + "since that start. Nothing is sent if the engine is not seen running within 3 minutes.");
        item(card, "The app must be running", "The head unit stops the app at ignition off. At ignition on it "
                + "reopens only the app that was on screen. Leave this app on screen, or turn Background start on.");
        item(card, "The notification", "While it is on, a background service follows the ignition and shows a "
                + "silent notification, \"Watching for engine start\".");
        item(card, "A battery charger", "A charger that holds the battery at 13.0 V or more with the engine off can "
                + "make the app send one switch it should not.");
        item(card, "\"Automatic switch failed. Tap to switch.\"", "The switch could not be sent: the head unit "
                + "refused it, or there was no link to the car within 30 seconds. It is not tried again until the "
                + "next engine start. The message stays until you switch from the screen.");
    }

    private void headUnit(LinearLayout column) {
        LinearLayout card = card(column, "This head unit");
        DisplayMetrics m = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        item(card, "App version", MainActivity.versionName(this));
        item(card, "Android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        item(card, "Model", Build.MODEL);
        item(card, "Display", m.widthPixels + " x " + m.heightPixels + ", " + m.densityDpi + " dpi");
        item(card, "Background start", !StartListener.available(this) ? "Not allowed by this head unit"
                : StartListener.enabled(this) ? "On" : "Off");
    }

    private void car(LinearLayout column) {
        LinearLayout card = card(column, "Nothing changes in the car");
        para(card, "The app changes nothing in the car: no coding and no wiring. It sends the command the car's own "
                + "settings menu sends. Turn Switch off at engine start off, or uninstall the app, and the car behaves as it "
                + "left the factory.");
    }

    private void sharing(LinearLayout column) {
        LinearLayout card = card(column, "What Share diagnostics sends");
        para(card, "Nothing is sent before you answer the question on first launch, and nothing while the switch "
                + "is off.");
        item(card, "With each log line", "The line itself, the app version, the Android SDK level, the head unit's "
                + "model name and a random id made for this install. The id is not tied to you or to the vehicle.");
        item(card, "With Record car data on", "The log, and so what is sent, also holds raw car data that may "
                + "include vehicle details.");
        item(card, "Switching it off", "Discards the lines not sent yet and forgets the random id.");
    }

    private void logCard(LinearLayout column) {
        LinearLayout card = card(column, "The log");
        para(card, "The commands sent, the car's status frames, what the automatic switch saw and did, the assumed "
                + "state, boot, screen, shutdown and network events, and errors.");
        item(card, "Record car data", "Off by default. Also logs the raw frames from the car, engine and vehicle "
                + "speed, battery voltage and the head unit's identity. This may include vehicle details such as "
                + "the VIN. Leave it off unless you are asked for it.");
        item(card, "Beta: reach the car without adb", "Off by default. Some head units block the link to the car "
                + "for apps that are not part of the system, and the card says NO LINK TO CAR. This asks Android "
                + "to lift that block for this app only. The log says if it failed.");
        item(card, "Beta: FYT and Microntek head units", "Off by default. For head units that are not Topway, such "
                + "as Teyes, Joying or Dasaita, with a PSA car and a Raise CAN box. The app sends nothing if the head "
                + "unit is set up for another car or box. At engine start it waits for the engine speed. Microntek "
                + "gives none, so there it never switches by itself unless the guess below is on.");
        item(card, "Beta: guess the engine start", "Off by default. Where the head unit gives no engine speed, "
                + "the engine is taken as running 15 s after ignition on. A switch sent before the engine runs is "
                + "undone by the start.");
        item(card, "Beta: other cars", "Off by default. On Topway head units set up for VW MQB on a Hiworld or "
                + "Raise box, Honda on Hiworld, Haval on Raise, or PSA on a Simple Soft, Binary or LuZheng box, the app uses that car's own command and reads the "
                + "setting back. At engine start it always sends off; a tap sends the opposite of what the car "
                + "reports, and the card shows the state the car reports. Read from the head unit maker's app, not "
                + "tested on these cars.");
        item(card, "The file", "Android/data/com.furgo.nostartstop/files/nostartstop.log on the head unit's "
                + "internal storage. Copy it to a pendrive with the head unit's file manager.");
    }

    private void updates(LinearLayout column) {
        LinearLayout card = card(column, "Updates");
        item(card, "Check for updates", "Needs a network. The button turns into Install vX.Y.Z when a newer release "
                + "exists, or says Up to date. Tap Install; Android asks you to confirm.");
        item(card, "The first time", "Android says the app is not allowed to install unknown apps. Tap Settings, "
                + "allow it for No Start-Stop, go back and tap Install again.");
        item(card, "Check for updates automatically", "About once a day and after a boot, only with a network. It "
                + "shows a notification and never downloads or installs by itself.");
        item(card, "After an update", "The app closes. Open it again. Settings and the log are kept.");
    }

    private void backgroundStart(LinearLayout column) {
        LinearLayout card = card(column, "Background start");
        para(card, "Lets Android start the app without its screen, when the head unit powers on and after it stopped "
                + "the app. Switch off at engine start then also works when another app was on screen.");
        item(card, "Turn it on", "Tap Background start at the top, then Open settings, and allow notification access "
                + "for No Start-Stop. The app reads no notification; Android starts apps that have this access.");
        item(card, "What it does then", "A start in the background sends nothing by itself. The app sends its "
                + "switch once the head unit wakes at the next ignition on and the engine runs, if Android kept the app "
                + "running until then.");
        item(card, "Check that it works", "After a drive where you did not open the app, the log shows "
                + "\"listener: connected\" and \"watch: service started (by the notification listener)\", then "
                + "\"power state 5\", \"auto: ignition on, waiting for the engine to run\" and \"auto: engine "
                + "running\". Without those lines no switch was sent in the background: open the app before you "
                + "drive.");
        item(card, "\"This head unit does not allow a background start.\"", "Android 10 and older do not give "
                + "notification access on low-memory units.");
        item(card, "Depends on the head unit", "Measured on one Android 10 unit. Some head units stop apps "
                + "themselves and may not let Android start them again. \"This head unit\" below shows its Android version.");
    }

    private void diagnostics(LinearLayout column) {
        LinearLayout card = card(column, "Diagnostics line");
        para(card, "The line under the log shows what happens to the log lines while Share diagnostics is on. "
                + "Lines are stored on the head unit until there is a network, then sent to the developer.");
        state(card, 0, "Diagnostics off", "Nothing is stored for sending and nothing is sent.");
        state(card, ACCENT, "Diagnostics on, nothing waiting", "Nothing was sent since the app started.");
        state(card, MUTED, "3 lines waiting to send", "They go out in the next batch, a few seconds later.");
        state(card, ACCENT, "Sending 3 lines…", "A request is on its way. The dot pulses.");
        state(card, ACCENT, "Sending: 500 of 1240 lines", "A backlog goes out in requests of up to 500 lines. "
                + "The bar above the line fills as they go.");
        state(card, ACCENT, "Sent 3 lines at 14:32", "The last batch went out. Then: All sent at 14:32.");
        state(card, WARN, "Send failed, 1240 lines from 3 days waiting", "No network, or the upload failed. The "
                + "lines stay stored and go out at the next try. Up to 30 days or 5 MB are kept; past that the "
                + "oldest days are dropped.");
    }

    private void troubleshooting(LinearLayout column) {
        LinearLayout card = card(column, "If something is wrong");
        item(card, "\"This app may be harmful\" when installing or updating", "Google Play Protect shows this for "
                + "apps it does not know; this one is not on Google Play. Tap Install anyway. If an update stopped "
                + "there, tap Install again. To stop the prompt, turn off Scan apps with Play Protect in the Play Store.");
        item(card, "NO LINK TO CAR", "The app cannot reach the head unit's car interface. The switch button is grey "
                + "and nothing is sent. It only works on Topway head units.");
        item(card, "\"No answer from the car.\"", "The switch was sent but not confirmed within 3 seconds. Check the "
                + "instrument cluster before you tap again: it may have worked. With \"Nothing sent.\" after it, "
                + "the car gave no status in time and no switch went out.");
        item(card, "Nothing happens at engine start", "Check that Switch off at engine start is on, and that the app "
                + "was running: open it once and leave it on screen when you switch the ignition off, or turn "
                + "Background start on. The log shows \"wake\" and \"auto:\" lines when the app saw the engine start.");
        item(card, "Stop-start is on though the card says OFF", "Tap Car shows the opposite, then Switch stop-start "
                + "once.");
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private LinearLayout card(LinearLayout column, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(16), dp(24), dp(20));
        card.setBackgroundDrawable(shape(CARD, 16, 1, CARD_EDGE));
        TextView t = text(title.toUpperCase(Locale.ROOT), 13, MUTED, true);
        t.setLetterSpacing(0.14f);
        card.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        column.addView(card, lp);
        return card;
    }

    private void para(LinearLayout card, String body) {
        TextView v = text(body, 16, TEXT, false);
        v.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        card.addView(v, lp);
    }

    private void item(LinearLayout card, String name, String body) {
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.topMargin = dp(14);
        card.addView(text(name, 16, TEXT, true), nameLp);
        TextView v = text(body, 15, MUTED, false);
        v.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(2);
        card.addView(v, lp);
    }

    /** One state of the diagnostics line, drawn as on the main screen; dot 0 is the ring shown while sharing is off. */
    private void state(LinearLayout card, int dot, String line, String body) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View d = new View(this);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        if (dot == 0) shape.setStroke(dp(1.5f), OFF_EDGE);
        else shape.setColor(dot);
        d.setBackgroundDrawable(shape);
        row.addView(d, new LinearLayout.LayoutParams(dp(10), dp(10)));
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(0, -2, 1f);
        lineLp.leftMargin = dp(10);
        row.addView(text(line, 16, TEXT, true), lineLp);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(14);
        card.addView(row, rowLp);
        TextView v = text(body, 15, MUTED, false);
        v.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(2);
        lp.leftMargin = dp(20);
        card.addView(v, lp);
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

    private int dp(float v) {
        return Math.round(v * density);
    }
}
