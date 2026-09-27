# Planner

An offline-first Android planner built with Kotlin and Jetpack Compose.

- Agenda and calendar views for events, tasks and bills.
- Offline natural-language Quick entry with editable previews.
- Optional Gemini or OpenAI assistance using a personal API key, sent directly from the phone only when requested.
- Reminders, recurring entries, calendar invitation import and free-time search.
- Document and bill scanning with cropping and on-device text recognition.
- Matrix Green, High Contrast and Colour-blind friendly themes.

## Build and run

1. Clone this repository and open its root folder in Android Studio.
2. Install Android SDK platform 35 and let Gradle sync using the checked-in versions.
3. Use JDK 21 for Gradle. The daemon configuration requests JetBrains JDK 21 and can download it when needed.
4. Run the `app` configuration on an emulator or phone running Android 8.0 (API 26) or later.

From a terminal (use `gradlew.bat` on Windows):

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Set `ANDROID_HOME` to your Android SDK directory or let Android Studio create the ignored `local.properties` file. The first build requires internet access to download Gradle, the JDK and dependencies.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Downloadable builds can also be attached to this repository's GitHub Releases. Debug signing keys are local to each developer; builds signed with different keys cannot update an existing installation in place.

## Quick entry

- [Offline parsing guide](docs/QUICK_ENTRY_OFFLINE.md)
- [Optional AI setup and data handling](docs/QUICK_ENTRY_AI.md)

The offline parser needs no API key or backend server. Never commit personal API keys, signing keys or device backups.

## Tests

Local JVM tests live in `app/src/test`. Device tests live in `app/src/androidTest`; run them on a dedicated disposable emulator, since they exercise and modify app data:

```sh
./gradlew :app:connectedDebugAndroidTest
```

## Project layout

- `app/src/main`: application code and resources.
- `app/src/test`: parser, scheduling and other JVM tests.
- `app/src/androidTest`: device and UI tests.
- `docs`: feature guides.
- `tools`: local development helpers.
- `gradle`: pinned dependency versions and build wrapper.

Local QA captures, working notes, credentials, SDK paths, caches and APK binaries are excluded from Git. Keep personal working history and device backups separately.
