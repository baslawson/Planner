# Start-up profile

`app/src/main/baseline-prof.txt` lists the code Planner runs while it starts; Android compiles it ahead of time on
install (through profileinstaller), so the first starts are faster. To make it again after big changes, with the
emulator running:

```
./gradlew :baselineprofile:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile
```

It installs "Planner benchmark" (`io.github.baslawson.planner.benchmark`, its own app and data) and the test app,
then copy the `*-baseline-prof.txt` from `baselineprofile/build/outputs/connected_android_test_additional_output/` to
`app/src/main/baseline-prof.txt`, and uninstall both apps.
