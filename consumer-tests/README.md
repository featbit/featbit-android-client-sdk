# Device model and local runtime checks

For combined acceptance run `python tools/acceptance.py --serial emulator-5554` from the SDK root.
It creates separate consumer roots and Wrappers for Java and Kotlin 1.9.24/1.9.25/2.2.10,
resolves only the freshly staged Maven artifact, runs lint/Debug/R8/JUnit and installs both
variants. R8 APKs are signed with the local debug test key solely for these checks. Shared
ReleaseContract assertions check artifact version, immutable state and synchronous reads
under StrictMode. Kotlin additionally runs AttributeSmoke and the platform fixture.
See [release commands and toolchains](../docs/release.md). The interactive project below
uses its default Kotlin 1.9.25/AGP 8.5.2/Gradle 8.7; use the runner for the full matrix.

Phase 6 adds an opt-in public-AAR lifecycle fixture. After staging the current SDK and
building the Kotlin APK with `-Pphase6Probe=true` and installing it, run `python tools/platform_device_checks.py` from the
repository root (use `--adb` for its full path). See [Phase 6](../docs/phase-6.md) for
commands, emulator setting changes and acceptance boundaries. The exported probe receiver
is test-only and is not part of the SDK AAR. Probe registration is absent by default in
both Debug and Release; the flag explicitly enables either variant for device validation.

完整中文步骤、常见问题与已验证范围：[手动验证指南](../docs/manual-verification.md)。

These Java/Kotlin consumers use the real Maven-staged SDK AAR. They are test fixtures,
not SDK samples. The default page checks local TestData and Custom clients without SDK
transport connections; opt-in network/event checks below use the target fixture. Current AAR
clients install Android lifecycle/network/idle observers, including in local checks.

1. In the SDK root, run `./gradlew :sdk:publishReleasePublicationToLocalTestRepository`
   (Windows: `.\gradlew.bat :sdk:publishReleasePublicationToLocalTestRepository`).
2. Open this `consumer-tests` directory as a separate Android Studio project, select
   Gradle JDK 17, and let Gradle sync finish.
3. Start an Android API 21+ emulator, or connect a device with USB debugging enabled.
4. Choose the `java` or `kotlin` Android App run configuration, use the `debug` build
   variant, select the device, and click Run. If there is no run configuration, create an
   Android App configuration for the corresponding module with Launch: Default Activity.
5. The page executes `ModelSmoke.verify()` followed by asynchronous `RuntimeSmoke.verify()` and displays **PASS** or **FAIL**.
   Use the retry button to run again; the check count and completion time update on each run.
   Search Logcat for `FeatBitConsumer` for results and
   failure stack traces. Run both modules to check both consumer languages.

The checks cover models, TestData/Custom sources, evaluation, Identify, online/offline,
subscriptions and Close. Kotlin additionally exercises suspend/Flow and logs a 5,000-record
conversion/heap probe. JVM consumer tests cover models and inactive TestData mutations;
the Android runtime checks require launching the Activity.
A PASS does not establish network, event delivery or complete SDK conformance.

Debug APKs are signed automatically for device installation. Direct builds of this interactive
project run R8 for Release but leave its APK unsigned. The Phase 7 runner signs its generated
Release APKs with a local test key and exercises them on device when `--serial` is supplied;
see [recorded results](../docs/verification.md). This is not trusted release signing.
Debug device results must not be reported as release/R8 device results.
## Phase 3 persistence checks

The Kotlin runtime launcher also verifies cache and anonymous identity through the
staged AAR and real Android AtomicFile storage. Launch once, wait for `PHASE3_PASS`,
force-stop the Kotlin test app and launch again: `previousProcessData=true` confirms
that the prior process's cache and anonymous key were read before replacement.
The fixture uses an isolated subdirectory under noBackupFilesDir, no service/network,
and leaves one snapshot for the next run. Clear retains active values and the anonymous
key; post-clear commits may repopulate the cache. Physical-device backup restoration
and termination at each atomic-write stage remain later acceptance checks.

## Phase 5 event checks

Stage the latest AAR and rebuild both consumers first. Start the target evaluation-server
Fake fixture as described in [phase-4.md](../docs/phase-4.md), install the debug APKs, then:

```powershell
adb reverse tcp:5189 tcp:5189
adb shell am start -S -n co.featbit.consumer.java/.SmokeActivity --ez phase5 true
adb shell am start -S -n co.featbit.consumer.kotlin/.SmokeActivity --ez phase5 true
adb logcat -d -s FeatBitConsumer:I '*:S'
```

Each language must emit `PHASE5_PASS`. These opt-in runs exercise actual AAR factory creation,
Track before readiness, deduplication, remote evaluation, private-attribute options, Flush,
offline suppression/retention/resumption and final Close delivery. Java uses callbacks and Kotlin
uses suspend adapters. The separate JVM live export plus target Domain verifier checks the
actual filtered wire payload and resulting message content; the Activity does not inspect server
storage. No production databases/MQ are involved. The current SDK installs real platform
observers, but this event fixture does not independently establish lifecycle/Doze acceptance;
use the Phase 6 platform fixture for those checks. Remove the
test reverse mapping afterward with `adb reverse --remove tcp:5189`.
