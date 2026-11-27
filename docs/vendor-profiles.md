# Stop-start in other CAN box profiles

The Topway vendor app `com.tw.car` is built once per CAN box profile. Each build holds that profile's car settings screens and the commands they send. This page lists the profiles whose build has a stop-start setting, and how to repeat the search when new builds appear.

Only the Raise PSA command (`961`) is tested on a car. Every other row is read from decompiled vendor code.

## Results

Scanned 2026-11-27: 802 vendor APKs (442 `com.tw.car` builds plus themes, info apps and services). 11 profiles have a stop-start setting. All send on message 1281, the same message the app uses for PSA: `write(1281, cmd, length, data)`.

| Build | CAN box / make | Selections | Setting | Command | Value |
|---|---|---|---|---|---|
| `961` | Raise / PSA, Opel | 126 | Stop-start | `write(1281, 128, 2, {13, v})` | any change of bit 0 flips it, see README |
| `96` | Simple Soft, Binary, LuZheng / PSA | 42 | `PSA2008_Engine_start_and_stop` | `write(1281, 128, 2, {13, v})` | 1 on, 0 off |
| `964` | Hiworld / PSA, Opel | 45 | `wc_Engine_start_and_stop_function` | `write(1281, 140, 2, {1, 255})` | none: acts like a button press |
| `114` | Hiworld / VW MQB | 53 | `start_stop` | `write(1281, 74, 2, {13, v})` | 1 on, 0 off |
| `111` | Raise / VW MQB | 50 | `automatic_start_and_stop` | `write(1281, 198, 2, {17, v})` | 1 on, 0 off |
| `11` | Simple Soft, Binary / VW MQB | 25 | `automatic_start_and_stop` | `write(1281, 198, 2, {17, v})` | 1 on, 0 off. Only in the platform 01 build |
| `274` | Hiworld / Honda | 30 | `accord9_Energy_saving_automatic_start_stop` | `write(1281, 111, 2, {8, v})` | 1 on, 0 off |
| `880` | Simple Soft / Jeep | 17 | `automatic_start_stop_function` | `write(1281, 198, 2, {114, v})` | 1 on, 0 off |
| `880` | same | 17 | `jeep_the_auto_start_stop_function_is_powered_on_by_default` | `write(1281, 198, 2, {68, v})` | 0 on at power-on, 1 off at power-on |
| `F91` | Raise / GWM Haval | 33 | `intelligent_start_and_stop` | `write(1281, 131, 2, {11, v})` | 1 on, 0 off |
| `F94` | Hiworld / GWM Haval | 30 | `H6_Intelligent_start_and_stop` | `write(1281, 109, 4, {1, v, 255, 255})` | 1 on, 0 off |
| `41F` | BaoXunFeng / Mercedes Vito 2015-16 | 1 | `Automatically_close_the_start_and_stop_switch` ("Remember Engine Start-Stop") | `write(1281, 151, 2, {b0, b1})` | `b0` bit 4 = v; other bits and `b1` copied from status frame 45 |
| `41F` | same | 1 | `cm_switch7` ("Remember Engine Start-Stop (Hardware)") | `write(1281, 195, 3, {b0, b1, b2})` | `b0` bit 6 = v; other bits copied from a status frame not yet identified |

"Selections" counts the make / model / variant choices in the chooser that install that build. `report.md` from the scan lists them all. Car groups per build:

- `961`: Peugeot, Citroën, DS and Opel models on Raise (the van's profile).
- `96`: Peugeot 301, 407, 408, 508, 2008, 3008, Citroën Elysée, C3-XR, C4L, C-Quatre on Simple Soft, Binary and LuZheng boxes.
- `964`: Citroën C-Quatre, C4, C4L, C5, DS4, DS5, Peugeot 307, 308, 408, 508, 2008, 3008, Opel Combo and Grandland X on Hiworld.
- `114`, `111`, `11`: Golf 7, Octavia, Superb, Passat B8, Kodiaq, Tiguan L, Touran, T-Roc, Teramont, Lamando, Lavida, Bora, Magotan.
- `274`: Civic 2016, CR-V 2017, Accord 10, Avancier, Crider, Odyssey.
- `880`: Renegade, Compass, Grand Cherokee 2014, Wrangler 2018.
- `F91`, `F94`: Haval H1, H2, H2s, H6, H6 Coupe, H9, F7, GWM M6, Fengjun 7.

Notes:
- The command byte (`cmd`) and the data length differ per profile. A profile table needs `cmd`, the data layout and the value meaning, not only an id.
- `961` and `964` toggle; the others take an explicit on/off value and may not need the echo logic in `Protocol.toggleValue`.
- Three settings could keep stop-start off with nothing sent at each start, if the car honours them: the Jeep power-on default and both Vito "Remember Engine Start-Stop" settings.
- The Vito settings are read-modify-write: sending them with the other bits wrong changes other car settings. They need the status frame decoded first.
- The status frame that reports each setting back is not decoded yet, except where noted.
- Builds `00_*` and `01_*` are the same profile for two head unit platforms. Their commands match, except that `00_11` has no stop-start setting.

Left out, because they do not switch stop-start:
- Honda `22A`, `211`, `218`, `238`, `27`, `270`, `271`: "Automatic start and stop indication" only turns a display on or off.
- `641`: "AUTO STOP timer reset" resets a meter counter.
- `870`: `jeep_auto_stop_brake_key` is automatic braking.
- `CarAppTheme_*`, `com.tw.carapps_*`, `CarServiceTheme_*`, `com.tw.carinfoservice_*`: they show stop-start states and warnings but have no `TWUtil` code, so they send nothing.

## Repeating the search

Safety: these files come from a host that has been reported to distribute head unit malware, over plain HTTP. Never install or run them, on the head unit or anywhere else. Only unzip and decompile them.

1. Put the vendor APKs in one folder, for example `../CarChooseAPK/cdn/`. If the chooser's `tree.json` and `res.json` are in an `index/` folder next to them, the report names the cars for each build.
2. Install jadx (`brew install jadx`) and the Android SDK build-tools 34.0.0 (for `aapt`).
3. Run:

   ```sh
   python3 -I scripts/vendor-scan.py ../CarChooseAPK/cdn ../decompiled/vendor-scan
   ```

   For each APK it reads the string resources and the settings XML. A build is a candidate when a string or a preference key mentions stop-start and its code contains a `TWUtil` subclass. Candidates are decompiled, and each stop-start setting is listed with the code that handles it, in `report.md`. A rerun skips APKs already in `scan.json`; delete `scan.json` to scan everything again.
4. For each new build in `report.md`, decode the command (see below), check the title text in `resources/res/values/strings.xml`, and add a row above or to the left-out list.

The scan finds a setting by its title text or key in English or Chinese (`启停`, `怠速`). A setting with neither is missed.

## Decoding by hand

The send wrapper is a method of a `TWUtil` subclass, for example `f(int i, int i2, int i3) { return write(1281, i, 2, new byte[]{(byte) i2, (byte) i3}); }`. The report lists the two-byte wrappers it finds; read the class for longer ones.

Shapes seen so far:

- Branch per key: `i = 13` in the branch for the key, then `x.f(74, i, checked ? 1 : 0)`.
- Table of `{id, cmd}`: `K0("key", 8, 111)`, then `x.e(cmd, id, value)`.
- Table of ids: `map.put("key", 114)` or `a("key", 11)`, then `x.f(198, id, value)` or `x.d(131, id, value)`.
- Longer data: `x.f(109, 1, value, 255, 255)` through a four-byte wrapper.
- Bit fields: the new byte is built from the last status frame (`this.X0` from frame 45) with one bit replaced, then sent with the other bytes unchanged.

The title text decides whether a setting switches stop-start or only shows it: check `strings.xml` before adding a row.
