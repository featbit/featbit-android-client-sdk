# FeatBit Android Client SDK

Kotlin implementation with Java-compatible public APIs, targeting Android API 21+.

**Current state: Phase 2 local runtime.** The project builds debug/release AARs with
local evaluation, Bootstrap, TestData/Custom sources, Identify, online/offline intent,
subscriptions, coroutine adapters and bounded Close. Persistent cache, anonymous storage,
built-in network synchronization and analytics delivery remain later-phase work.
Do not use this snapshot as a production feature-flag SDK.

- [Implementation plan](./plan.md)
- [Architecture](./architecture.md)
- [Phase 1 decisions and build commands](./docs/phase-1.md)
- [Phase 2 runtime, usage and boundaries](./docs/phase-2.md)
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
the local test Maven repository `build/test-repository/`. The planned coordinates are
`co.featbit:featbit-client-android`; the local development version is `0.1.0-SNAPSHOT`.
Nothing is published remotely by these commands.

`consumer-tests/` contains independent Java/Kotlin compilation and R8 fixtures, not samples.
Samples are deferred. OpenFeature is a separate product and is not a dependency.
