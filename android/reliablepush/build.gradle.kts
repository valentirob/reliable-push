plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
    signing
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

    buildTypes {
        release {
            // Keeps the AAR under the 100 KB budget (CI gate); proguard-rules.pro keeps the whole public API
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
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

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.reliablepush"
            artifactId = "reliable-push-android"
            version = sdkVersion
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Reliable Push Android SDK")
                description.set("Delivery and read tracking for FCM push notifications, with server-side fallback.")
                url.set("https://github.com/valentirob/reliable-push")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("valentirob")
                        name.set("Reliable Push maintainers")
                    }
                }
                scm {
                    url.set("https://github.com/valentirob/reliable-push")
                    connection.set("scm:git:https://github.com/valentirob/reliable-push.git")
                }
            }
        }
    }
}

// Sign only when the release job provides the key; local and CI builds publish unsigned.
val signingKey = providers.environmentVariable("SIGNING_KEY")
val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")
if (signingKey.isPresent && signingPassword.isPresent) {
    signing {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
        sign(publishing.publications["release"])
    }
}
