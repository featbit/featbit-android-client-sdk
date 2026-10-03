# FeatBit Android Client SDK

Kotlin implementation with Java-compatible public APIs, targeting Android API 21+.

**Current state: Phase 7 release preparation; full release acceptance remains open.** The project builds debug/release AARs with
local evaluation, Bootstrap, TestData/Custom sources, Identify, online/offline intent,
subscriptions, coroutine adapters, bounded Close, persistent cache and anonymous identity.
Built-in Streaming/Polling, reconnect, Identify isolation, optional fallback/recovery and
platform-aware background polling are implemented. Evaluation/Track events, privacy filtering,
bounded queues/retries, Flush and final Close delivery are implemented. Configure `eventsUrl`
for enabled analytics. Process lifecycle, connectivity and device-idle observers are connected;
physical-device/deployed-storage acceptance and formal publication remain outstanding.
Do not use this snapshot as a production feature-flag SDK.

- [Implementation plan](./plan.md)
- [Java/Kotlin integration and configuration](./docs/integration.md)
- [Phase 7 acceptance evidence and limitations](./docs/phase-7.md)
- [Release preparation and toolchain matrix](./docs/release.md)
- [Conformance mapping and remaining gates](./docs/conformance.md)
- [Architecture](./architecture.md)
- [Phase 1 decisions and build commands](./docs/phase-1.md)
- [Phase 2 runtime, usage and boundaries](./docs/phase-2.md)
- [Phase 3 persistence, clearing and identity](./docs/phase-3.md)
- [Phase 4 online synchronization and integration checks](./docs/phase-4.md)
- [Phase 5 events, privacy, Flush and Close](./docs/phase-5.md)
- [Phase 6 lifecycle installation, background policy and device checks](./docs/phase-6.md)
- [Verification results](./docs/verification.md)
- [手动验证指南（Android Studio / 模拟器）](./docs/manual-verification.md)
- [开发交接与当前进度](./docs/handoff.md)

Build with JDK 17 and Android SDK 34:

```sh
bash gradlew :sdk:assembleDebug :sdk:assembleRelease :sdk:testDebugUnitTest :sdk:lintRelease :sdk:publishReleasePublicationToLocalTestRepository
python3 tools/check_api.py
bash gradlew -p consumer-tests :java:assembleRelease :java:testDebugUnitTest :kotlin:assembleRelease :kotlin:testDebugUnitTest
```

On Windows use `.\gradlew.bat`. Outputs are under `sdk/build/outputs/aar/` and
the local test Maven repository `build/test-repository/`. The Maven coordinates are
`co.featbit:featbit-client-android`; the local development version is `0.1.0-SNAPSHOT`.
Nothing is published remotely by these commands.

For the isolated Java/Kotlin compiler matrix, use `python tools/acceptance.py`.
Add `--serial emulator-5554` to install and execute Debug/R8 test APKs. This additionally
requires build-tools 35.0.0 for the Kotlin 2.2.10 consumer; the core SDK compiler stays 1.9.25.
See the release guide for per-run evidence, signing preparation and unexecuted release gates.

`consumer-tests/` contains independent Java/Kotlin compilation and R8 fixtures, not samples.
Samples are deferred. OpenFeature is a separate product and is not a dependency.
