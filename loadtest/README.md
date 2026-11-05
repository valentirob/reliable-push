# loadtest

Gatling (Java DSL) simulations for the local NFR-2 load test, plus JDK-only helpers. Local only: never run in CI.
`./mvnw -B -pl loadtest verify` compiles the simulations and runs the unit tests; it never starts Gatling.
`scripts/load.sh` runs everything inside the compose network (see `loadtest/compose/`).

| Class | Role |
| --- | --- |
| `LoadConfig` | Settings from the environment (`LOAD_*`, `HARNESS_APPS_SHOP_*`); `toString()` hides secrets |
| `GrantSigner` | HS256 signed grant, same shape as the harness `GrantMinter` |
| `DeviceProvisioner` | Registers `LOAD_DEVICES` devices (at most 50 in flight), returns feeder rows |
| `EventIngestSimulation` (test sources) | 500 notifications/s + 1,000 events/s; NFR-2 assertions per group `register` and `events`; an arrival whose register failed sends no events |
| `FallbackBurstSimulation` (test sources) | `LOAD_BURST_WARMUP` (default 30 s) of warm-up requests (`notify-warm-up`, not asserted), then `LOAD_BURST_SIZE` notifications (`notify`) at `LOAD_BURST_PER_SECOND`, drained by the workers |
| `BurstDrainProbe` | Plain JDK main: samples worker `/actuator/prometheus` every second, writes drain JSON |

Run one simulation explicitly (the Gatling plugin is bound to no phase):

```
./mvnw -B -pl loadtest gatling:test -Dgatling.simulationClass=io.reliablepush.loadtest.EventIngestSimulation
```

Start the probe before the burst (counters are deltas from its first sample):

```
java -cp loadtest/target/classes io.reliablepush.loadtest.BurstDrainProbe \
  --metrics http://worker-1:8081/actuator/prometheus http://worker-2:8081/actuator/prometheus \
  --expected 30000 --timeout 10m --out drain.json
```

Exit code 0 means `PASSED`; `FAILED` on timeout or any `failed`/`expired` increase.

Logging: `src/test/resources/logback-test.xml` sets root to WARN and `io.gatling.http.engine.response` to OFF. Do not
raise it: at DEBUG Gatling prints failed requests with their headers (`X-Api-Key`, device tokens). Gatling's console
summary is unaffected. `LOAD_BURST_SIZE` must be a multiple of `LOAD_BURST_PER_SECOND`.
