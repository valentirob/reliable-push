plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.reliablepush.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.reliablepush.sample"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
