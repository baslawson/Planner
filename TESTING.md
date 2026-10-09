# Testing Planner: a checklist

**Current build entry point:** use `./gradlew :app:assembleDebug :app:testDebugUnitTest`
(`gradlew.bat` on Windows). The historical machine-specific recipes below predate the wrapper.
Local `NOTES.md`, QA captures and device backups are intentionally excluded from Git; see README.md
for portable setup. Use a disposable emulator for device tests.

For whoever is checking a change works — a person, or an assistant driving the emulator. `NOTES.md` says what the app
is and what has been tested; this says **how** to test it so the result can be trusted.

Written 20 Sep 2026 out of what actually went wrong during a day's testing. Every rule below is here because ignoring
it produced a wrong answer at least once.

---

## The five rules

### 1. Prove the test can fail before you trust it passing
The most dangerous result is a green one from a method you never validated.

A real example: to test whether a config change destroys the editor, "Don't keep activities" was switched on with
`settings put global always_finish_activities 1`. It read back as `1`. The scan came through fine. That nearly got
reported as "the bug does not reproduce" — but the setting was **not in effect** (ActivityManager caches it; the
`settings put` route does not work). Pressing HOME and relaunching showed the editor surviving when it should have
been destroyed, which proved the method was broken, not the app healthy.

So: before believing "X did not happen", show that your setup *can* make X happen. If you cannot, say the case is
**untested** rather than passing.

### 2. "It compiled" is not "it ran" is not "it persisted"
Three different claims, three different proofs:

- **Compiled** — check the task list, not just `BUILD SUCCESSFUL`. A build reporting every task `UP-TO-DATE` compiled
  nothing. Look for `kspDebugKotlin` and `compileDebugKotlin` actually executing.
- **Ran** — the feature did something on screen, observed in a screenshot or the accessibility tree.
- **Persisted** — survived `am force-stop` (with the process confirmed gone) *and* a cold relaunch. An item visible on
  screen is not a saved item.

### 3. Prefer evidence that is not the thing you are testing
Screenshots can mislead; independent facts do not.

- `firstInstallTime` unchanged proves an install preserved data, where "the list looks the same" does not.
- `itinerary.db-wal` byte count unchanged proves nothing was written.
- A SHA-256 match proves an APK copy, where a size match does not.
- A PDF **rendering** in a viewer proves the file is valid; "an app opened it" only proves an app accepted it.

### 4. Re-read the screen before every tap
Coordinates go stale between steps. Real mis-taps from one session: the on-screen keyboard shifted a dialog upward so
a "Pick dates" tap landed on the colour swatches; the scanner's auto-capture moved on to the review screen so a
"shutter" tap hit the Filters row. Dump or screenshot first, tap second — and after any layout change (keyboard,
rotation, font size), dump again.

### 5. Write down what you did **not** check
A report that lists only successes is not trustworthy. Name the cases you could not reach and why — no real phone, a
path that never ran, a message never actually seen. `NOTES.md` keeps a "Not tested" line in every section for this.

---

## Before you touch the emulator

The user drives this emulator by hand as well. **Ask before driving it, and leave it as you found it.**

Record anything you are about to change so you can put it back — app data, and any system setting
(`font_scale`, `accelerometer_rotation`, `user_rotation`, `always_finish_activities`).

---

## Recipes

These historical PowerShell examples use environment variables. Set `JAVA_HOME` to your installed JDK 21 directory; see README.md for the current build instructions.

```
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$env:JAVA_HOME = "<path-to-jdk-21>"
```

### Build, copy, install
The original workflow used an unpacked `gradle.bat`; use the checked-in wrapper now, then copy to `Planner.apk` in the project folder and
**verify the copy by hash**, not by size:

```
& "<gradle.bat path>" :app:assembleDebug --console=plain
Copy-Item $src $dst -Force
(Get-FileHash $src).Hash -eq (Get-FileHash $dst).Hash
& $adb -s emulator-5554 install -r $dst
```

Expect exactly two warnings, both `VIBRATOR_SERVICE` deprecations in `AlarmService.kt`. Anything else is new.

To confirm the running app is the binary you think it is, compare `md5sum` on the device
(`adb shell pm path io.github.baslawson.planner.debug`) against the local file.

**Package names.** Debug builds are `io.github.baslawson.planner.debug` ("Planner debug"); release builds are
`io.github.baslawson.planner`. Only debug builds allow `run-as`. Builds before the ID change were
`com.example.itinerary`, a separate app as far as Android is concerned — its data does not carry over by itself.

### Screenshot
Read `exec-out screencap` as a **raw byte stream**. Letting PowerShell redirect it into a file corrupts the PNG, and
writing to `/sdcard` then deleting invites a blocked `rm`:

```
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $adb; $psi.Arguments = "-s emulator-5554 exec-out screencap -p"
$psi.RedirectStandardOutput = $true; $psi.UseShellExecute = $false
$p = [System.Diagnostics.Process]::Start($psi)
$ms = New-Object System.IO.MemoryStream
$p.StandardOutput.BaseStream.CopyTo($ms); $p.WaitForExit()
[System.IO.File]::WriteAllBytes($path, $ms.ToArray())
```

### Coordinates
The screen is **1440x3120**. Do not assume a scale factor — check the one your viewer reports (a capture shown at
923x2000 needs **1.56**). Better still, take bounds from the accessibility tree rather than reading them off a picture:

```
& $adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml
& $adb -s emulator-5554 shell cat /sdcard/ui.xml
```

Parse `bounds="[x1,y1][x2,y2]"` and tap the centre. The dump fails transiently ("null root node") — retry a few times.
Clean up afterwards with `adb shell rm -f /sdcard/ui.xml` as its own command.

### Back up and restore app data — do this before ANY test that saves
```
& $adb -s emulator-5554 shell am force-stop io.github.baslawson.planner.debug
& $adb -s emulator-5554 shell run-as io.github.baslawson.planner.debug mkdir -p files/bk
# copy all three: itinerary.db, itinerary.db-wal, itinerary.db-shm
& $adb -s emulator-5554 shell run-as io.github.baslawson.planner.debug cp databases/itinerary.db files/bk/itinerary.db
```
Restore by copying back the other way, then remove the folder
(`find files/bk -type f -delete`, then `rmdir files/bk`).

Skipping this means the database cannot be returned byte-for-byte, which happened once and had to be admitted in the
write-up. Deleting test data through the app afterwards restores the *contents* but not the file.

Check permissions as well as contents afterwards (`run-as … ls -la databases files shared_prefs`). Folders and files
created through `run-as` (mkdir, cp from `/data/local/tmp`, tar) can come out world-writable (`drwxrwxrwx`, `-rw-r--r--`)
and hashes do not show that. The app's own modes are 771 for `databases`, `files`, `shared_prefs`; 700 for folders
inside `files`; 660 for database and preference files; 600 for other files. Set them with `chmod` if they differ.

### Check for crashes
```
& $adb -s emulator-5554 shell logcat -d -b crash | Select-String 'itinerary'
```
Filter to the package. `/vendor/bin/hw/android.hardware.uwb-service` crash-loops with SIGABRT every 5 seconds on this
emulator; it is a vendor HAL and nothing to do with Planner. Do not report it as an app crash.

### Force a configuration change
Font scale is the reliable trigger; rotation is **not**, because the Play Services scanner is portrait-locked and
`user_rotation` gets reverted:
```
& $adb -s emulator-5554 shell settings put system font_scale 1.15   # then back to 1.0
```

### If the emulator freezes on startup
`adb devices` stuck `offline`, processes alive and `Responding`, `bootcompleted.ini` still 0 bytes: it is hanging on
the Quick Boot snapshot. Stop it, then **Cold Boot** from Android Studio's Device Manager — Cold Boot keeps data,
**"Wipe Data" destroys it**. After a hard kill, clear the stale `hardware-qemu.ini.lock` (a directory holding a `pid`
file — check that pid is dead first) and `multiinstance.lock` from the AVD folder. Full detail in `NOTES.md`.

### Driving the UI: known traps
- `input text` stops at the first **space**. Use one word, or `%s`.
- The keyboard shifts dialogs upward, invalidating earlier dumps.
- The document scanner defaults to **Auto capture**, so a scripted shutter tap often lands on the review screen's
  Filters row. Check for "Next" in a dump first; if the filter panel opens, "Apply" backs out harmlessly.
- The harness blocks some commands containing `rm`. Keep `rm -f <path>` as its own `adb shell` command.
- `find /sdcard -name <file> -delete` silently fails on `/sdcard`, though it works on the app's own `databases`.

---

### Instrumented UI tests
They run against a separate test app, "Planner test" (`io.github.baslawson.planner.uitest`, build type `uitest`), so
they never touch the data in Planner debug. Full run (about 16 minutes; Gradle installs the test app, runs every
class except harness steps, and uninstalls it afterwards):

```
./gradlew :app:connectedUitestAndroidTest
```

Report: `app/build/reports/androidTests/connected/uitest/index.html`. One class or method:
`adb shell am instrument -w -r -e class com.example.itinerary.<Class>[#method] -e notAnnotation
com.example.itinerary.HarnessStage io.github.baslawson.planner.uitest.test/com.example.itinerary.PlannerTestRunner`
(install both uitest APKs with `-g` first).

- Before every test the runner (`PlannerTestRunner`, listener `CleanStart`) closes all screens, clears drafts, empties
  the database and puts the agenda view settings back to new-install defaults. A test creates the data it needs.
- `@HarnessStage` marks a step of a multi-step check (for example: leave a draft, `am force-stop`, then recover it).
  These are excluded from normal runs; run the steps one by one with `am instrument -e class …#method` and without
  `notAnnotation`.
- Read the screen through `uiAutomation.freshRoot` (androidTest `QuickUiText.kt`), never `rootInActiveWindow`
  directly: without clearing the accessibility cache, a Compose screen's old labels and cards stay in the tree for
  seconds after they have gone from the screen.
- Wait for a scrolling list to settle before comparing positions, and prefer checking an effect (cards shown or
  hidden) over a toggled label.
- The system camera can show its shutter while still starting and ignore a press: press with a real tap and retry
  until the photo is taken.
- Anything a test writes to shared storage (Downloads) must be removed by the test afterwards.

#### Emulator for long test runs
The full suite is about 500 tests; on an emulator short of memory it crashes partway (system_server restarts, ANRs
waiting for focus) and tests time out at random. Before a full run:
- Give the AVD enough: `hw.ramSize=6144` and `hw.cpu.ncore=6` in its `config.ini` (4 GB filled up after a few hours of
  tests). Check with `adb shell head -1 /proc/meminfo` and `adb shell nproc`.
- Start it fresh (`emulator -avd <name> -no-snapshot-load`), not from a snapshot left after earlier runs.
- Turn animations off for the run and back on after, so the emulator still feels normal when used by hand:
  `adb shell settings put global window_animation_scale 0` (and `transition_animation_scale`,
  `animator_duration_scale`); afterwards `settings delete global …` for each, which restores the default.
- `connectedUitestAndroidTest` with a comma-separated `class=` runner argument runs only the first class. To run
  several, install both uitest APKs and use `am instrument -e class A,B,…`; split the full list into a few runs so one
  crash doesn't end them all.
- A Notes test that fails with a note open leaves its draft for the next tests, which then open on "Recovered unsaved
  changes" and fail too: re-run the failing ones on their own (reinstall the uitest APKs first to clear the state).
- Before blaming a change for a failure, run the same test on the last commit (a `git worktree` of HEAD, its uitest
  APKs built there): if it fails there too, it isn't the change.

## Finishing up

- [ ] Test data deleted through the app, and the database restored from your backup.
- [ ] The `files/bk` folder removed.
- [ ] `files/attachments` back to empty (or to whatever it held before).
- [ ] Every system setting you changed put back — check by reading it, not by remembering.
- [ ] Any file you pushed to `/sdcard` deleted, confirmed with `ls`.
- [ ] `logcat -b crash` clean for the package.
- [ ] The app left on the screen you found it on.
- [ ] `NOTES.md` updated: what changed, what was tested, and what was **not**.
- [ ] `Planner.apk` in the project folder refreshed and hash-verified if the build changed.

## Reporting

Say what was checked and how it was proven. Then say plainly what was not covered, and why — an unreached case is a
normal outcome, a case quietly omitted is not. If a slip happened while testing (a mis-tap, a script that stopped
early), record it as a testing slip rather than an app fault, so the next person does not chase it.

## Notes sync against a real Nextcloud (test server)

A Nextcloud with the Notes app runs in Docker for testing notes sync (set up 2026-10-09): `C:\Users\bas\nextcloud-test`
(`docker-compose.yml`, a self-signed `server.crt`/`server.key` for 10.0.2.2, and `admin-password.txt`; none of it is in
this repo). Start it with `docker compose -p nextcloud-test up -d` in that folder, stop it with `docker compose -p
nextcloud-test stop`. From the PC: `https://localhost:8443`; from the emulator: `https://10.0.2.2:8443`; login `qa`.

`RealNextcloudNotesTest` runs Planner's sync code against it and checks every result through the Notes API, as the web
editor sees it. It is skipped unless given the server's details, and it deletes every note of that login first, so only
point it at this test server:

```bash
adb -s emulator-5556 shell am instrument -w -r -e class com.example.itinerary.RealNextcloudNotesTest \
  -e nextcloudUrl https://10.0.2.2:8443/ -e nextcloudUser qa -e nextcloudPassword "$(cat admin-password.txt)" \
  -e nextcloudCert "$(base64 -w0 server.crt)" io.github.baslawson.planner.uitest.test/com.example.itinerary.PlannerTestRunner
```
