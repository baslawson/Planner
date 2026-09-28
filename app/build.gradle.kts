import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.itinerary"
    compileSdk = 35

    defaultConfig {
        // Permanent: Android treats a different ID as a different app. The Kotlin package
        // (namespace) is internal and deliberately still com.example.itinerary.
        applicationId = "io.github.baslawson.planner"
        minSdk = 26 // java.time works natively from API 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.0.2"
        testInstrumentationRunner = "com.example.itinerary.PlannerTestRunner"
        // Steps that need an outside action between them are run one at a time, not in the full suite.
        testInstrumentationRunnerArguments["notAnnotation"] = "com.example.itinerary.HarnessStage"
    }

    // The release key lives outside the repository. keystore.properties (git-ignored) points at it;
    // without that file a release build is simply left unsigned.
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        val keystore = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Debug builds install alongside the release app instead of replacing it.
        debug { applicationIdSuffix = ".debug" }
        // The instrumented tests clear and rewrite the app's database, so they run against their own app
        // ("Planner test") and can never touch the data in Planner debug.
        create("uitest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".uitest"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    testBuildType = "uitest"
    // Grant the runtime permissions (notifications, camera) when the test app is installed, so no system prompt
    // covers the screens the UI tests look at.
    installation { installOptions += listOf("-g") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

// testBuildType (above) would also move the JVM unit tests to the uitest build; keep them on debug, where the
// documented command `:app:testDebugUnitTest` expects them.
androidComponents {
    beforeVariants { variant ->
        (variant as? com.android.build.api.variant.HasHostTestsBuilder)
            ?.hostTests?.get(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE)
            ?.enable = variant.buildType == "debug"
    }
}

composeCompiler {
    // Types the compiler cannot work out for itself (java.time, the collection interfaces, the data
    // layer). Without it a row is compared by instance, so every database emission redraws the whole
    // list. See app/stability_config.conf for what is listed and why.
    stabilityConfigurationFiles.add(layout.projectDirectory.file("stability_config.conf"))

    // To check the effect of the above, add these two lines and run
    //   gradle :app:compileDebugKotlin --rerun
    // (--rerun matters: an incremental compile only rewrites the report for the files it recompiles).
    // app/build/compose-reports then says, per composable, which parameters are stable.
    //   reportsDestination = layout.buildDirectory.dir("compose-reports")
    //   metricsDestination = layout.buildDirectory.dir("compose-metrics")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    "uitestImplementation"(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Plain JVM tests (app/src/test), e.g. the agenda ordering and filtering in AgendaTest.
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.androidx.test.runner)
}
