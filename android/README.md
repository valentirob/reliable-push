# reliable-push Android SDK

Kotlin SDK (`io.reliablepush:reliable-push-android`, minSdk 26, compileSdk 37). Own Gradle build, not part of the Maven reactor.

## Layout

| Path | What |
|---|---|
| `reliablepush/` | The library (package `io.reliablepush.android`, `explicitApi()`) |
| `sample/` | Sample app (empty for now) |
| `gradle/libs.versions.toml` | Every pinned version, with release dates |
| `gradle.properties` | `VERSION_NAME`, the SDK version |

## Prerequisites

- JDK 21 (`JAVA_HOME`)
- Android SDK with platform `android-37` (`ANDROID_HOME`, default `$HOME/Library/Android/sdk`)
- No global Gradle: use `./gradlew` (the wrapper verifies the distribution checksum)

## Commands

| Task | Command |
|---|---|
| Lint, unit tests, debug and release AAR | `./gradlew build` |
| Unit tests only | `./gradlew :reliablepush:testDebugUnitTest` |
| Runtime dependencies of the library | `./gradlew :reliablepush:dependencies --configuration releaseRuntimeClasspath` |

Robolectric tests run on JDK 21 with `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED` (set in the build).
