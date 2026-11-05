plugins {
    alias(libs.plugins.android.library)
}

val sdkVersion = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "io.reliablepush.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // VersionTest compares ReliablePush.VERSION with gradle.properties VERSION_NAME
            it.systemProperty("rp.expectedVersion", sdkVersion)
            // Robolectric reaches into JDK internals (FileDescriptor) on JDK 21
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    compileOnly(libs.firebase.messaging)

    testImplementation(libs.firebase.messaging)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
