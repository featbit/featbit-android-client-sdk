# Android sample implementation guide

Status: Kotlin and Java apps share the independent sample build.

This guide explains how the Kotlin and Java Android implementations realize the
[language-independent product design](./README.md) and [interaction contract](./interaction-design.md).
It owns language APIs, Android Views, resource sharing, modules, toolchain, build commands,
and runtime integration mechanics. Those choices do not define a second product design.
The same screens, business data, copy, states, and behavioral acceptance apply to each implementation.

## Language and UI implementation choices

| Area | Kotlin sample | Java sample |
| --- | --- | --- |
| UI | Native Android Views | Native Android Views |
| SDK | The same core Android SDK artifact | The same core Android SDK artifact |
| Async operations | Existing suspend adapters where appropriate | `Operation.observe` and explicit outcomes |
| Observations | Existing Flow adapters or public subscriptions | Public subscriptions and registrations |
| Product behavior | [Shared design](./README.md) | [Shared design](./README.md) |

Use the current repository toolchain as the starting point. Do not upgrade the SDK
toolchain solely to build a sample. Consume the published local Maven/AAR artifact;
do not access SDK internal packages. Formal remote artifact publication is not a prerequisite.

The Kotlin app lives in `samples/kotlin/`, under an independent sample Gradle build.
The Java app lives in `samples/java/`; both `:kotlin` and `:java` are included in Gradle settings.
Keep one shared README/design and common flag/user examples; do not maintain separate,
potentially conflicting product specifications for the two languages.

## Ownership, observations, and shared resources

Own one active client at application scope. Do not create clients per screen, evaluation,
or Activity rotation. Keep SDK integration thin and readable rather than adding a generic
framework that hides the public calls developers are here to learn.

UI observation follows the screen lifecycle and releases registrations/collectors when no
longer needed. Leaving a screen does not close the application client. Render initial state
without introducing a read/subscribe race; use subscription initial values or the existing
adapter's initial invalidation behavior as appropriate.

SDK operations must not block the main thread. Removing an observer or cancelling a coroutine
wait does not cancel the underlying operation. Handle registration failures and outer Outcomes.
When reconnecting, account for Close results, including incomplete cleanup or undelivered events.

Both apps share XML layouts, strings, themes, drawables, and canonical sample-data assets
through a resource-only `samples/shared/` source directory. Each app includes those
directories in its own build; this is not a shared SDK wrapper. Keep client ownership,
initialization, subscription cleanup, Identify, evaluation, Track, and Flush code in the
respective language module. Java sample integration code must not depend on Kotlin sample code.
Shared resources must not reference language-specific custom View classes.

Use distinct application IDs `co.featbit.sample.kotlin` and `co.featbit.sample.java` and launcher
labels **FeatBit Café Kotlin** / **FeatBit Café Java**. In-app branding is the same; Inspect
identifies the language and `client.getVersion()`. Both apps can be installed together and have
independent connection drafts, SDK storage, selections, and activity history.

Keep networking and platform behavior inside the SDK. No sample-owned foreground service,
wake lock, lifecycle probe receiver, or custom synchronization implementation is needed.

## SDK integration details

- Use `allVariations()` for passive snapshot browsing without evaluation events; use typed detail
  reads only for business evaluation or the explicit Evaluate flag action.
- Use public SDK builders to validate configuration before closing the existing client.
- Serialize Close then Create, detach old observations, and advance a client revision so old
  completions cannot update the replacement UI.
- Preserve Local edits/removals in an application-owned snapshot; reconstruct TestData on return
  rather than reusing a still-bound instance. Updates use TestData update/remove; Restore all uses
  one replace operation. Distinguish committed and saved-only results.
- Do not enable Bootstrap in the initial Live configuration, so missing-data fallbacks and cache
  behavior remain observable without conflicting data precedence.
- Credentials stay in process memory and must not enter disk preferences or saved-state bundles.
- Use decimal conversion equivalent to `BigDecimal.valueOf` for SDK numbers, then decimal
  arithmetic and HALF_UP rounding to implement the language-independent pricing examples.
- After shutdown, report actual Close cleanup/delivery results rather than assuming completion.

Use SDK startup readiness and Identify waits of 5 seconds for the sample. Kotlin adapter waits
must leave room for the SDK result (for example 6 seconds around a 5-second operation); do not
mistake an adapter wait timeout for cancellation. Reconcile pending operation results instead
of allowing concurrent conflicting actions. Retain the SDK's default 10-second request and
5-second Close timeout; no UI timeout editor is included.

Keep an application-owned selected preset; the SDK does not expose a public current-user getter.
Use the supported explicit Identify contract: a valid admitted request changes context before
its remote-readiness wait settles. Pre-admission failures (`INVALID`, `CLOSED`, capacity rejection)
retain the previous selection. A readiness timeout retains the target preset with an unconfirmed
status, not a rollback. A terminal readiness outcome likewise does not prove rejection of the
identity change. If an unexpected superseded result occurs, the latest app revision owns the UI.
Do not infer identity from flag values or create a new client merely to change users.

## Streaming fallback integration

The [Connection design](./interaction-design.md)
and [three-state board](./ui-connection-fallback-proposal.png) are implemented in both languages.
`ConnectionDraft`, `CafeForms`, and `SampleSession.liveOptions()` own the draft, form and
SDK configuration respectively in each language module.

Use an in-process Boolean fallback draft, initially false, alongside the existing transport
URL drafts. Preserve it across form navigation, mode changes and Activity recreation, but
not process death. Build options from the submitted snapshot, not mutable form state:

| Mode and fallback | SDK builder settings |
| --- | --- |
| Streaming, off | `mode(STREAMING)`, `streamingUrl(...)`, `pollingFallback(false)` |
| Streaming, on | `mode(STREAMING)`, `streamingUrl(...)`, `pollingUrl(...)`, `pollingFallback(true)` |
| Polling | `mode(POLLING)`, `pollingUrl(...)`, `pollingFallback(false)`; omit Streaming URL |

Do not submit a retained true fallback draft in direct Polling mode: the SDK rejects it
with `fallback_requires_streaming`. Local TestData ignores all Live transport drafts.
Validate both active endpoints before retiring the current client, preserving the existing
scheme and deployment-path rules. An inactive URL must not block Apply.
Keep user, key and Events configuration unchanged by the choice of fallback policy.

The SDK owns the continuous 30-second foreground transient-failure window, polling schedule,
cooldown and Streaming recovery probes. Do not add sample timers, manual fallback transports,
or repeated client creation to implement recovery. Successful valid Streaming recovery returns
the effective mode to Streaming. HTTP 401/403 and terminal failures do not trigger a bypass.

Use the running client's `ConnectionInformation` for Inspect. This sample does not enable
background polling: configured Streaming plus effective Polling and non-NONE recovery can
identify fallback. Do not infer it from the draft or from `effectiveMode` alone if background
polling is added later. Keep recovery/candidate failure distinct from initial readiness waits;
recovery is not a promise of remote confirmation. Form checks have emulator evidence;
controlled failure/recovery acceptance remains pending. See the
[verification record](./kotlin/VERIFICATION.md) and [acceptance specification](./setup-and-acceptance.md).

## Build and launch contract

Use JDK 17 and the repository's Android toolchain baseline (SDK/compileSdk 34, minSdk 21,
Java 11 bytecode, AGP 8.5.2, Gradle 8.7, Kotlin 1.9.25). Resolve a compatible Views/Material
dependency set at implementation time rather than silently increasing the SDK requirements.

Existing SDK publication command from repository root on Windows:

```powershell
.\gradlew.bat :sdk:publishReleasePublicationToLocalTestRepository
```

This produces local Maven artifacts under `build/test-repository`; it is not a remote release.
Both samples must resolve `co.featbit:featbit-client-android:0.1.0-SNAPSHOT` there by default,
with shared `sdkVersion` and `testRepository` Gradle properties for alternate local artifacts.
The SDK version used for publication must equal the version consumed by both apps.

Kotlin sample commands from repository root (JDK 17 and Android SDK required):

```powershell
.\gradlew.bat -p samples :kotlin:assembleDebug :kotlin:assembleRelease
.\gradlew.bat -p samples :kotlin:installDebug
```

Open `samples/` as the independent Gradle project in Android Studio, select the Kotlin or Java
run configuration, and launch on an emulator or connected device. On Linux/macOS the equivalent
commands use `bash ./gradlew`. A missing local SDK artifact must produce an actionable setup
instruction, not an undocumented fallback to an unrelated remote version.

Structure:

- `samples/kotlin/`: Kotlin application and SDK integration.
- `samples/java/`: Java application and SDK integration.
- `samples/shared/res/`: common XML layouts, strings, themes, and drawables.
- `samples/shared/assets/`: common canonical demo-data assets and product image when supplied.
- Root sample Gradle settings/build configuration and these shared design documents.

The Kotlin integration is readable in `SampleSession.kt`; the shared directory contains no runtime
SDK wrapper. Each app compiles independently without depending on the other language module.
The Release APK enables R8 and uses the debug signing key for local demonstration only.
It is not a production distribution signing configuration.

## Java sample

The Java module has no Kotlin source or Kotlin Android plugin. It consumes the same SDK AAR
and includes `../shared/res` and `../shared/assets` directly. Gson parses sample JSON;
SDK builders still validate submitted values. `CafeModel.java` owns business values,
`SampleSession.java` owns the application client, and `MainActivity.java`, `CafeViews.java`,
and `CafeForms.java` implement the same native screens as Kotlin.

`Operation.observe` callbacks are marshalled to the main thread. A rejected observation retries
observation of the same operation after checking `getResult()`; it never repeats the SDK action.
Completed registrations close. Client subscriptions close before replacement; a generation check
rejects callbacks already queued for the retired client. Activity state listeners attach in
`onStart` and detach in `onStop`, while SDK work remains application-owned. Initial readiness
wait results also carry a wait revision so they cannot overwrite a later Identify result.

From the repository root:

```powershell
.\samples\gradlew.bat -p samples :java:assembleDebug :java:assembleRelease
.\samples\gradlew.bat -p samples :java:testDebugUnitTest :java:lintDebug :java:lintRelease
.\samples\gradlew.bat -p samples :java:installDebug :java:assembleDebugAndroidTest
adb install -r samples/java/build/outputs/apk/androidTest/debug/java-debug-androidTest.apk
adb shell am instrument -w co.featbit.sample.java.test/androidx.test.runner.AndroidJUnitRunner
# Start the shared protocol fixture in another terminal for the Live test:
python samples/tools/protocol_fixture.py
adb shell am instrument -w -e live true co.featbit.sample.java.test/androidx.test.runner.AndroidJUnitRunner
```

On Linux/macOS replace `.\samples\gradlew.bat` with `bash samples/gradlew`.
See [Java setup and source map](./java/README.md) and [Java evidence](./java/VERIFICATION.md).

## Endpoints and local development

- Hosted example placeholders: `wss://evaluation.example.com`, `https://evaluation.example.com`,
  and `https://events.example.com`. These are fictional and not working hosted demo services.
- Android Emulator uses `10.0.2.2` to reach the development host; `localhost` inside the emulator
  is the emulator itself. Use the service's actual exposed port and deployment base path.
- A physical device uses a reachable host LAN address/DNS name, with server binding and firewall
  configured appropriately. Do not use emulator-only addressing on a physical device.
- Streaming accepts `ws://` and `wss://`; Polling and Events accept `http://` and
  `https://`, for any valid host in both Debug and Release. The sample application's
  main manifest enables cleartext traffic for local, LAN and remote HTTP/WS endpoints.
  HTTPS/WSS certificate verification remains enabled.
- SDK host applications control their own Android cleartext policy; see the
  [integration guide](../docs/integration.md#android-cleartext-connections).
- Connection settings remain process-memory-only. Re-enter them after process death. Rotation
  retains them. Android's ordinary sandbox storage, including SDK cache, is independent per app.

## Engineering validation

Both Debug and R8 Release variants must build and run against the same published local SDK
artifact. Existing `consumer-tests` remain the independent API/compiler/AAR/R8 acceptance suite;
the samples do not replace those tests. Java compatibility continues to be checked there as well
as in the Java sample. Check observation cleanup, no duplicate clients, late-result isolation,
and independent package storage with focused tests where appropriate.

Run the [shared behavioral scenarios](./setup-and-acceptance.md#common-scenario-matrix) for each
implementation. S25 covers visible behavior across configurations; additionally perform it on
Debug and R8 Release. Record app/artifact/SDK version, build variant, device/OS, and evidence.

For deterministic rare failures (Create/Close failure, capacity rejection, stale callback races),
use focused sample-side tests or controlled fixtures during implementation. Never add an exported
test receiver or simulate a success state in the ordinary sample UI to make acceptance pass.


## Deterministic sample tests

```powershell
.\gradlew.bat -p samples :kotlin:testDebugUnitTest :kotlin:lintDebug :kotlin:lintRelease
.\gradlew.bat -p samples :kotlin:assembleDebug :kotlin:assembleDebugAndroidTest
# Separate terminal; binds only to the developer machine's loopback interface:
python samples/tools/protocol_fixture.py
# adb must be on PATH; use an Android emulator (10.0.2.2 is host loopback):
adb install -r samples/kotlin/build/outputs/apk/debug/kotlin-debug.apk
adb install -r samples/kotlin/build/outputs/apk/androidTest/debug/kotlin-debug-androidTest.apk
adb shell am instrument -w -e live true co.featbit.sample.kotlin.test/androidx.test.runner.AndroidJUnitRunner
```

Without `-e live true`, the fixture-dependent Live test is skipped. The Local test needs no service.
The fixture exercises real SDK HTTP/WebSocket requests and event delivery with fictional data;
it does not replace acceptance against a deployed FeatBit environment. Stop it with Ctrl+C.
The ordinary sample APK contains no fixture URL, preset real key, exported test receiver, or test hook.
Only the separately installed instrumentation APK drives these checks. Captures are written to its
target app's external `files/screenshots` directory.

Set `sdk.dir` in untracked `samples/local.properties`, or configure `ANDROID_HOME`.
If dependency resolution reports a missing `co.featbit` artifact, publish the SDK with the first
command above, then build again. `-PtestRepository=<absolute Maven directory>` and
`-PsdkVersion=<version>` can select another locally published SDK.
