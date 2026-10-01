# Phase 1: implementation baseline

Date: 2026-09-30. This is the build and public-model foundation, not a functional flag client.
Phases 2–7 supply runtime behavior. No placeholder factory throws `NotImplementedError`,
returns invented success, or silently ignores configured switches. Client/source/coroutine
interfaces describe the next phases; only the value, user, configuration and update-model
builders, build identity and validation currently execute.

## Build and distribution

| Item | Selected baseline |
| --- | --- |
| Core Kotlin compiler / stdlib | 1.9.25 / 1.9.25 |
| AGP / Gradle | 8.5.2 / 8.7 |
| Build JDK / emitted bytecode | 17 / Java 11 |
| minSdk / compileSdk / Build Tools | 21 / 34 / 34.0.0 |
| Local artifact | `co.featbit:featbit-client-android:0.1.0-SNAPSHOT` |
| Shipped dependency | Coroutines core 1.8.1, required by the Flow contract |
| Unit tests | JUnit 4.13.2 |
| Transport/platform candidates | OkHttp 4.12.0, Serialization 1.6.3, Coroutines Android 1.8.1, lifecycle-process 2.8.7 |

The wrapper JAR and distribution are verified against Gradle's published SHA-256 values.
The distribution checksum remains enforced by the wrapper. The AAR contains no OpenFeature
or transport/platform implementation dependency; `candidateRuntime` resolves the Android
variants separately. Serialization's compiler plugin is deferred until codecs exist.
All selected libraries use Apache-2.0 licenses; the SDK uses MIT. JUnit uses EPL-1.0.
Transitive dependency versions must be rechecked when candidates become runtime dependencies.

[AGP 8.5 compatibility](https://developer.android.com/build/releases/agp-8-5-0-release-notes)
requires JDK 17 and Gradle 8.7 and supports API 34. This is outside Kotlin 1.9.25's
[fully supported Gradle/AGP range](https://kotlinlang.org/docs/gradle-configure-project.html).
Our consumer builds provide evidence for this exact combination, not official support or
compatibility with every dependency/toolchain. No metadata checks are bypassed.
Pinned older versions are intentional; only lint's update-notification `GradleDependency`
check is disabled. This is not a blanket lint baseline or a security audit waiver.

## Reproducible local checks

Use JDK 17 and an Android SDK containing `platforms;android-34` and `build-tools;34.0.0`.
Set `ANDROID_HOME` or an ignored `local.properties`. No machine-specific paths are committed.

```powershell
.\gradlew.bat :sdk:assembleDebug :sdk:assembleRelease :sdk:testDebugUnitTest :sdk:lintRelease :sdk:publishReleasePublicationToLocalTestRepository :sdk:resolveCandidateRuntime
python tools/check_api.py
.\gradlew.bat -p consumer-tests '-PconsumerKotlinVersion=1.9.24' :java:assembleDebug :java:assembleRelease :java:testDebugUnitTest :kotlin:assembleDebug :kotlin:assembleRelease :kotlin:testDebugUnitTest
.\gradlew.bat -p consumer-tests '-PconsumerKotlinVersion=1.9.25' :java:assembleDebug :java:assembleRelease :java:testDebugUnitTest :kotlin:assembleDebug :kotlin:assembleRelease :kotlin:testDebugUnitTest
.\gradlew.bat -p consumer-tests '-PconsumerKotlinVersion=2.2.10' :java:assembleDebug :java:assembleRelease :java:testDebugUnitTest :kotlin:assembleDebug :kotlin:assembleRelease :kotlin:testDebugUnitTest
```

On Linux use `bash gradlew` with the same arguments. The independent `consumer-tests`
build consumes the Maven-staged AAR/POM, never `project(":sdk")`. Each consumer has a minimal
launcher Activity showing model-check PASS/FAIL and a retry button; no SDK demo flows.
The launcher calls the same model checks as JUnit and keeps that usage reachable during R8.
JUnit executes model assertions against the unshrunk artifact; R8 builds establish shrinking
compatibility, not device execution. An actual post-R8 device run remains a Phase 6/7 check.
CI runs the same matrix with read-only repository permission and no publication secrets.

`sdk/api/public-api.txt` is generated from the release AAR using `javap`. Check it on every
build; intentional API edits require `python tools/check_api.py --update` and review.
The tool also rejects bytecode above Java 11 and implementation-package/dependency leaks.
Kotlin synthetic members appear in the binary inventory; builder internals are `@JvmSynthetic`
and not Java-source APIs. Runtime ABI policy: preserve existing public signatures within a
stable major release; Phase 1 snapshot contracts may change only with explicit baseline review.

## Public contract decisions

- `ClientFactory.create(applicationContext, options)` returns an `Operation<FeatBitClient>`;
  only an application context may be retained. No runtime factory is provided yet.
- `Operation.getResult()` is null while pending, otherwise one immutable `Outcome<T>`.
  `observe` returns a registration outcome, including overload. Detaching a registration or
  a coroutine wait never cancels shared work. Close reuses one operation; extra callback
  registrations may be rejected without rejecting cleanup. Callbacks default to the main
  thread, run outside state locks and survive runtime shutdown until delivered/detached.
- Readiness, Identify, mode changes, source commits, cache clear and close have distinct
  payload types. Recoverable errors use `OutcomeCode`; no exception-based recovery is required.
  A local/offline outcome never asserts remote confirmation. Source lifecycle `STARTED`
  acknowledges a start call, not data readiness.
- `FbValue` supports finite Double, Boolean, String, immutable Object/Array and explicit JSON
  Null. No arbitrary JVM objects or dependency-specific JSON types cross the API. Wrong-kind
  accessors return null; numeric/collection factories return ordinary validation errors.
  Nested values are immutable and input containers are copied. IEEE-754 precision applies;
  decimal parsing and JSON parsing are Phase 2 work and must reject nonfinite results.
- `User` requires an explicitly supplied name and a key; both reject null, empty and
  whitespace-only strings. Valid key/name values are preserved without trimming. Omitting
  `.name(...)` returns `INVALID` with `invalid_user_name`. Attribute values retain
  TEXT, NULL and OMITTED wire-value forms; an absent map entry is separate. Duplicate and
  reserved key/name properties are rejected. Automatic-prefix collisions are rejected when
  that capability is enabled. Canonical hashing is a later internal implementation concern.
- `bootstrap(flags)` is nullable only as stored configuration state: absent versus explicitly
  empty remain distinct. A null builder argument is invalid. Creation uses bootstrap first;
  subsequent identity transitions use the target full-context cache first, even if empty.
- Global/per-key change registrations return the initial immutable values atomically with
  registration. Subsequent changes may coalesce to a key union/full-invalidated marker.
  Status listeners deliver current status then updates; status Flow is not lossless history.
  Flow implementation is deferred to Phase 2 and must complete on close.
- `ConnectionInformation` separates local availability, confirmation, pause reasons, terminal
  state and candidate recovery. Nullable timestamps are UTC epoch milliseconds for the active
  context; wall time is never a scheduling clock. Candidate failure never becomes authoritative
  success or an event-delivery failure. `SdkInfo.getVersion()` comes from artifact build metadata.
- `SourceUpdate` is FullUpdate, PatchUpdate or NoChange with an opaque SDK-issued Baseline.
  No runtime schema negotiation. Trusted factories fix REMOTE/LOCAL capabilities for a session.
  Factory/start must return promptly; stop reports asynchronous cleanup. The SDK validates
  structure, authority and metadata, not authenticity of arbitrary extension code.
- `TestDataFactory.create(initialFlags)` accepts immutable `BootstrapFlag` values, with no
  caller-managed versions. `TestData.clientOptions(user)` binds the source with events/cache
  disabled. Its mutation contract is `replace`, `update`, `remove`, with COMMITTED or
  SAVED_FOR_NEXT_START outcomes. Phase 2 implements these contracts, single-client binding,
  local versions and no-network/no-analytics/no-production-cache behavior.
- Logging uses an SDK-owned Diagnostic code/field, never raw Throwable, credentials, URLs,
  user values or payloads. Level NONE disables logs; public results/loss counts remain observable.
  Runtime sanitization, exception isolation and rate limiting are implemented with diagnostics.
- Header sets are separate for synchronization and events, copied at build, case-insensitively
  unique and validated for HTTP token names/ASCII values. Protect Authorization, Host,
  Content-Length, Content-Type, Connection, Upgrade, User-Agent, X-User-Agent, Transfer-Encoding,
  Accept-Encoding and Sec-WebSocket-*. Actual request adapters must disable redirects.
- Custom factory code is never executed by the options builder. Runtime creation validates
  capabilities/options off coordination/UI work before start. Built-in synchronization settings
  conflict with a custom source; independent event endpoint validation still applies.

## Initial defaults and bounded policies

These are implementation baselines, not claims of measured optimal performance. Phase 1
implements builder validation for the public controls below; later phases implement their
effects and validate the resource assumptions. No whole-result-store capacity state machine.

| Public control | Default | Accepted range |
| --- | --- | --- |
| Startup wait | 5,000 ms | 1–300,000 ms |
| Request timeout | 10,000 ms | 1–300,000 ms |
| Close budget | 5,000 ms | 1–300,000 ms |
| Event flush | 30,000 ms | 1,000–86,400,000 ms |
| Event capacity | 10,000 | 1–100,000 events across all retained work |
| Foreground polling | 30,000 ms | 1,000–86,400,000 ms, after completion |
| Background polling | 900,000 ms | 900,000–86,400,000 ms; no execution guarantee |
| Flag background grace | 0 ms | 0–30,000 ms |

Streaming, events and cache default on. Explicit offline, background polling, fallback/recovery,
anonymous generation, automatic attributes, transition flushing and private filtering default off.
Logs default WARN. Enabling fallback implies recovery; directly configured Polling never probes.
Endpoints have no public-service default: require the applicable URLs online, not offline.
No dynamic disableEvents toggle. Validating a configuration does not make it an operational SDK.

Internal starting policies, documented rather than adding configuration knobs:

| Area | Baseline for implementation and tests |
| --- | --- |
| Streaming keepalive / inactivity | JSON ping every 18 s / reconnect after 36 s without activity |
| Sync retry | exponential 1 s to 60 s, full jitter, no lifetime attempt cap; honor valid Retry-After |
| Fallback | 30 s continuous transient failure window, reset by valid synchronization |
| Recovery | 60 s cooldown, one 15 s candidate budget; repeated failure backoff capped 5 min; reset after 60 s stability |
| Events | 50 events / 256 KiB per batch, one physical request; at most 3 attempts; 1 s then 2 s jittered retry; max age 24 h |
| Transition flush | disabled; when enabled, 2 s total from background entry |
| Event memory | 8 MiB total and at most 256 groups; overflow drops new unique work observably |
| Cache | 5 contexts / 10 MiB per namespace, 7-day age, LRU; backup-excluded private atomic files |
| Input protection | 8 MiB update, 50,000 records/update, 1 MiB/value, 1 KiB/key, 64 JSON depth; bound expansion/scratch during Phase 2 measurement |
| Operations / subscriptions | 256 pending ordinary operations and 256 registrations/client; close independent; 32 additional close registrations |
| Extensions | 2 worker threads, 64 queued invocations/client; 2 s stop wait, no replacement threads for hung calls |
| Diagnostics | repeated code at most once per 60 s, 128 code buckets / queued log messages; loss counters are not rate limited |

Concrete limits may be revised after Android measurements, with explicit documentation and tests.
Persistence will use Android `AtomicFile` under `Context.noBackupFilesDir`, guarded by the
process-local repository coordinator. Cache namespaces include environment/source identity;
context keys include the complete effective user. Namespace clear affects only that namespace,
never anonymous identity. Anonymous storage is installation/application-scoped, shared across
clients in one process, backup-excluded and removed on uninstall/app-data clearing. Reset
persists a new random ID before adoption; write failure returns STORAGE_FAILED and retains the
previous identity. Other active clients adopt it only on an explicit anonymous transition.
Candidate network/HTTP status classification must be verified against the pinned service in Phase 4;
do not silently label all 4xx transient or port LaunchDarkly protocol behavior.

Automatic attribute schema to implement: `featbit.sdk.applicationId` (package name),
`featbit.sdk.applicationVersion` (versionName), `featbit.sdk.osName` (Android),
`featbit.sdk.osVersion` (Build.VERSION.RELEASE), `featbit.sdk.deviceManufacturer`
(Build.MANUFACTURER), `featbit.sdk.deviceModel` (Build.MODEL), `featbit.sdk.version`
(artifact version). Values are strings; unavailable fields are omitted. No identifiers,
location or advertising data. Sampling occurs at creation/Identify, never evaluation/foreground.

## Protocol baseline and external validation

Source comparison: sdk-spec `3f08faa77dbf70bea208bd8ab946c2aa0b38ffad`, JS SDK
`210f4e6d4c032fd73d5bf9f16920507d645c2711`, evaluation-server
`7ecc24aac0a5ad766f6843faabf0eaeb71f1b753`. No service was launched or modified.

- JS `src/data-sync/utils.ts` encodes wall-clock connection tokens; HTTP uses raw SDK key
  Authorization and User-Agent/X-User-Agent. Android uses `Android-Client-SDK/<artifact version>`.
- Service `Streaming/Protocol/MessageTypes.cs` supports application JSON ping/data-sync;
  `Streaming/StreamingMiddleware.cs` uses 4003 for permanent authentication rejection.
- Polling path remains `api/public/sdk/client/latest-all`, event path `api/public/insight/track`,
  with deployment prefixes retained. Remote cursor is Unix-millisecond record versions.
- `Domain/Insights/MetricInsight.cs` accepts a non-null appType up to 128 characters without
  a closed platform enum: select `Android-Client-SDK`. This is source compatibility, not proof
  of a deployed server accepting the event or downstream analytics behavior.
- That service also restricts metric names to at most 128 ASCII letters/digits/underscore/hyphen,
  whereas the shared spec says nonempty. Record this as an integration discrepancy; do not
  tighten the shared Track contract or modify the service silently in Phase 1.

Phase 4 integration owner: SDK implementer maintains fixtures/tests/results; service deployment
owner supplies an isolated instance pinned to the above commit or a recorded replacement,
its dependency versions, HTTP/WS endpoints and a client-only key. Setup uses separate admin
credentials. Create a test environment/user and Boolean/Number/String/JSON flags; reset only
those fixtures between runs, not production data. Validate auth, prefix routing, empty/full/patch,
304 baseline, equal versions, archived flags and actual analytics reception. Environment access
and service startup are not prerequisites for local Phases 2–3 and are not marked passed.

## Verification record

See [verification](./verification.md) for commands, consumer/runtime versions and observed results.
CI configuration is present; a hosted CI run, real-device execution, live service integration,
full runtime behavior and Maven Central publication remain unexecuted at this stage.
