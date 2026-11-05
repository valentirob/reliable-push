plugins {
    alias(libs.plugins.android.application)
}

// Where the sample talks to (debug defaults: the local harness stack seen from the emulator, spec §10).
// Override with -Prp.baseUrl=… -Prp.appId=… -Prp.harnessUrl=…
val rpBaseUrl = providers.gradleProperty("rp.baseUrl").getOrElse("http://10.0.2.2:8080")
val rpAppId = providers.gradleProperty("rp.appId").getOrElse("shop")
val rpHarnessUrl = providers.gradleProperty("rp.harnessUrl").getOrElse("http://10.0.2.2:8090")

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

        buildConfigField("String", "RP_BASE_URL", quoted(rpBaseUrl))
        buildConfigField("String", "RP_APP_ID", quoted(rpAppId))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // with the orchestrator: every test in its own process (one refresh per process) and with empty storage
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    buildTypes {
        debug {
            // the harness client and the debug push receiver exist only in the debug source set
            buildConfigField("String", "RP_HARNESS_URL", quoted(rpHarnessUrl))
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
    implementation(project(":reliablepush"))
    // the SDK has it compile-only: the host app provides firebase-messaging
    implementation(libs.firebase.messaging)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.junit)
    androidTestUtil(libs.androidx.test.orchestrator)
}
