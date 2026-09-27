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
        applicationId = "com.example.itinerary"
        minSdk = 26 // java.time works natively from API 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
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
