# FeatBit Café Java

Java implementation of the same native Android sample as [Kotlin](../kotlin).
Open **samples/** in Android Studio and choose the **java** run configuration.
Launcher: **FeatBit Café Java** (`co.featbit.sample.java`). Both apps can be installed together.

## Build and run

Use JDK 17 and Android SDK Platform 34. From the repository root:

```powershell
.\gradlew.bat :sdk:publishReleasePublicationToLocalTestRepository
.\samples\gradlew.bat -p samples :java:installDebug
```

Linux/macOS: use `bash gradlew` / `bash samples/gradlew` for the same tasks.
The sample starts in Local Demo without credentials. Live endpoints and the client-side SDK key
are entered in Connection and kept only in process memory.

## Shared design and resources

The product contract is [shared README](../README.md), [interaction design](../interaction-design.md),
[visual system](../DESIGN.md), and [scenario matrix](../setup-and-acceptance.md).
The approved boards are shared directly rather than copied into a second design:

- [Overview](../ui-overview-v2.png)
- [Checkout variants](../ui-checkout.png)
- [Connection and users](../ui-connection-users.png)
- [Flag editing](../ui-flag-editing.png)
- [Streaming fallback](../ui-connection-fallback-proposal.png)

`build.gradle.kts` includes `../shared/res` and `../shared/assets`: layouts, colors, themes,
strings, icons, coffee image, flags and users. Only the launcher label is language-specific.
No SDK wrapper or Kotlin sample code is shared. The Java module consumes the locally published
SDK AAR, including its transitive runtime dependencies.

## Source map

- `CafeApplication.java`: application-scoped session.
- `SampleSession.java`: Create/Close, operation callbacks, subscriptions, identity, TestData,
  typed evaluation, Track and Flush. Activity observation attaches/detaches with its lifecycle.
- `CafeModel.java`: sample models, strict JSON/menu validation, decimal pricing.
- `MainActivity.java`: Demo, Flags, Inspect and details; navigation and rendering.
- `CafeForms.java`: Connection drafts, user sheet, typed local editors and confirmations.
- `CafeViews.java`: native view construction matching the shared visual system.

All source files are under `src/main/java/co/featbit/sample/java/`.

## Verify

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat spotlessCheck
.\samples\gradlew.bat -p samples :java:testDebugUnitTest :java:lintDebug :java:lintRelease
.\samples\gradlew.bat -p samples :java:assembleDebug :java:assembleRelease :java:assembleDebugAndroidTest
```

Device test and controlled Live fixture commands are in the
[implementation guide](../implementation-guide.md#java-sample).
Release uses R8 and the debug signing key for local demonstration, matching Kotlin.
See [VERIFICATION.md](./VERIFICATION.md) for actual evidence and remaining acceptance limits.
