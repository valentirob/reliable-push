plugins {
    alias(libs.plugins.android.application)
}

// Where the sample talks to. Debug: the local harness stack seen from the emulator (spec §10), overridable with
// -Prp.baseUrl=… -Prp.appId=… -Prp.harnessUrl=…. Release: an https placeholder (-Prp.releaseBaseUrl=…), never the
// cleartext emulator address (a release build has no cleartext permission and would queue events forever).
val rpBaseUrl = providers.gradleProperty("rp.baseUrl").getOrElse("http://10.0.2.2:8080")
val rpReleaseBaseUrl = providers.gradleProperty("rp.releaseBaseUrl").getOrElse("https://reliable-push.example.invalid")
val rpAppId = providers.gradleProperty("rp.appId").getOrElse("shop")
val rpHarnessUrl = providers.gradleProperty("rp.harnessUrl").getOrElse("http://10.0.2.2:8090")

// Real FCM (optional, Task 7): the Google Services plugin is applied only when the maintainer placed
// android/sample/google-services.json (git-ignored). Without it the build, the tests and the E2E run as before and the
// app has no default FirebaseApp (every Firebase call in the sample is guarded).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "io.reliablepush.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.reliablepush.sample"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "RP_APP_ID", quoted(rpAppId))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // with the orchestrator: every test in its own process and with empty storage
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    buildTypes {
        debug {
            buildConfigField("String", "RP_BASE_URL", quoted(rpBaseUrl))
            // the harness client and the debug push receiver exist only in the debug source set
            buildConfigField("String", "RP_HARNESS_URL", quoted(rpHarnessUrl))
        }
        release {
            buildConfigField("String", "RP_BASE_URL", quoted(rpReleaseBaseUrl))
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    // -Prp.sampleLibVariant=release (./scripts/android-e2e.sh --release-lib): the debug sample consumes the library's
    // minified RELEASE AAR from mavenLocal (published by :reliablepush:publishToMavenLocal), so the emulator suite
    // exercises the R8-processed code that ships. Otherwise it uses the project (debug variant for the debug build).
    if (providers.gradleProperty("rp.sampleLibVariant").orNull == "release") {
        debugImplementation("io.reliablepush:reliable-push-android:${providers.gradleProperty("VERSION_NAME").get()}")
        releaseImplementation(project(":reliablepush"))
    } else {
        implementation(project(":reliablepush"))
    }
    // the SDK has it compile-only: the host app provides firebase-messaging
    implementation(libs.firebase.messaging)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.junit)
    androidTestUtil(libs.androidx.test.orchestrator)
}
