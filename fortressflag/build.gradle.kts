plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.fortressflag.sdk"
    compileSdk = 35

    defaultConfig {
        // minSdk 26: Keystore maturity plus java.time, and the vast majority of devices
        // (ADR-0013). Raising it is a public-surface decision, not a convenience.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Warnings are errors: the SDK ships inside customers' apps, and "we'll fix the
        // warning later" is how a shipped SDK accumulates the problems we cannot recall.
        allWarningsAsErrors = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

ktlint {
    // The lint gate is strict by construction; see also the separate crash-primitives CI job,
    // which stays independent of this config on purpose.
    version.set("1.5.0")
}

dependencies {
    // The ONLY runtime dependency (ADR-0013): supply-chain surface on end users' devices is
    // the Security pillar's concern, and coroutines are the platform-idiomatic concurrency
    // primitive — the analogue of Swift Concurrency, not a "dependency" in that sense.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.orgjson)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
