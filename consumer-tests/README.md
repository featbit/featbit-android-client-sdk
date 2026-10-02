# Device model and local runtime checks

完整中文步骤、常见问题与已验证范围：[手动验证指南](../docs/manual-verification.md)。

These Java/Kotlin consumers use the real Maven-staged SDK AAR. They are test fixtures,
not SDK samples. They create local TestData and Custom clients, without network connections.

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

Debug APKs are signed automatically for device installation. Release builds still run R8,
but remain unsigned: release device execution requires a separate test signing setup.
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
