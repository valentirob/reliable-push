# Getting started: run and test each component

This guide takes you through every component step by step. First you run the whole system on your laptop in a few
minutes. Then you build, run and test each component on its own. Every step ends with what you should see, so you know
it worked.

How the pieces fit together: [overview.md](overview.md). Reference for every test suite: [testing.md](testing.md).

## 0. Prerequisites

Install only what the components you need require:

| You want to... | You need |
|---|---|
| Try the demo console, run the backend E2E | Docker with the compose plugin, `curl`, free ports 8080, 8090 and 8091 on 127.0.0.1 |
| Build and test the backend, harness, client, load tests | JDK 21 and Docker (Testcontainers) |
| Build and test the Android SDK | JDK 21, Android SDK with platform `android-37` (`ANDROID_HOME`); an AVD for the emulator suite |
| Build and test the iOS SDK | macOS, Xcode 26.6 selected with `xcode-select`, the iPhone 17 Simulator |
| Validate the Helm chart | Docker only (tools run in pinned containers); kind and kubectl for the smoke install |

On a Mac with Homebrew OpenJDK 21 and Colima, set these once per shell before the Maven and Gradle commands:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export ANDROID_HOME=$HOME/Library/Android/sdk
```

All commands run from the repository root unless the step says otherwise.

## 1. Ten-minute tour: the demo console

The demo runs the real backend (one API, two workers, PostgreSQL) with a test harness that plays the sender, the
devices and the fallback receiver. It needs only Docker.

1. **Start the stack.**

   ```bash
   ./scripts/demo.sh
   ```

   ✅ The script prints the console URL and a stop command. Open <http://127.0.0.1:8090/>. A banner reads "Test harness —
   simulated data, not for production". The fast clock is on: 1 product minute = 1 second.

2. **Register a simulated device.** Open **Devices**. In "Register a simulated device" pick app `shop`, platform
   `ANDROID`, user id `alice`, then click **Register device**.

   ✅ The device appears in the list. The harness signed a grant, and the backend registered the device and issued its
   device token.

3. **Send a push that is read.** Open **Send push**. Set user id `alice`, policy `NOT_READ`, timeout `2` minutes.
   Behaviour: "deliver then read". Click **Send push**.

   ✅ "Sent. Tracking id: …". The preview shows the payload your real backend would send to FCM or APNs, with `rp_tid`.

4. **Watch it live.** Open **Live tracking** and select the notification.

   ✅ The timeline goes `PENDING` → `DELIVERED` → `READ`. The policy is satisfied, so no fallback is sent.

5. **Send a push that never arrives.** In **Send push**, same user, behaviour `never`, timeout `1`.

   ✅ After about 1 second (one fast minute) the state becomes `FALLBACK_PENDING`, then `FALLBACK_TRIGGERED`.

6. **See the fallback call.** Open **Fallback inbox**.

   ✅ The signed webhook is listed: `trackingId`, `userId`, `fallback`, `reason`. The harness checked the
   `X-RP-Signature`. Set "Receiver behaviour" to `FAIL_500` and send another `never` push: the worker retries with
   backoff, which you can watch in Live tracking.

7. **Try the other screens.**
   - **Scenarios** runs the E2E scenarios from the browser. The worker-crash scenarios need `scripts/e2e.sh`.
   - **Logs** searches the backend's JSON log by `trace.id`, `rp.tracking_id` or `event.action`.
   - The device list has **Go offline**, to simulate late delivery.

8. **Open the ops dashboard.** The demo backend serves a read-only dashboard with its own admin key. The key is generated
   for each run and written to `harness/build/demo/secrets.env` (git-ignored) as `HARNESS_ADMINKEY`. Read it yourself from
   that file; it is not printed and not in the docs. Open <http://127.0.0.1:8080/admin/>, paste the key and sign in.

   ✅ **Overview** shows the notifications you just sent (use the 24 h window) with delivery and read rates, and the
   fallbacks triggered. **Search**: app `shop`, user `alice`, then open a result: the timeline lists the `DELIVERED` and
   `READ` events and, for the `never` push, the webhook attempts (a `FAIL_500` receiver shows `500` attempts before the
   success). "Show payload" works because this key has the `payload` permission. More: [admin.md](admin.md).

9. **Stop.** Run the `docker compose ... down -v` command that the script printed.

`./scripts/demo.sh --realtime` runs the same demo with real minutes.

## 2. Backend

### 2.1 Build and test

```bash
cd backend && ./mvnw -B verify
```

✅ `BUILD SUCCESS`. This runs the unit tests, the integration tests (real PostgreSQL in Testcontainers, WireMock
receivers) and the 80% line-coverage gate. Reports are in `backend/target/`.

```bash
cd backend && ./mvnw -B verify -Pimage
```

✅ `BUILD SUCCESS`. This builds the container image and checks it: non-root, read-only filesystem, under 250 MB.

### 2.2 Run it on its own

Follow the [README quick start](../README.md#quick-start). It uses Docker only, with no JDK:

1. build the image;
2. generate throwaway secrets with `openssl`;
3. write `rp.yml`;
4. start PostgreSQL and the backend;
5. register a notification with `curl`.

✅ `curl -s http://localhost:8080/actuator/health/readiness` returns `{"status":"UP"}`, and `POST /v1/notifications`
returns `201 {"trackingId": ..., "state": "NO_DEVICE_REGISTERED"}` (no device is registered for that user).

### 2.3 Test it by hand

With the stack from 2.2 running and `RP_SENDER_KEY` set:

1. Register: the `POST /v1/notifications` call of the quick start, with the `trackingId` you generated (`$TID`).
   Repeat it: ✅ the same row comes back (idempotent).
2. Read it back:

   ```bash
   curl -s -H "X-Api-Key: $RP_SENDER_KEY" http://localhost:8080/v1/notifications/$TID
   ```

   ✅ `"state":"NO_DEVICE_REGISTERED"`: the user has no registered device, so nothing is tracked and no fallback will
   fire. A user with a device (the harness in section 4, or an app with the SDK) gets `PENDING`.
3. Try to cancel it:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -X DELETE -H "X-Api-Key: $RP_SENDER_KEY" http://localhost:8080/v1/notifications/$TID
   ```

   ✅ `409`: the state is final. A `PENDING` notification would answer `204` and become `CANCELLED`.
4. Watch the logs: `docker logs rp-app`. ✅ JSON lines with `event.action` `notification.no_device_registered` and
   `notification.duplicate`, with no user ids or message content.

Every endpoint is in [api.md](api.md), and every setting in [operations.md](operations.md).

### 2.4 End-to-end suite

```bash
./scripts/e2e.sh
```

✅ Ends with `[e2e] summary: 23/23 passed`. It runs in about 8 minutes and covers the 22 scenarios plus the single `console-smoke`
entry, which runs both Playwright classes (the web console and the dashboard). It includes worker crashes inside and outside the trigger window. Results are in
`harness/build/e2e/summary.json`. Run one scenario with `--scenario <name>`, and keep the stack for inspection with
`--keep`.

## 3. Java sender client

```bash
./mvnw -B -pl client-java verify
```

✅ `BUILD SUCCESS`. The tests run against a local HTTP server.

To use it from your project, install it into `~/.m2`:

```bash
./mvnw -B -pl client-java install
```

Then call `SenderClient.builder().baseUrl(...).apiKey(...).build()`. Point it at the backend from 2.2 and call
`register(...)` with a `trackingId`. ✅ It returns a `Notification` with that `trackingId`. Usage:
[client-java/README.md](../client-java/README.md).

The payload jar (Java 8+, zero dependencies) builds and tests the same way:

```bash
./mvnw -B -pl payload-java verify    # tests, golden vectors included
./mvnw -B -pl payload-java install   # into ~/.m2
```

✅ `BUILD SUCCESS`. Usage: [payload-java/README.md](../payload-java/README.md).

## 4. Harness

```bash
./mvnw -B -pl harness -am verify
```

✅ `BUILD SUCCESS`. This builds and tests the simulators, the receiver, the console API and the scenario runner. No
Docker is needed for this step. The harness runs inside the stacks of `demo.sh`, `e2e.sh`, `android-e2e.sh` and
`ios-e2e.sh`.

To check the console in a real browser, start `./scripts/demo.sh`, then run:

```bash
./mvnw -B -pl harness -am verify -Pconsole-smoke
```

The first run downloads Playwright's Chromium, about 150 MB. ✅ A headless browser registers a device, sends a push and
sees `DELIVERED` and the fallback.

## 5. Android SDK

### 5.1 Build and unit tests

```bash
cd android && ./gradlew build
```

✅ `BUILD SUCCESSFUL`. This runs lint and the Robolectric unit tests, and builds the debug and release AAR. Check the
release AAR size, which must stay under 102,400 bytes:

```bash
ls -l android/reliablepush/build/outputs/aar/reliablepush-release.aar
```

### 5.2 Try the sample app by hand (emulator)

1. Start an emulator: the AVD `Medium_Phone_API_37.0`, or any API 37 AVD.
2. Start the stack: `./scripts/demo.sh`.
3. Install the sample:

   ```bash
   cd android && ./gradlew :sample:installDebug
   ```

4. Open **Reliable Push Sample** on the emulator, keep the generated user id and tap **Register**.

   ✅ "Registered" and a device id. In the console's **Devices** screen the device shows as `REAL`.
5. In the console, open **Send push**, enter that user id and send.

   ✅ Without Firebase files no real push is sent. The preview shows an `adb shell am broadcast ...` line instead: run it
   in a terminal on your Mac. The notification appears on the emulator, and Live tracking shows `DELIVERED`.
6. Tap the notification. ✅ Live tracking shows `READ`.
7. Optional: turn on airplane mode before step 5, then turn it off. ✅ `DELIVERED` arrives within about 30 s after
   the network is back. This is the SDK's retry job at work.

### 5.3 Emulator E2E suite

```bash
./scripts/android-e2e.sh
```

✅ Ends with `Android E2E: 8/8 passed, 1 skipped`. The skipped entry is `fcm-real-delivery`, which needs the
maintainer's Firebase files ([TODO.md](TODO.md) 1.2). Add `--release-lib` to test the minified release AAR. Guide:
[android.md](android.md).

## 6. iOS SDK

### 6.1 Package tests and gates

```bash
xcodebuild test -scheme ReliablePush -destination "platform=iOS Simulator,name=iPhone 17"
```

✅ `** TEST SUCCEEDED **`, with 108 tests and 1 skipped. The skipped one is the real Keychain test, which runs in the
sample instead.

```bash
ios/scripts/size.sh
```

✅ It prints `... bytes (limit 307200)` and exits 0.

```bash
plutil -lint ios/Sources/ReliablePush/PrivacyInfo.xcprivacy
```

✅ `OK`.

### 6.2 Try the sample app by hand (Simulator)

1. Start the stack: `./scripts/demo.sh`. The Simulator reaches the backend through the harness device proxy on
   `127.0.0.1:8091`.
2. Open `ios/Sample/ReliablePushSample.xcodeproj` in Xcode. Pick the scheme **ReliablePushSample** and the iPhone 17
   Simulator, then press Run.
3. Tap **Register**, and allow notifications if iOS asks.

   ✅ A device id appears. The console's **Devices** screen shows the device as `REAL`, with user `ios-sample-user`.
4. In the console, open **Send push** to `ios-sample-user`.

   ✅ The preview shows an `xcrun simctl openurl booted 'rpsample://debug-push?...'` line. Run it in a terminal. The
   Simulator does not run a Notification Service Extension for simulated pushes, so this debug link runs the same
   extension code in the app. A notification appears, and Live tracking shows `DELIVERED`.
5. Tap the notification. ✅ `READ`.
6. Optional: in **Devices**, switch "Real device network" to offline, then send and run the `openurl` line.
   ✅ `DELIVERED` stays in the App Group queue. Switch the network back online and tap the notification:
   `DELIVERED` and `READ` arrive together. The app flushes the queue when it opens a notification.

### 6.3 Simulator E2E suite

```bash
./scripts/ios-e2e.sh
```

✅ Ends with `iOS E2E: 8/8 passed, 1 skipped`. The skipped entry is `apns-real-delivery`, which needs a paid Apple
Developer team ([TODO.md](TODO.md) 1.1). Guide: [ios.md](ios.md).

## 7. Helm chart

```bash
deploy/helm/scripts/validate.sh
```

✅ Exit 0. This runs lint, the helm-unittest suites and kubeconform on four renders: default, split, GKE and OpenShift.

```bash
./scripts/helm-smoke.sh
```

This needs kind, kubectl and JDK 21. ✅ Every check prints `PASS` and the script exits 0. It installs the chart on a
local kind cluster in split mode and restarts the workers while webhook calls are in flight: every notification is
triggered exactly once. Real clusters: [deployment.md](deployment.md).

## 8. Load tests

```bash
./mvnw -B -pl loadtest verify
```

✅ `BUILD SUCCESS`. This compiles the simulations and runs the unit tests, but never starts Gatling.

```bash
./scripts/load.sh --simulation ingest
```

✅ Exit 0, and `harness/build/load/summary.json` has `"status": "PASSED"`. This is the NFR-2 run: 500 notifications/s
and 1,000 events/s for 5 minutes. It cannot run next to the demo or E2E stack, because they use the same ports.
`--simulation all` takes about 15 minutes. Results and how to read them: [operations.md](operations.md#measured-capacity).

## 9. What CI runs

On every push to `develop` or `main`, GitHub Actions runs the workflows of the components that changed:

- the backend build with the image and Trivy scan;
- client, harness and load-test builds;
- the Android build;
- the iOS package tests, sample builds, size and manifest gates;
- the Helm checks;
- CodeQL for Java/Kotlin and Swift.

The end-to-end, emulator, Simulator, kind and load suites run only locally, with the commands above.
