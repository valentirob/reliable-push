pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ./scripts/android-e2e.sh --release-lib: the sample consumes the minified release AAR that
        // :reliablepush:publishToMavenLocal installed (see sample/build.gradle.kts)
        if (providers.gradleProperty("rp.sampleLibVariant").orNull == "release") {
            exclusiveContent {
                forRepository { mavenLocal() }
                filter { includeGroup("io.reliablepush") }
            }
        }
    }
}

rootProject.name = "reliable-push-android"
include(":reliablepush", ":sample")
