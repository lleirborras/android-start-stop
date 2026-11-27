# No Start-Stop

By Lleïr Borràs.

<img src="res/mipmap-xxxhdpi/ic_launcher.png" alt="App icon" width="96" align="right">

Android app for Topway head units (TS18 and similar) that switches the PSA engine stop-start feature off through the Raise CAN box, the same way the built-in "Original Car" settings app does.

Built for a Citroën Jumpy (2015–23) with CAN box setting `02: Raise` / `96: PSA Peugeot Citroen`, and tested on a real Jumpy head unit (Android 10). See [Status](#status).

The Jumpy shares its platform and electronics with these vans, so the app should work on them with the same head unit and CAN box setting. None of them has been tested:

| Brand | Van | Passenger version |
|---|---|---|
| Citroën | Jumpy / Dispatch | SpaceTourer |
| Peugeot | Expert | Traveller |
| Opel / Vauxhall | Vivaro (2019 on) | Zafira Life / Vivaro Life |
| Toyota | ProAce (2016 on) | ProAce Verso |
| Fiat | Scudo (2022 on) | Ulysse |

## Why

On recent Euro 6 versions of these vans the physical stop-start button is gone, and the plug-in modules that used to press it for you no longer work. Stop-start comes back on at every engine start, so the only way left is to go through the touchscreen menu on every trip. This app does that step for you, from one button or by itself when the engine starts.

It changes nothing in the car: no ECU reflash, no coding, no wiring. It sends the same command the car's own settings menu sends. Turn the automatic option off or uninstall the app and the van behaves exactly as it left the factory, for example for an inspection.

![Main screen during a drive, after the automatic switch: stop-start off (assumed)](docs/main.png)

![After a tap: sent, check the instrument cluster](docs/sent.png)

The screenshots are from an emulator at 1920x720 with a simulated CAN box: the main screen during a drive, after **Switch off at engine start** sent its switch, and the same screen right after a tap. On a device without the Topway framework the button is grey and sends nothing:

![No link to the car](docs/unreachable.png)

While the state is unknown, **Started** and **Stopped** set it by hand from what the instrument cluster shows:

![Unknown state with the Started and Stopped buttons](docs/set-by-hand.png)

In split screen the same screen is one scrolling column:

![Split screen: one column](docs/split.png)

In builds that can share diagnostics, the line under the log shows when log lines are being sent:

![Line under the log: sending 2 lines](docs/diagnostics.png)

A backlog stored while offline goes out in several requests, and the rule above the line fills as they go:

![Line under the log: sending 505 of 1233 lines, bar partly filled](docs/progress.png)

With no network it shows how many lines, from how many days, are stored for the next try:

![Line under the log: send failed, 2466 lines from 3 days waiting](docs/waiting.png)

**Help** in the header opens a screen that explains each control, the diagnostics line, the log, updates and what to check when something is wrong:

![Help screen](docs/help.png)

## What it does

- **Switch stop-start**: one button that sends one switch command to the car. The command is a toggle: each tap turns stop-start off if it was on, and on if it was off.
- **Assumed state**: the car does not report whether stop-start is on or off, so the card shows the state the app assumes, as a large **OFF** (white), **ON** (green) or **UNKNOWN** (grey). Beside the word is the stop-start symbol, the "A" in a circular arrow: struck through for OFF, plain for ON, replaced by a dashed ring with "?" for UNKNOWN. OFF and ON carry the word "assumed"; the card says it is not read from the car. The instrument cluster shows a message when stop-start is switched off or on; look there to be sure. See [the assumed state](#the-assumed-stop-start-state).
- **Help**: opens a screen, readable with no network, that explains the stop-start card and its buttons, **Switch off at engine start**, **Background start** and how to check that it works, each state of the diagnostics line and what **Share diagnostics** sends (both only in builds that can share diagnostics), the log and where its file is, updates, and what to do about the Play Protect prompt, no link, no answer from the car and no switch at engine start. A **This head unit** card shows the app version, the Android version, the model, the display and whether background start is on.
- **Split screen**: in a window narrower than 900 dp the screen is one scrolling column: header, stop-start card with its buttons below the text, one setting per row, then the log.
- **Started** / **Stopped**: shown while the state is UNKNOWN. Read the instrument cluster and tap **Started** if stop-start is on, **Stopped** if it is off. They carry the colours of the state they set: **Started** green like ON, **Stopped** white like OFF. The card then shows that state as assumed and says "Set by hand. Nothing sent to the car." Greyed out while **Switch off at engine start** is off or its background service has no link to the head unit, because then nothing would end the state at the next ignition off.
- **Car shows the opposite**: tap it when the instrument cluster disagrees with the assumed state. It writes one line to the log with what led to the assumed state and flips the shown state. It sends nothing to the car. While the state is unknown, **Started** and **Stopped** take its place. It is greyed out while a tap's command is being sent, while **Switch off at engine start** is sending ("Switching…"), and for 2 seconds after each report. The card then says "Noted in the log. Nothing sent to the car.", or, past the daily limit of report lines, "Changed, but not logged: too many reports today. Nothing sent to the car."
- **Feedback**: a tap first asks the CAN box for a fresh status frame and picks the command from it, then sends. The button shows "Sending…" meanwhile. The card then says:
  - "Sent at HH:mm. Check the instrument cluster." once the CAN box echoes the command. When the command's bit is 0 the echo cannot confirm it, so this shows as soon as it is sent.
  - "No answer from the car." when the command was sent but not echoed within 3 seconds. Check the instrument cluster before tapping again: the switch may have worked.
  - "No answer from the car. Nothing sent." when no status frame came within 3 seconds, and "Could not send to the car. Nothing sent." when the head unit refused the command.
  - "Not connected to the car. Nothing sent." for a tap while the indicator next to the title says "NO LINK TO CAR".

  Taps are ignored while a command is pending, for 2 seconds after each command is sent, and while **Switch off at engine start** is sending ("Switching…"), so a double tap does not switch it straight back. When idle, the card shows the last switch sent in the current ignition cycle, for example "Last switch: 08:41, automatic".
- **Background start**: off by default; the header shows **Background start: Off** or **On**. Tap it to open Android's notification access screen and switch on No Start-Stop there. Android then starts the app in the background when the head unit powers on, and again after the app was stopped, so **Switch off at engine start** can work when another app was on screen: it then sends after the next engine crank it sees. The app reads no notification; see [background start](#background-start).
- **Switch off at engine start**: off by default. When on, the app sends one switch command each time the car starts. The car turns stop-start on at every engine start, so one switch turns it off. See [how it works and its limits](#switch-off-at-engine-start-how-and-limits).
- **Record car data**: off by default. When on, the log also records the raw frames the CAN box passes on from the car, engine speed (rpm) and vehicle speed, battery voltage and the head unit's identity and configuration, to help find where the car reports the real stop-start state. This data may include vehicle details (for example the VIN, speed or position), so leave it off unless you are helping with that.
- **Log**: the commands sent, the CAN box status frames, the CAN box function frame, what the automatic switch saw and did (ignition, engine starts, battery voltage), the assumed state, boot, screen, shutdown and network events, and any errors. See [Getting the log off the head unit](#getting-the-log-off-the-head-unit).
- **Check for updates**: downloads and installs the latest release when you ask for it.
- **Check for updates automatically**: on by default. About once a day, and after a boot once there is a connection, the app checks for a new release and shows a notification. It never installs by itself. See [Updating from the app](#updating-from-the-app).
- **Beta: reach the car without adb**: off by default. For head units where the log shows `TWUtil unavailable`. Asks Android to stop hiding the vendor class `android.tw.john.TWUtil` from this app, so `adb shell settings put global hidden_api_policy 1` is not needed. Seen working on an Android 14 emulator; not tested on a head unit that needs it. Turning it on tries at once; turning it off takes effect the next time the app starts. A failure is logged as `beta: reach the car without adb failed: …`.
- **Beta: FYT and Microntek head units**: off by default. On head units without the Topway framework, reaches the CAN box through FYT / SYU (Teyes, Joying, Mekede, ATOTO) or Microntek MTCD/MTCE (Dasaita, Xtrons, Pumpkin) instead. Only for PSA with a Raise CAN box: it sends nothing unless the head unit reports FYT canbus id 339 or Microntek CAN box type 50, and logs why (`beta: FYT canbus id … is not PSA with a Raise box`). The tap switch works. **Switch off at engine start** follows the ignition the head unit reports (FYT ACC, Microntek `carpower`). FYT also reports engine speed; the switch is sent once rpm is above 0, and again when rpm shows the engine started again after a switch (a stall, or a start after a guess). Microntek gives no engine speed, so without **Beta: guess the engine start** it never switches by itself and the log says so (`auto: no engine signal on this head unit, nothing sent; …`). Read from vendor firmware and tested only on an emulator against a stand-in service, never on such a head unit.
- **Beta: guess the engine start**: off by default. For **Beta: FYT and Microntek head units** only: where the head unit gives no engine speed (always on Microntek, on FYT until it has once reported rpm), the engine is taken as running 15 s after ignition on, which the log says (`engine running (assumed, 15 s after ignition on)`). A switch sent before the engine runs is undone by the engine start; on FYT the engine speed that follows sends it again.
- **Share diagnostics**: asked on first launch, with a switch to change it later. Sends the app's log lines to the developer. The line under the log shows what it is doing: off, lines waiting, sending or sent. Only in builds made with a PostHog key, such as releases. See [Diagnostics](#diagnostics).

There is no "keep it off" or lock option: the vendor app's "permanently closed" setting has no effect on this van, and stop-start is on again after every engine start whatever it is set to.

## Install from a USB pendrive

1. Download `nostartstop-vX.Y.Z.apk` from the [Releases](../../releases) page, or build it yourself (see [Build](#build)).
2. Copy the APK to a FAT32 or exFAT pendrive.
3. Plug the pendrive into the head unit's USB port.
4. Open the head unit's **APK Installer** app (or its file manager), pick the USB drive and tap the APK.
5. If Android asks, allow installing from unknown sources for that app, then tap **Install**.
6. Open **No Start-Stop** once from the app list. Android does not start an app that has never been opened, not even in the background.
7. Turn **Switch off at engine start** on if you want it sent automatically. Leave the app on screen when you switch the ignition off; see [the limits](#switch-off-at-engine-start-how-and-limits).

To update, install the new APK over the old one the same way. Release APKs all share one signing key, so they update each other. A locally built APK is signed with your own debug key: uninstall the old version before switching between a release and a local build.

## Updating from the app

With the head unit online (phone hotspot or Wi-Fi):

1. Tap **Check for updates** at the top of the screen. The button changes to **Install vX.Y.Z** if a newer release exists, or **Up to date**. If the automatic check already found one, the button shows **Install vX.Y.Z** as soon as you open the app.
2. Tap **Install vX.Y.Z**. The app downloads the APK and Android asks you to confirm the update.
3. The first time, Android says the app is not allowed to install unknown apps. Tap **Settings**, allow it for No Start-Stop, go back and tap **Install** again.
4. The app closes when the update finishes. Open it again; the new version is shown next to the title. Settings and the log are kept.

### Automatic check

With **Check for updates automatically** on (the default), Android runs a background check about once a day, after each boot and when you open the app, but only while there is a network connection, and at most once every 6 hours. It asks `api.github.com` for this repository's latest release; it sends nothing else. If a newer release exists, a silent notification says "Update vX.Y.Z available" (once per release), and tapping it opens the app. The check never downloads or installs anything: installing still needs your tap on **Install vX.Y.Z** and Android's confirmation. Each check writes one line to the log, for example `update check: up to date (v0.9.1)`, `update check: v0.9.2 available` or `update check failed: no internet` (a repeated failure is logged once). Failed checks are retried later by Android, not in a loop.

Turn the switch off to stop all background update checks. The **Check for updates** button keeps working.

Apart from that check and **Share diagnostics**, the app only uses the network when you tap the button: it asks `api.github.com` for the latest release and downloads the APK from `github.com`. Android refuses any APK that is not signed with the same key as the installed app, so a locally built (debug-signed) install cannot be updated this way.

## Switch off at engine start: how and limits

This was measured on the van's head unit. It does not reboot at each engine start: it sleeps at ignition off and wakes at ignition on, so Android's boot event rarely comes. At ignition off it reports power state 1 and stops the app a few seconds later. At ignition on it opens again the app that was on screen, and by the time it is awake the engine is usually already running. While the option is on, the app keeps a small background service running (with a silent notification) that listens to the head unit's power state and battery voltage.

A new ignition cycle starts when:

- the service starts after the head unit last reported ignition off (the app remembers this across the restart). This is the usual case; or
- the engine is started while the app is running: the head unit reports a crank (power state 9) and then 6 a few seconds later. This covers a quick ignition off and on, and a second engine start in the same drive; or
- the head unit reports that it wakes (power state 5) after it last reported ignition off, to a service that is still running. This is the case with [background start](#background-start): the service was started again before the head unit slept, so no start comes at ignition on, and a crank during the wake is not reported as a 9.

After that the app waits until a battery reading of 13.0 V or more arrives, at least 5 seconds have passed and no crank is in progress, and then sends one switch. After an engine start, readings from the crank count too. After an engine start, if the engine is not seen running within 3 minutes, nothing is sent. After an ignition on there is no time limit, since the app may be restarted while the head unit is still off; it waits until the engine runs or the ignition is reported off again. Without battery readings nothing is sent. Each engine start gets its own switch, because the car turns stop-start back on every time; two crank ends less than 5 seconds apart count as one.

- On the van this sent the switch at every engine start tested (3 of 3), and the instrument cluster showed its "disabled" message each time.
- The app must be running after ignition on. That is known to happen when it was the app on screen at ignition off, because the head unit then reopens it. Whether the head unit also restarts the app, or its background service, when another app was on screen is not verified; if it does not, nothing is sent and stop-start stays on.
- It sends at most one switch per cycle, and none if you already switched from the screen in that cycle. An engine start after a switch, by tap or automatic, always starts a new cycle, because the engine start turned stop-start back on.
- A boot, a restart or an update of the app counts as ignition on only if the ignition was reported off before it and no switch was sent since, by the app or by a tap. Then stop-start is on, so if the engine is already running the app sends one switch, even late in a drive. Otherwise such a start sends nothing.
- A charger that holds the battery at 13.0 V or more with the engine off can make the app send one switch it should not.
- Broadcasts such as `ACC_ON` are only logged. Any app can send them, so they never start a cycle or cause a switch.
- If the link to the car does not come up within 30 seconds, nothing is sent.
- If the head unit refuses the command, nothing is sent and it is not retried until the next cycle. The main screen then says "Automatic switch failed. Tap to switch." until you switch from the screen.

## Background start

The head unit only reopens the app that was on screen at ignition off, and its wake broadcasts never reached this app on the van. So with another app on screen, such as the phone mirroring app, nothing starts No Start-Stop and no switch is sent.

Android itself starts every app that has notification access, at power on and again after the app was stopped. **Background start** uses that: the app declares a notification listener whose only job is to start the background service of **Switch off at engine start**. It asks for no notification, reads none and stores none. Turn it on or off in Android's notification access screen, which the **Background start** control in the header opens.

- It does nothing while **Switch off at engine start** is off.
- A start by the listener never counts as ignition on by itself. Android binds the listener again seconds after the head unit stops the app at ignition off, so such a start says nothing about the ignition. A service started that way starts a cycle when the head unit reports its wake (power state 5), or at the next engine crank it sees (power state 9, then 6).
- Android 10 and older do not start notification listeners on low-memory devices. There a tap on the control says "This head unit does not allow a background start."
- On the van (Android 10) Android started the app again within seconds of the head unit stopping it at ignition off, and the service was still running at the next ignition on: the log shows `listener: connected, asleep … s since boot` and `watch: service started (by the notification listener)`. It then saw power state 5 and 6 but no crank, which is why the wake starts a cycle. A switch sent after such a wake, without the app on screen, is not verified on the van yet: the log then shows `power state 5`, `auto: ignition on, waiting for the engine to run` and `auto: engine running (… V), switching`. If Android starts the app only after the wake, the service sees neither the 5 nor a crank and nothing is sent until the app is opened.

## The assumed stop-start state

The car turns stop-start on at every engine start, and each switch command flips it. So the app assumes stop-start is on when a cycle starts (an ignition on or an engine start it noticed), and flips the assumed state for each switch it actually sent in that cycle, by a tap or automatically, and for each **Car shows the opposite** tap. A switch sent during an engine crank counts for that engine start. The assumed state survives the app being stopped in the middle of a drive. It is only shown and logged; it does not decide whether anything is sent.

It needs **Switch off at engine start** on: only then does the app follow the ignition. It is unknown:

- until the app has seen a cycle start, for example after a reboot;
- from ignition off until the next cycle;
- while **Switch off at engine start** is off, and after it is turned off until the next cycle;
- when the background service could not start, gets no power messages from the head unit, or its link to the head unit goes silent, until the next cycle.

While it is unknown and the background service is following the ignition, **Started** and **Stopped** set it by hand to what the instrument cluster shows. Switches and reports flip it from there, and the next ignition off or engine start ends it like any other state.

It is not verified on the van yet. It is a model, not a reading from the car. It is wrong after a switch the car ignored (for example one sent with the engine off), after an engine start the app missed, or after a stop-start change made from the car's own menu. That is what **Car shows the opposite** is for: the log line it writes shows what led to the mistake.

## Troubleshooting

- **Log shows `TWUtil unavailable`**: Android is blocking the vendor class for a non-system app. With adb: `adb shell settings put global hidden_api_policy 1`. Without adb: turn on **Beta: reach the car without adb**.
- **"NO LINK TO CAR" and a grey button**: the app could not open the head unit's link to the car, usually the `TWUtil unavailable` case above. The card says "This head unit did not let the app reach the car." Nothing is sent in this state.
- **Log shows `the car app names profile …: its stop-start command differs, nothing will be sent`**: the head unit is set up for a CAN box profile other than PSA with a Raise box (see [docs/vendor-profiles.md](docs/vendor-profiles.md)). The PSA command means something else there, so the app sends nothing and the card says NO LINK TO CAR. Head units without the vendor car app, or with a profile not in that list, are not affected.
- **"No answer from the car."**: the command was sent but the CAN box did not echo it within 3 seconds. Check the instrument cluster before tapping again, since the switch may have worked, and the log for the `sent stop-start switch` line and its `rc`.
- **"No answer from the car. Nothing sent."**: the CAN box did not send a status frame within 3 seconds of the tap, so no command was sent. Wait for "CONNECTED TO CAR" and tap again.
- **The instrument cluster showed no message after a tap**: the command may not have changed bit 0 for the car, or the car ignored it. Wait 10 seconds, then tap once more and watch the cluster. If it still shows nothing, share the log: the `sent stop-start switch` and `status` lines around the tap show what happened.
- **Stop-start ends up on after a tap**: each tap flips it, so it was off before. Tap once more and check the instrument cluster message.
- **Switch off at engine start does nothing**: open the app once after installing, and leave it on screen when you switch the ignition off. Then check the log for `wake` and `auto:` lines after an engine start; without a `wake` line the app did not notice the ignition on or the engine start. `auto: battery …` lines show the voltages it saw while waiting.
- **Stop-start is on after an engine start with Switch off at engine start on**: the app probably was not running at that engine start, or the car ignored the switch. If the card says **OFF** (assumed), tap **Car shows the opposite** first so the log records it. Then tap **Switch stop-start** once and share the log.
- **"UNKNOWN"** on the stop-start card: the app has not seen the ignition come on or an engine start since the last ignition off or head unit reboot, the automatic switch is off, or the background service lost the head unit. Check the instrument cluster and tap **Started** or **Stopped**. See [the assumed state](#the-assumed-stop-start-state).
- **"This app may be harmful" when installing or updating**: Google Play Protect shows this for apps it does not know. The APK is not on Google Play, installs its own updates and starts at boot, which is what Play Protect flags. The release APK is signed (APK signature schemes v1 to v3) with the same key every time. Tap **Install anyway**. If an update stopped at the prompt (the log shows `install failed (3): INSTALL_FAILED_VERIFICATION_FAILURE`), tap **Install** again. To stop the prompt, turn off **Scan apps with Play Protect** in the Play Store's Play Protect settings on the head unit.

## Status

- Tested on a real Jumpy head unit (Android 10). The switch command works: every command that changes bit 0 flips stop-start, and the instrument cluster shows a message each time.
- **Switch off at engine start** works on the van: it switched stop-start off at each of 3 engine starts tested. Not verified: whether the head unit restarts the app's background service when another app was on screen at ignition off.
- The assumed stop-start state and **Car shows the opposite** are not verified on the van yet.
- **Background start** is not verified on the van yet.
- The car's real stop-start state is not readable through the settings frame. The app logs the function frame (`function …` lines), a candidate for it, and with **Record car data** on the other CAN box frames (`frame …` lines) so it can be looked for.
- "Permanently closed" does nothing on this van.

## Diagnostics

The first time a release build is opened it asks **Help fix problems?**, with **Share** and **Don't share**. Nothing is sent before you answer. If you choose **Share**, each line the app writes to its log is also sent to the developer's [PostHog](https://posthog.com) project, so problems can be diagnosed without copying the log file by hand. The **Share diagnostics** switch on the main screen changes your answer at any time. With **Record car data** on, the log also holds raw car data that may include vehicle details. Lines are uploaded in batches, a few seconds after they are logged.

The line under the log on the main screen shows what the app is doing with them. It is only there in builds that can share diagnostics, and it cannot be tapped.

| Line | Dot | Meaning |
|---|---|---|
| `Diagnostics off` | grey ring | Sharing is off. Nothing is stored for sending and nothing is sent. |
| `Diagnostics on, nothing waiting` | green | Sharing is on. Nothing was sent since the app started. |
| `3 lines waiting to send` | grey | Lines are stored and go out in the next batch. |
| `Sending 3 lines…` | green, pulsing | A request to PostHog is on its way. |
| `Sending: 500 of 1240 lines` | green, pulsing | A backlog, for example older days stored while offline, goes out in requests of up to 500 lines. The rule above the line fills from left to right as they go. |
| `Sent 3 lines at 14:32` | green | The last batch went out. Shown for 8 seconds. |
| `All sent at 14:32` | green | Nothing is waiting; the time is that of the last batch. |
| `Send failed, 1240 lines from 3 days waiting` | amber | The last upload failed, for example with no network. The lines stay stored for the next try. The days are named when the lines are from more than one. |

What is sent with each line:

- the log line itself (the same text as in the log file: commands sent, CAN box status frames, the automatic switch, the assumed state, errors, boot activity);
- the app version, the Android SDK level and the head unit's model name;
- a random id created for this install, so lines from one head unit can be told apart. It is not tied to you or to the vehicle.

Always logged: the commands sent, the status (`status …`) and function (`function …`) frames from the CAN box, boot count and uptime, screen on/off, shutdown and network up/down. While **Switch off at engine start** is on, also the head unit's power state, wakes, battery voltage (`watch: battery …`), what the automatic switch did and the assumed state. The function frame is expected to hold only the car's feature flags, but that is not verified. Only logged while **Record car data** is on: `frame …`, `engine …`, `battery …` (from the screen), `unit id …` and `unit config …`, and the engine speed on `sent stop-start switch` lines. Those lines are only sent if **Share diagnostics** is on too. Switching **Record car data** off stops new lines; lines already in the log file stay there.

Nothing is sent while the switch is off. With it on, lines are kept in the app's private storage until they are sent, one file per day (UTC). Once a day is over its file is compressed. Together they are kept up to 5 MB or 30 files; past that the oldest days are deleted first. The current day may use at most 1 MB of that, so a burst of lines drops its own oldest lines instead of older days. They are sent the next time a line is logged with a connection, or by the background job that Android runs once there is a network after a boot, when the app is opened and about once a day (logged as `diagnostics: sent N queued lines`). The current day goes first, then the older days from oldest to newest, up to 500 lines per request and 20 requests at a time; the rest waits for the next send. After a failed upload, new lines wait 1 minute before the next try, doubling up to 15 minutes; the background job always tries. That job runs for this alone when **Check for updates automatically** is off. Switching it off discards anything not sent yet and forgets the random id, so switching it on again starts with a new one.

The switch only appears in builds made with a PostHog key. Release builds get it from the repository variables `POSTHOG_KEY` and `POSTHOG_HOST`; a local build has no key unless you set those environment variables for `build.sh`. The key is a public, write-only project key.

## Getting the log off the head unit

The app writes everything shown in its LOG panel, plus what its background services did, to a text file:

```
Android/data/com.furgo.nostartstop/files/nostartstop.log
```

on the head unit's internal storage (full path `/storage/emulated/0/Android/data/com.furgo.nostartstop/files/nostartstop.log`). When it passes 512 KB it is renamed to `nostartstop.log.1` and a new file is started.

To copy it without adb:

1. Plug a pendrive into the head unit.
2. Open the head unit's file manager and go to internal storage, then `Android/data/com.furgo.nostartstop/files/`.
3. Copy `nostartstop.log` to the pendrive.

Each line has a date and time. Lines to look for:

- `app opened, version …, android … (sdk …), <model>, display 1920x720, 240 dpi, boot count 7, up 42 s, network up (wifi), car app 961_0000_260130, profile 961 (Raise, PSA and Opel, tested)`: app and Android version, the head unit's model name, its screen size in pixels and density, how many times the head unit has booted, seconds since it booted, the network state, and the version of the vendor car app `com.tw.car` with the CAN box profile it names. `boot count unknown` before Android 7. The profile line says `untested` for a profile whose stop-start command is only read from vendor code, `no stop-start command known` for any other, and `car app not found` without the vendor app. The app still sends only the PSA command; see [docs/vendor-profiles.md](docs/vendor-profiles.md).
- `TWUtil open` / `TWUtil unavailable: …`: whether the head unit's MCU link works. With **Record car data** on, `TWUtil open, CAN box only (engine and unit ids refused: …)` means the head unit refused the engine and unit messages; the switch works the same.
- `status 00 4A …`: the raw settings frame (id 56) from the CAN box, logged whenever it changes.
- `function 05 12 02 01 (stop-start field=2)`: the CAN box function frame (id 57), asked for after the first status frame when the link opens and 1.5 and 5 seconds after each switch, logged when it changes since the app started (at most 40 lines each time the app connects). The field is a candidate for the real stop-start state (1 on, 2 off, 0 not reported); it is not verified and the app does not use it.
- `frame <id> …`: any other CAN box frame, only while **Record car data** is on. Logged when it changes, at most 20 lines per frame id, 64 frame ids and 400 lines in total each time the app connects. Frames 51 (trip computer), 81 and 125 are left out: they change every second while driving. The automatic switch never records them.
- `engine rpm=… speed=…` and `battery 12.34 V`: only while **Record car data** is on, except that `watch: battery …` is logged whenever **Switch off at engine start** is on. Logged when they change noticeably, at most one of each every 10 seconds and 100 of each each time the app connects. `rpm=?` means the head unit reported it as invalid.
- `unit id …` / `unit config …`: the head unit's identity text and configuration block, once each time the app starts, only while **Record car data** is on.
- `sent stop-start switch, bit=…, rc=…`: a switch command sent to the CAN box. With **Record car data** on and the engine speed known, it ends with `, rpm=…`.
- `assumed: on (ignition on)`, `assumed: on (engine start)`, `assumed: on (engine start, same cycle)`, `assumed: off (switch sent, automatic)`, `assumed: on (switch sent, tap)`, `assumed: on (reported wrong)`, `assumed: off (set by hand)`, `assumed: unknown (ignition off)`, `assumed: unknown (automatic switch off)`, `assumed: unknown (no power messages)`, `assumed: unknown (link silent)`, `assumed: unknown (watch service not started)`: each event that sets the assumed state and why, with the state after it. An event that leaves the state as it was, such as an engine start right after an ignition on, still gets its line (at most 60 lines per 24 hours).
- `report: car shows ON, app assumed OFF, wake 5 (ignition on), 1 switch sent, 0 reports, cycle 312 s old, last switch 300 s ago (automatic), battery 14.85 V, power state 6`: a **Car shows the opposite** tap, written before the shown state flips. `cycle N s old` counts from the ignition on; after an engine start it reads `engine start N s ago` instead. A battery or power state value older than 10 seconds gets its age, for example `battery 14.85 V 312 s ago`. Fields the app does not know are left out (at most 50 lines per 24 hours).
- `auto: …`: what the automatic switch did, including why it sent or did not send and whether the CAN box echoed the command. `watch: …` lines come from the background service that waits for the engine.
- `process started, pid …`, `wake <n> (<source>)`, `power state <n>` and `broadcast <action> (manifest|runtime), up … s`: what the app sees across ignition cycles, to learn how the head unit sleeps and wakes. `watch: service started (…)` says how the service was started (by the app, at boot, after update, by a broadcast, by the notification listener or restarted by the system). `listener: connected, asleep … s since boot, …` (the head unit's total sleep time, to tell a start after a wake from one right after the app was stopped) and `listener: disconnected`: Android bound or released the [background start](#background-start) listener (at most 40 lines per 24 hours). `background start on` / `off` and `background start: opened notification access settings`: the notification access as the screen last saw it, and a tap on the control. Capped at 30 `wake`, 60 `power state`, 30 `broadcast` and 60 `auto: battery` lines per 24 hours; the other `auto:` and `watch:` lines are capped too.
- `screen off, up … s` / `screen on, up … s`, `network up (…)` / `network down`: while the app is open, at most 50 network lines each time the app starts.
- `shutdown, up … s`: the head unit shut down cleanly. Android 9 and later only tell the app while it is running (open or in the background), so on those a missing line does not prove a power cut.

With adb the same file is at `adb pull /sdcard/Android/data/com.furgo.nostartstop/files/nostartstop.log`.

## How it works

The head unit's MCU relays CAN box frames through the framework class `android.tw.john.TWUtil`. `stubs/` holds a compile-only stub of it; the real class comes from the device.

| Action | Call |
|---|---|
| Query settings status | `write(1281, 143, 2, {56, 0})` |
| Switch stop-start | `write(1281, 128, 2, {13, value})`. Every command that changes bit 0 flips stop-start on the car, so the app sends the opposite of the echo bit in a status frame it has just asked for (the automatic switch sends 1 if no frame arrives within 5 seconds). Bit 7 ("service permanently closed") has no effect on this van and is always sent as 0 |
| Status frame | message `what = 1281`, `arg1 = 56`, 9 bytes. `byte[5]` bit 6 echoes bit 0 of the last command and clears by itself after about 8 seconds; it is **not** the stop-start state. No byte of this frame was seen to follow the real state |

These were read from the decompiled vendor app `com.tw.car` (`PsaRaiseSettingsActivity`) and checked on the van. The vendor app reads `byte[5]` bit 6 as the stop-start state, so it shows the same wrong value.

The automatic switch uses two head unit messages, found in the vendor code and checked on the van:

| Data | Call |
|---|---|
| Power state | message `what = 514`, `arg1`: 1 at ignition off, 4 as the head unit goes to sleep, 5 as it wakes, 9 while the engine cranks, then 6 a few seconds later. The head unit also answers 6 every time the link is opened |
| Battery | message `what = 1292`, `arg1 = 0`: `((b[0] & 0x7F) << 8 \| b[1]) * 0.01` V when `b[0]` bit 7 is clear. About 12.0 V with the engine off, above 13 V with it running |

The app also logs these, which come from the vendor code and are **not verified** on the van. Nothing depends on them:

| Data | Call |
|---|---|
| Function frame (candidate stop-start state) | query `write(1281, 143, 2, {57, 0})`, answered as frame 57. `byte[2]` bits 1-0 may be the state (1 on, 2 off, 0 not reported); the box may instead answer with a text list, so the raw bytes are logged |
| Engine | message `what = 1291`: `arg1` speed, `arg2` rpm (65535 invalid) |
| Head unit identity | `write(266, 255, 0)` answered by `what = 266` with a text; `write(267, 255, 0)` by `what = 267` with a block of bytes |

The screen opens the engine, battery and identity messages, together with the CAN box, only while **Record car data** is on. Turning it on takes effect for them the next time the app's screen is shown. If the head unit refuses them, the app opens the CAN box alone, as before. The automatic switch opens the CAN box alone to send; its background service opens only the power state (514) and battery messages, and reopens them if they stay silent for a minute.

## Install with adb

```sh
adb install -r build/nostartstop.apk
adb shell am start -n com.furgo.nostartstop/.MainActivity
adb logcat -s NoStartStop
```

## Build

Needs JDK 17 and the Android SDK with `build-tools/34.0.0` and `platforms/android-34`. No gradle.

### Set up the tools (macOS)

1. Install JDK 17, with either:

   ```sh
   asdf plugin add java && asdf install    # reads .tool-versions
   ```

   ```sh
   brew install --cask temurin@17
   ```

2. Install the Android SDK packages. If you have Android Studio, use its SDK Manager. Otherwise:

   ```sh
   brew install --cask android-commandlinetools
   sdkmanager "build-tools;34.0.0" "platforms;android-34" "platform-tools"
   ```

3. Point `ANDROID_HOME` at the SDK if it is not in `~/Library/Android/sdk` (Android Studio's default). For the Homebrew command line tools:

   ```sh
   export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
   ```

### Build the APK

```sh
sh build.sh
```

The result is `build/nostartstop.apk`, signed with the local debug key in `~/.android/debug.keystore` (created on first build). That is fine for sideloading onto your own head unit.

The launcher icon is drawn in `art/icon.svg`. After editing it, regenerate the PNGs in `res/mipmap-*` (48, 72, 96, 144 and 192 px) with Inkscape or `rsvg-convert`.

To sign with a stable key instead, set `KEYSTORE` (a PKCS12 file), `KS_PASS` and `KEY_ALIAS`. `VERSION_NAME` and `VERSION_CODE` set the version stamped into the APK.

## CI and releases

GitHub Actions (`.github/workflows/ci.yml`) runs on every push to `master` and on pull requests:

- **test**: `shellcheck` on the scripts and the unit tests (`sh test.sh`). The tests cover the CAN box frame encoding, the echo bit, the switch value and the parsing of the logged diagnostic data in `Protocol.java`, the boot id and the boot facts in `BootId.java`, the ignition cycle rules in `WakeRules.java`, the reading of the power state (ignition off, engine crank) in `PowerWatch.java`, when the automatic switch fires in `AutoSwitch.java`, what the background service does with a start, a power state, a battery reading and a tick in `WatchDecisions.java`, what the switch service does (one switch per ignition cycle, the mark written before the send, retries and timeouts) in `BootFlow.java`, the assumed stop-start state and its log lines in `AssumedState.java`, the log caps in `LogBudget.java`, the diagnostics storage (per-day files, compression, upload order and size limits) in `TelemetryStore.java`, and the version bump rules; they need only a JDK.
- **security**: `gitleaks` scans the commits for leaked secrets and `zizmor` audits the workflow file. Actions are pinned to commit SHAs.
- **build**: compiles with javac warnings as errors, builds the APK, checks its contents (launcher activity, boot components, and that the `TWUtil` stub is not packaged) and keeps it as a workflow artifact. That APK is signed with a debug key made on the runner. The job has no access to the release key.
- **release**: only when a release was decided (see below). Signs the APK from the build job with the release key and publishes it. It runs no code from the repository.

Nothing in CI talks to a head unit or a CAN box.

### Releases

Merging a pull request into `master` publishes a release automatically, depending on the prefix of the merged branch (`scripts/next-version.sh`):

| Branch prefix | Version bump | Example |
|---|---|---|
| `breaking/`, `major/` | major | `v0.3.1` → `v1.0.0` |
| `feat/`, `feature/` | minor | `v0.3.1` → `v0.4.0` |
| `fix/`, `perf/` | patch | `v0.3.1` → `v0.3.2` |
| anything else (`ci/`, `docs/`, `chore/`, `refactor/`, `test/`, `renovate/`, …) | no release | |

The release is tagged `vX.Y.Z` on the merge commit and has `nostartstop-vX.Y.Z.apk` attached. Its notes are the pull request's title and description, so write the description as a changelog for someone installing the APK. Commits pushed straight to `master` without a pull request do not release.

Runs for `master` wait in a queue and go one at a time, so pull requests merged quickly one after another each get their own release. A run does not release when the latest tag already contains its commit: a queued run that started after a later merge's run, or a re-run of a run that already released.

To release by hand instead, push a tag (the notes are then GitHub's generated list of changes):

```sh
git tag v0.2.0 && git push origin v0.2.0
```

[Renovate](https://docs.renovatebot.com/) (`renovate.json`) opens pull requests for new versions of the GitHub Actions (keeping them pinned to commit SHAs) and the pinned JDK patch level. It needs the Renovate GitHub App installed on the repository.

Releases are signed with the key in the secrets `RELEASE_KEYSTORE_B64` (base64 of a PKCS12 keystore, alias `release`) and `RELEASE_KEYSTORE_PASSWORD`. They belong to the GitHub environment `release`, which only `master` and `v*` tags may use, so only the release job can read them. A release fails if they are missing. Keep a copy of that keystore: if it is lost, later releases cannot update earlier installs.

## License

[MIT](LICENSE).
