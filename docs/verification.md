# Verification record

## Maven Central 0.2.0 publication — 2026-10-06

Published `co.featbit:featbit-client-android:0.2.0`; tag `v0.2.0` points to
`7210251fbe5e3f9f452ef15e370da265f1009702`.
[Commit CI](https://github.com/featbit/featbit-android-client-sdk/actions/runs/37489448132),
[tag CI](https://github.com/featbit/featbit-android-client-sdk/actions/runs/37489866694), and
[publication](https://github.com/featbit/featbit-android-client-sdk/actions/runs/37489895116)
all succeeded. The release workflow repeated all four Java/Kotlin consumer rows before signing.
Central deployment `8b28e24c-20ad-49d1-b921-4b2fa4312cff` reached `PUBLISHED` with no errors.

Public AAR, POM, Gradle metadata, sources and Javadoc were independently downloaded from
[Maven Central](https://repo.maven.apache.org/maven2/co/featbit/featbit-client-android/0.2.0/).
All bytes match the workflow bundle, including signatures and MD5/SHA-1 sidecars.
GPG verified all five signatures against fingerprint
`615BFA905D697043815235B258BC085A1E90EE12`.
Bundle SHA-256: `2877dda436d96504773b6c2c26b98a75e5689ebe4d3e5593a7f3af7e8b124e4b`.
AAR SHA-256: `fb30670ec396c0440456e16d64caacb15ece1d546d4ae2e0073f7fdf8f89970b`.

A standalone Java/Kotlin consumer uses only Google/Maven Central repositories, a fresh
Gradle dependency cache, and no build cache or SDK source substitution. Version 0.2.0 POM,
module and AAR downloads from `repo.maven.apache.org` are recorded in the build log.
Both languages passed Debug, Release/R8, SDK-version/model unit tests (one per language),
Release lint (zero errors; 17 Java and 22 Kotlin warnings), and Spotless. All 182 tasks
executed successfully in 2m 34s. Toolchain: JDK 17, Gradle 8.7, AGP 8.5.2, Kotlin 1.9.25.

Local evidence: `build/central-0.2.0/publication-report.json`, `artifact-verification.json`,
`consumer-result.json`, `consumer-verification.log`, and signed bundle/result ZIPs in
the same directory. Local versioned acceptance is also recorded in
`build/acceptance/20261006-173158-a072baab/report.json` (155 SDK tests and all four rows).
The initial toolchain-only warmup failed because PowerShell split an unquoted version
argument; the quoted rerun succeeded. No SDK or consumer source change was required.

This final public-download verification did not repeat device or live-service execution.
The earlier same-runtime live/emulator and sample evidence below remains separate;
it is not a new physical-device or deployed database/MQ acceptance claim.

## Full live acceptance after typed-read migration — 2026-10-06

The reported run `20261006-164447-76c6b30b` failed in
`LiveSyncIntegrationTest.targetServerStreamingOutagePollingAndStreamingRecovery`: its final
assertion still read the boolean `returns-true` flag through stringVariation. Updated it to
boolVariationDetail and assert both MATCH and true. No production-code or launcher workaround.

Reran `tools/run-live-acceptance.ps1 -Serial emulator-5554` with JDK 17. Full run passed:
`build/acceptance/20261006-164655-da1da4b2/report.json`; launcher logs in
`build/live-acceptance/20261006-164649-771b3eb6/`. All 159 SDK tests passed, including four live
tests; target Domain validation reported three valid payloads and three messages. All four
Java/Kotlin compiler rows passed builds/tests/lint plus Debug and Release/R8 emulator smoke;
all six Kotlin platform checks and target-device synchronization/event checks passed.
Spotless and diff whitespace checks passed. The launcher stopped its own Fake/None test server.
This is emulator/test-service evidence, not physical-device, production DB/MQ or remote publication.

## Unified declared-type evaluation and event policy — 2026-10-06

Boolean/number/string reads and their Detail counterparts now require matching declarations;
JSON helpers require json. Generic variation still selects by declaration independently of
fallback kind, and bulk reads retain raw values. Numeric values remain finite Double values.
Type/parse failures are revalidated against the current view before returning WRONG_TYPE and
may admit eligible selected-remote-variation events. The wire format is unchanged: no error
reason or caller fallback is added. This supersedes earlier permissive/success-only descriptions.

All 155 SDK unit tests passed, including declared-type mismatches, generic fallback-kind
independence, Double rounding and type/parse failure event delivery. Spotless apply/check passed.
The first acceptance run (`20261006-163232-fbdfa803`) stopped because the report referenced the
renamed event test. After updating that evidence mapping, full acceptance passed in
`build/acceptance/20261006-163455-d0e9983d/report.json`: SDK Debug/Release, lint, Dokka, local
publication, unchanged API/Java 11 check, and Java plus Kotlin 1.9.24/1.9.25/2.2.10 consumers
(Debug, Release/R8, unit tests and lint). No device/live-server runs or remote publication.
Provider documentation is updated, but Provider runtime and Int/Long adapters remain unimplemented.

## Identity methods moved onto FeatBitClient — 2026-10-06

Removed `StrictClientCapabilities`; `identifyContext` and `identifyAnonymousContext` are now
direct abstract methods on `FeatBitClient`. `IdentityReceipt` and runtime completion semantics
are unchanged. Custom client implementations must implement these methods. API comments,
Java/Kotlin examples and the Java published-AAR contract test now use the client directly.
Earlier entries describing the optional interface are historical.

Spotless apply/check and whitespace checks passed. Full `tools/acceptance.py` evidence is in
`build/acceptance/20261006-161053-5bf84f3e/report.json`: 153 SDK tests passed, SDK Debug/Release,
lint, Dokka, isolated local publication and the reviewed 79-type API/Java 11 bytecode check passed.
Java and Kotlin 1.9.24/1.9.25/2.2.10 consumers each passed Debug, Release/R8, unit tests and lint
against the new artifact. No device/live-server tests or remote publication were performed.

## Consumer compiler matrix rerun — 2026-10-06

Ran `python tools/acceptance.py` against the current working tree after unified asynchronous
Identify. Evidence: `build/acceptance/20261006-160004-8642e964/report.json`, including source
hashes, publication hashes, command logs, dependency trees and plugin versions.
The runner built and published a fresh `0.1.0-SNAPSHOT` into its isolated local Maven repository;
all four independent consumers used that artifact.

| Consumer | Kotlin compiler | AGP | Gradle | Debug / Release R8 / unit tests / Release lint |
| --- | --- | --- | --- | --- |
| Java | N/A | 8.5.2 | 8.7 | Passed |
| Kotlin | 1.9.24 | 8.1.0 | 8.1.1 | Passed |
| Kotlin | 1.9.25 | 8.1.0 | 8.1.1 | Passed |
| Kotlin | 2.2.10 | 8.10.1 | 8.11.1 | Passed |

All 153 SDK unit tests and five consumer unit tests passed without failures or skips.
SDK Debug/Release assembly, Release lint, API/Java 11 bytecode verification and publication
artifact checks also passed. The SDK itself remains compiled with Kotlin 1.9.25; the matrix
checks consumer compiler compatibility. No emulator, physical-device, live-server or remote
publication checks were run. This supersedes the earlier same-day matrix-not-rerun notes below.

## Unified asynchronous Identify — 2026-10-06

Named `identify` now shares worker preparation/adoption and readiness completion with
`identifyAnonymous`. Returning the Operation does not guarantee adoption. Queueing, preparation
and readiness share one deadline; a timeout before adoption prevents a later switch, while a
timeout after adoption does not roll back the user or stop synchronization.

Windows/JDK 17 verification passed:

- 153 SDK unit tests, including four new named-Identify tests for deferred adoption, readiness,
  pre-adoption timeout, a shared queue/readiness deadline, supersession and close. Existing
  source/cache tests now explicitly advance the adoption worker before testing late responses.
- Spotless apply/check, Release AAR, Release lint, Dokka and local Maven publication.
- Unchanged public API baseline and Java 11 bytecode check (80 public JVM types).
- Independent Java and Kotlin 1.9.25 consumer tests (three total) and both Release/R8 builds.

No device or live-server runs, remote publication or additional Kotlin compiler matrix were
performed for this change.

## Identity adoption operations — 2026-10-06

Implemented the optional StrictClientCapabilities identity methods and IdentityReceipt;
existing FeatBitClient method signatures remain unchanged. Both new methods prepare on the
worker and complete at adoption, independently of remote readiness. API KDoc and
[integration examples](./identity-adoption.md) explain the difference from identify/identifyAnonymous.

Windows/JDK 17 verification passed:

- 149 SDK unit tests, including 10 new adoption tests: readiness separation, delayed callbacks,
  queue/deadline expiry, anonymous revision-lock deadline, old-source rejection, supersession
  by both old and new APIs, storage failure, close and detached observation.
- Release AAR assembly, Release lint, Dokka generation and local Maven publication.
- Spotless apply/check and git diff whitespace checks.
- Reviewed public API baseline update; Java 11 bytecode and 80 public JVM types verified.
- Independent local-Maven Java and Kotlin 1.9.25 consumers: three unit tests total and both
  Release/R8 builds passed. Java checks the additive capability and unchanged FeatBitClient.

No emulator/physical-device or live-server tests were run for this change. No remote publication
or Kotlin 2.2.10 matrix rerun was performed.

## Automatic Central publication tooling — 2026-10-05

The release workflow now uploads the signed version bundle with AUTOMATIC publishing,
waits for PUBLISHED, and retains a deployment report on failure or success. Tests use
controlled responses rather than real Central credentials. They cover multipart/auth
construction, no upload retry after an ambiguous error, state transitions, validation
failure, transient status retries, authorization rejection, timeout, deployment-ID
matching, credential redaction, report retention and redirect rejection.

Windows: 30 tooling tests passed, five POSIX checks skipped. WSL Ubuntu 22.04: all
35 tooling tests passed. Workflow YAML parsing and `git diff --check` passed.
No real Central upload or publication was triggered by these checks. The new credential-
backed automatic path remains unverified until a tagged workflow is dispatched.

## POSIX launcher port preflight — 2026-10-05

Hosted Kotlin 1.9.25 verification failed in the shared Python launcher test after
the controlled server had stopped: port 5189 was reported in use. A new Linux
regression reproduced the same error on an ephemeral port by closing the server
side of an accepted connection first, leaving TCP TIME_WAIT state without a listener.

The POSIX preflight now uses SO_REUSEADDR and verifies both bind and listen, matching
server restart semantics while still rejecting a live listener. It does not enable
SO_REUSEPORT; Windows retains its existing non-reuse behavior. No SDK code changed.

Validation: all 25 tooling tests passed under WSL Ubuntu 22.04, including real occupied
port rejection and the TIME_WAIT regression. The Bash success/failure/cleanup test
also passed three consecutive reruns. Windows passed 20 tests and skipped five
POSIX-only tests. This is tooling validation, not a new SDK device acceptance run.

## Release readiness — 2026-10-04

### Automated evidence

- Version: `0.1.0`; full local run: `build/acceptance/20261004-122019-37bff871/`.
  `report.json` ends with `requested checks passed`; every recorded command exited 0.
- Java, Kotlin 1.9.24, 1.9.25 and 2.2.10 independent consumers passed build and installed
  Debug/R8 runtime checks. All six Kotlin platform checks passed, including the previously
  failing Release scenario. Live target-device checks, SDK live tests and exported-payload
  Domain validation passed in this run.
- Launcher evidence: `build/live-acceptance/20261004-122008-bba4b2de/`.
- The run recorded HEAD `60191f8` plus six pending acceptance-path/documentation changes
  subsequently committed as `8b739c2`. Recorded source SHA-256 values matched that committed
  workspace. This was not a clean-HEAD run; the content comparison connects the evidence
  to the committed files.
- [Hosted CI for 8b739c2](https://github.com/featbit/featbit-android-client-sdk/actions/runs/37197217151)
  passed all four consumer rows, including Spotless and acceptance-tool tests.
- Earlier failed run `build/phase7/20261004-120601-d869f7a7/` remains a failure;
  it is superseded by the complete successful run above, not rewritten as PASS.

### Maintainer-confirmed manual validation

On 2026-10-04 the maintainer explicitly confirmed physical-device validation passed,
then separately confirmed real FeatBit deployment event persistence, EndUser updates
and experiment attribution. This closes the agreed real-environment validation step.
These are user-reported results, not tests independently observed or executed by the agent.
Device/OS inventory, exact tested artifact hashes and per-scenario logs were not supplied;
do not infer exhaustive device or fault-injection coverage.

### Publication status

Remaining work: credential-backed signing, Central upload/validation/publication and
independent Java/Kotlin fresh-download verification. No Central publication or signed
workflow success is claimed. Release notes and future Central installation instructions
are prepared separately; the README retains local installation until publication succeeds.
This documentation-only update changes no SDK or test fixtures and does not require a
new device-matrix run. Hosted CI for its final commit must still pass before tagging.

## Emulator rendering ANR and focused platform retry — 2026-10-04

Run `build/phase7/20261004-120601-d869f7a7/` failed at the Kotlin 1.9.25
Release platform check while awaiting a foreground snapshot in the final TestData
connectivity scenario. Device Logcat shows the SDK had already reported
`background=false`, `status=READY`, `value=local`. The subsequent probe broadcast
timed out with an ANR for PID 17989. Its main-thread trace waits in Android
`HardwareRenderer.setStopped` / `RenderProxy::setStopped`; emulator graphics,
System UI and launcher rendering also show heavy kernel CPU usage. This evidence
points to an emulator rendering stall rather than an SDK foreground-state failure.

Saved original Logcat and DropBox ANR traces under
`build/acceptance/platform-retry-20261004-121757/`. Reran the unchanged
`tools/platform_device_checks.py` against the installed consumer on `emulator-5554`,
without rebuilding or extending assertion deadlines. All platform scenarios passed,
ending with `PHASE6_DEVICE_PASS`; see `platform.log` in that directory. Cleanup
succeeded and `adb reverse --list` was empty afterward. This focused retry does not
convert the original failed report into a full-matrix pass; the full release-candidate
matrix still needs a successful run.

## Repository code formatting — 2026-10-04

Added root Spotless 7.0.2 with ktfmt 0.54 Kotlin style and google-java-format 1.24.0
AOSP style. Targets cover all 75 tracked Kotlin, Java and Gradle Kotlin script files
across the SDK, tests, samples and consumer fixtures. Source formatting was applied;
editor settings, LF attributes, README commands and both CI formatting checks were added.

Validated locally on Windows with JDK 17 and Gradle 8.7:

- `spotlessApply` followed by `spotlessCheck` passed.
- SDK Release AAR, 139 Debug unit tests, Release lint and local Maven staging passed.
- `python tools/check_api.py` passed: 78 public types match the existing baseline,
  implementation dependencies remain hidden, and bytecode stays within Java 11.
- Python tooling: 20 tests passed; three POSIX-only tests were skipped on Windows.
- Kotlin sample: Debug APK, R8 Release APK, Debug instrumentation APK, three Debug
  unit tests, and Debug/Release lint passed.
- Independent Java and Kotlin 1.9.25 consumers: R8 Release builds, one Debug unit
  test per consumer, and Release lint passed against the newly staged local SDK.
- `git diff --check` passed.

This is formatting validation, not a new release-acceptance run. No device execution,
live-service integration, full Kotlin compiler matrix or hosted CI run is claimed.

## Bash and Python live-acceptance launcher — 2026-10-03

Added `tools/run-live-acceptance.sh` and `tools/run_live_acceptance.py` for Linux/macOS.
The Bash entry forwards arguments and exit status; Python performs prerequisite
checks, owns the Fake/None process, admits readiness from its own plain/structured
startup logs plus a TCP connection, invokes the existing full live matrix, captures
logs and cleans up owned processes. The Windows PowerShell entry is unchanged.

Validation under WSL Ubuntu 22.04: Bash syntax/help passed and all 23 Python tool
tests passed. Controlled end-to-end Bash runs exercised paths with spaces, complete
preflight, service startup with the target's structured log format, acceptance
stdout/stderr, success and nonzero exit, environment isolation and service cleanup.
Other tests cover occupied-port rejection without stopping the listener, readiness
failure, handled interruption cleanup and real POSIX process termination. Generated
test fixtures/logs are retained under `build/launcher-tests/`.
On Windows, 20 tests passed and the three POSIX integration tests were skipped.

A real WSL `--check-only` invocation correctly failed with `Executable not found: java`.
No Linux Android/JDK/.NET environment was installed for this change. Full Linux/macOS
live/device acceptance and native macOS execution remain unverified; controlled
launcher tests do not replace those release checks. No GitHub workflow was added.

Current tool names: `acceptance.py`, `platform_device_checks.py` and
`live_device_checks.py` replace `phase7_acceptance.py`, `phase6_device.py` and
`phase7_live_device.py`, respectively. Historical commands below retain the names
used by those runs. Evidence paths and report formats are unchanged; current commands
are in the [release guide](./release.md).

## Emulator network transition timing — 2026-10-03

Run `build/phase7/20261003-123547-f32916e2/` failed the Kotlin 2.2.10 Release
platform check while waiting for `networkPaused=false` after enabling Wi-Fi.
Captured `build/network-logcat.log` shows Wi-Fi enabled at 12:48:52.001, but the
Wi-Fi network only connected at 12:49:16.035, after the script's 12-second deadline.
Cleanup enabled cellular at 12:49:04, and the SDK cleared its network pause then.
This differs from the earlier ADB system-stall failure recorded below.

The test-only consumer probe now reports Internet-capable OS networks independently
of SDK pause state. The platform script verifies Wi-Fi/cellular handover, total
loss, Wi-Fi recovery and disconnected TestData prerequisites with a 45-second
Android transition budget, followed by the unchanged 12-second SDK state deadline.
It prints `NETWORK_READY` snapshots and includes the last snapshot on transition
timeout. The parent process budget is 480 seconds; timeouts remain failures.
SDK implementation and public APIs are unchanged. Original failed evidence is preserved.

Validation: all 11 Python tool tests passed. A fresh
`python -B tools/acceptance.py --rows kotlin-2.2.10 --serial emulator-5554` run
completed with `requested checks passed` in `build/phase7/20261003-125220-ec20eede/`:
139 SDK tests, consumer Debug/Release builds, JUnit/lint, both installed runtime
smokes and both complete platform checks passed. Both platform logs end with
`PHASE6_DEVICE_PASS`, and no reverse mapping remained. This focused run did not
repeat the other compiler rows or live target-server integration.

## Emulator system stall and platform retry — 2026-10-03

Run `build/phase7/20261003-122827-601255ce/` stopped at the Kotlin 1.9.24
Release platform check: `wm dismiss-keyguard` exceeded its 20-second ADB budget,
and cleanup's `am force-stop` also timed out. Captured device Logcat
(`build/platform-failure-logcat.log`) includes system_server pre-watchdog output,
a 38-second system UI dispatch and a Google Search process ANR at the same time.
This was a device-command timeout, not a failing SDK behavior assertion.

After the emulator recovered, force-stop cleanup and dismiss-keyguard both succeeded.
The same standalone platform script was rerun against the installed consumer without
code changes or relaxed timeouts. All scenarios and cleanup passed, ending with
`PHASE6_DEVICE_PASS`; log: `build/platform-retry-20261003.log`. No reverse mapping
remained. The original failed matrix report is preserved and is not converted into
a full-matrix pass by this focused retry.

## Windows live-acceptance launcher — 2026-10-03

`tools/run-live-acceptance.ps1` supports Windows PowerShell 5.1 and PowerShell 7. It checks tools and
the running emulator, builds/starts a dedicated Fake/None server, runs all four
consumer rows with live and emulator checks, and stops its own service in `finally`.
It preserves process environment variables (including absent versus empty values)
and the caller's working directory. It rejects an occupied port 5189.

Validation:

- After the initial PowerShell 7-only requirement was removed, preflight passed in
  both Windows PowerShell 5.1.19041.6456 and PowerShell 7. Native stderr capture and
  Python preflight quoting were adjusted for 5.1. Two controlled acceptance probes
  under 5.1 wrote stdout/stderr and exited with 0/7 after real server builds/startups.
  Success/failure handling, service cleanup, environment and working-directory
  restoration passed in both cases. Log: `build/launcher-ps51-validation.log`.
  These launcher probes do not represent another full SDK matrix run.
- `-CheckOnly` passed on the local Windows environment without starting a service.
- A second preflight while the test service was running rejected the occupied port
  and left the existing service process unchanged.
- A full launcher run on SDK commit `3891b25a0e3a5185c57be1c755ac0492f2589874`
  plus launcher/documentation changes completed successfully. Launcher logs:
  `build/live-acceptance/20261003-115730-3c943d7e/`; matrix evidence:
  `build/phase7/20261003-115741-3388f718/`. There were **143 passing SDK tests**,
  four passing consumer build/Debug/R8 rows, six passing platform runs and
  **16 passing installed-APK synchronization/event checks**. The target report is
  `target-device-runs/20261003-121107-be43e53d/report.json`. The wrapper stopped
  its service after success, and temporary ADB reverse mappings were removed.
- A controlled substitute for the acceptance command exited with code 7 after a
  real server startup. The wrapper propagated failure and stopped the server.
  This exposed a null-to-empty environment restoration issue; after fixing that
  helper, the failure probe confirmed exact environment and directory restoration.
  Final probe logs: `build/live-acceptance/20261003-121421-5480fae6/`.
  This deliberate failure probe is separate from the successful matrix evidence.

The full matrix preceded only the environment-restoration helper correction; no
SDK/runtime behavior changed. Physical-device, database/MQ and formal release
limitations remain as listed in the conformance gates.

## Consumer screen labels — 2026-10-03

Consumer screens now describe local runtime, synchronization, events and platform
lifecycle checks by purpose instead of development phase. All fixed screen text,
including titles, buttons, progress, results and scope descriptions, is English.
Scope text follows the selected check. Existing Activity extras, fixture data and Logcat PASS/FAIL markers
remain unchanged for the acceptance scripts.

Both interactive consumer Debug APKs were rebuilt against the Maven AAR from
`build/phase7/20261003-113008-f65e2fdc/repository` and installed on `emulator-5554`.
Java/Kotlin local runtime, synchronization and event checks all passed (six launches);
network checks used the Fake/None service. UI hierarchy captures verified English
PASS text without phase labels or Chinese characters, while Logcat retained the
original markers. The platform Activity's English text was also verified.
Build log: `build/consumer-ui-english-build.log`; UI captures:
`build/consumer-ui-english/`. The service and temporary reverse mapping were cleaned up.
This follow-up checked Debug screen text; the complete matrix below predates this
UI-only change and was not rerun for it.

## Combined live and emulator matrix — 2026-10-03

Command: `python -B tools/acceptance.py --live --serial emulator-5554`.
SDK baseline: clean commit `6b2af8728b12be8eb75e1c9e9330b2a10cc67638`;
artifact version `0.1.0-SNAPSHOT`. Evidence directory:
`build/phase7/20261003-113008-f65e2fdc/`.
Its `report.json` finishes with `requested checks passed`.

- **143 SDK tests passed**, zero failures/errors/skips: 139 non-live checks plus
  four explicitly executed target-service tests. SDK AAR builds, Release lint,
  publication, the 78-type API baseline/Java 11 check and conformance report passed.
- Java and Kotlin 1.9.24/1.9.25/2.2.10 independent consumers passed Debug/R8 builds,
  JUnit and lint, followed by installed Debug and test-signed R8 runtime checks
  on API 34 emulator `emulator-5554`. Gradle reused eligible build/test cache outputs;
  explicit live tests and device launches ran for this invocation.
- All six Kotlin Debug/R8 platform runs emitted `PHASE6_DEVICE_PASS`, with no
  cleanup failures. The runs cover controlled background/rotation/network changes,
  forced Doze/resumption, event transition deadlines and local TestData behavior.
- Target Domain verification reported three valid payloads and three target messages.
  The installed-APK target report at
  `target-device-runs/20261003-114235-d59d5192/report.json` reports **16 checks passed**:
  four consumer rows × Debug/R8 × synchronization/events. Local runtime checks
  are recorded separately by the main runner.

The evaluation server at commit `7ecc24aac0a5ad766f6843faabf0eaeb71f1b753` was
rebuilt with .NET 10 into `build/acceptance-server` and started with DbProvider=Fake,
MqProvider=None, CacheProvider=None on port 5189. Its build retained existing
Microsoft.OpenApi/SharpCompress NuGet advisory warnings. The test service was stopped
after acceptance; temporary ADB reverse mappings were removed. Emulator setting
cleanup completed. No SDK implementation or adjacent repository source was changed.

This is current-commit emulator and Fake/None protocol evidence, not physical-device
natural sleep, deployed database/MQ persistence, trusted release signing or Central
publication/download acceptance. Those release gates remain open.

## Hosted library and consumer CI — 2026-10-03

[Library and consumer contracts run 37112255715](https://github.com/featbit/featbit-android-client-sdk/actions/runs/37112255715)
completed successfully for commit `14fb4fd612a5d4bc63b6d209572b1a05d7cdb620`.
The GitHub Actions API confirmed success for all four jobs, their acceptance steps
and artifact uploads:

| Consumer row | Job result | Uploaded evidence |
| --- | --- | --- |
| Java | Success | `reports-java` |
| Kotlin 1.9.24 | Success | `reports-kotlin-1.9.24` |
| Kotlin 1.9.25 | Success | `reports-kotlin-1.9.25` |
| Kotlin 2.2.10 | Success | `reports-kotlin-2.2.10` |

Each job ran the Python script regressions and
`python3 tools/phase7_acceptance.py --rows <consumer>` on Ubuntu/JDK 17 with the
default artifact version `0.1.0-SNAPSHOT`. The configured acceptance covers SDK
Debug/Release AAR builds, non-live JVM tests, Release lint, local Maven publication,
API/bytecode checks, representative conformance checks, and independent consumer
Debug/R8 builds, JVM tests, lint and dependency/plugin reports.

The four artifacts were present and unexpired when checked; the API reports expiry
at `2027-01-01T09:12:19Z`. Raw reports remain attached to this run rather than committed
to Git. Job status and artifact metadata were verified; the ZIP contents were not
downloaded or independently audited in this documentation update.

This establishes hosted build/consumer CI for the stated commit. No `--live` or
`--serial` was supplied: it does not add device, live-service, database/MQ,
credential-backed signing or Central publication/download evidence. Historical
results below retain their original scope.

## Linux timeout/retry fixture address — 2026-10-03

The uploaded GitHub Actions SDK log reported two failures among 139 tests:
`EventTransportTest.realHungRequestTimeoutCancelsAndAllowsLaterRetry` and
`SyncTransportTest.realPollingTimeoutRecoversAndCloseRetainsData`.
Both failures were reproduced with the existing compiled JVM tests in a Linux
Temurin 17 container, while the seven transport tests passed on Windows.
Temporary transport diagnostics showed that after the initial IPv4 request was
canceled, later attempts used `localhost/::1` and failed with connection refused;
the MockWebServer fixture was listening on IPv4. Those diagnostics were removed.

The shared network options and event fixture now resolve the default server host
to one numeric address before constructing request URLs. Timeout, cancellation,
retry and recovery assertions remain enabled with their original time budgets;
production transport and retry policy are unchanged. Event request assertions now
distinguish a missing initial request from a missing retry.

- Windows Gradle: **139 non-live tests passed**, zero failures/errors/skips;
  log `build/ci-transport-fixed.log`.
- Linux Temurin 17 JUnitCore: the same full set of **139 tests passed** using
  the Gradle test runtime classpath; log `build/ci-linux-fixed.log`.
- `git diff --check` passed. This Linux JVM run is not a full Linux Android build,
  consumer matrix, hosted Actions rerun, device test or live-service acceptance.

## Platform fixture reverse-mapping ownership — 2026-10-02

`phase6_device.py` now preserves an existing matching `tcp:5196` mapping, rejects a
conflicting mapping before changing the device, and uses `--no-rebind` when creating a
missing mapping. If creation fails, it does not run device-setting cleanup for changes
it never started. Cleanup removes only its own still-matching mapping, leaves a replacement
unchanged, and reports inspection/removal failures. The HTTP fixture socket is also closed.

Six regressions execute the real script's setup/finally paths with simulated ADB and HTTP
server resources: new mapping cleanup after fixture failure, existing-map reuse, conflict,
concurrent bind, replacement before cleanup, and removal failure. Together with five live
evidence-admission regressions, all **11 Python tests passed** using
`python -B -m unittest discover -s tools -p 'test_*.py'`. Both CI workflows include them.
No emulator settings were changed; the full platform fixture and hosted CI were not rerun.

## Shared endpoint scheme handling — 2026-10-02

Builder validation, online transitions, synchronization/event request construction and
cache namespace matching now share the internal `Endpoint` parser. Protocol names are
case-insensitive and normalized using `Locale.ROOT`; paths retain casing and escaping.
Streaming still requires ws/wss, and Polling/events require http/https. Host requirements
and rejection of user-info/query/fragment remain unchanged. Offline creation still defers
enabled-path validation until going online. Public API signatures are unchanged.

Four EndpointTest regressions cover mixed/upper/lower schemes across all request paths,
equal cache namespaces for scheme-case variants, distinct path-case namespaces, invalid
endpoints, and actual offline-to-online admission with a controlled transport.
All **139 non-live SDK tests** passed, with zero failures/errors/skips; Release lint,
release AAR/local Maven publication, the 78-type API baseline and Java 11 bytecode checks
passed. Logs: `build/fix-endpoint-final.log`. The live fixture's nullable output-path
compiler warning was also removed with an explicit required-property check.

The default independent Java/Kotlin consumers (Kotlin 1.9.25, AGP 8.5.2, Gradle 8.7)
were checked against the newly staged AAR; see `build/fix-endpoint-consumers.log`.
This change does not establish new live-service, device, full compiler-matrix or hosted-CI
acceptance. Earlier Phase 7 and live-evidence results below retain their original scopes.

## Live acceptance evidence freshness — 2026-10-02

Source baseline: `f5932e8` plus this fix. Live test tasks now disable Gradle up-to-date
and build-cache reuse. The acceptance runner supplies a unique payload output in its run
directory and requires all four live tests plus that payload before Domain validation.
Direct Gradle live runs clear their single configured payload output before executing tests.

- Service absent, with a historical payload still present at the old default path:
  `python -B tools/phase7_acceptance.py --live --rows java --version 0.1.0-phase7`
  failed as expected. All 139 SDK tests executed; the four live checks failed, the run's
  payload was absent, and Domain/consumer validation did not run. Evidence:
  `build/phase7/20261002-191152-de47bf34/`.
- With the existing Phase 7 Fake/None evaluation-server binary running on port 5189,
  the same runner command passed 139 SDK tests, Domain validation (three payloads/messages),
  API/lint/publication checks and the Java Debug/R8/JUnit/lint row. Its fresh payload and
  copied XML are in `build/phase7/20261002-191343-e8f27345/`. No device checks were requested.
- Direct `:sdk:testDebugUnitTest -PliveIntegration -PsdkVersion=0.1.0-phase7
  --tests '*Live*IntegrationTest' --info` ran twice successfully with identical arguments.
  Both executions ran the four live tests; logs explicitly show caching disabled and
  up-to-date reuse disabled. Exported payload hashes differed. Logs/payload copies:
  `build/fix-live-direct-1.*` and `build/fix-live-direct-2.*`.
- After stopping that service, the same direct command executed again and failed all four
  live tests. The old default payload was removed and not recreated. Evidence:
  `build/fix-live-direct-stopped.log`, `build/fix-live-stopped-sync.xml` and
  `build/fix-live-stopped-events.xml`.
- Five Python evidence-admission regressions passed, covering complete evidence, missing or
  empty current payload, missing suite/method, and skipped/failed/error cases. Both CI
  workflows now run them. Normal SDK tests (135, no failures/errors/skips) and Release lint
  also passed after the live checks; log: `build/fix-live-normal.log`.

The test service was stopped. No physical-device, hosted-CI, signing, Central publication or
database/MQ acceptance is established by this fix. The earlier review's cached live "pass"
is not current-service evidence; these new negative and positive runs replace that inference.

## Phase 7 — 2026-10-02

Implementation, commands, source baselines and limitations: [phase-7.md](./phase-7.md).
Final artifact evidence: `build/phase7/20261002-164239-3eb84010/`, SDK version
`0.1.0-phase7`, source HEAD `6eff0ddf7e04eccba3863a2404a2d34edebf2e04` plus Phase 7 changes.

- **139 SDK tests passed**, zero failures/errors/skips: 135 unit/controlled-network checks
  plus four explicit target-server checks. Includes three new attribute/diagnostics tests.
- Debug/release AAR, Release lint, API inventory (**78 public types**) and Java 11 bytecode passed.
- Local Maven publication includes AAR, sources, Dokka documentation JAR, POM and Gradle
  module metadata. Runtime version, POM and module metadata agree on `0.1.0-phase7`.
- Independent Java, Kotlin 1.9.24, 1.9.25 and 2.2.10 roots passed Debug/R8 Release, JUnit
  and full Release lint. Toolchain versions and resolved graphs are in the phase report.
- API-34 x86_64 emulator: all four Debug and all four actual R8 Release consumer runtime
  launches passed. Six Kotlin launches passed automatic-attribute checks and six platform
  runs emitted PHASE6_DEVICE_PASS. R8 APKs were locally test-signed for installation only.
- Actual SDK reads/state/version were exercised under StrictMode disk/network penalties;
  state-container immutability and artifact-version equality were verified through the actual AAR.
- Rebuilt target service with Fake/None providers; Domain verifier accepted three exported
  Android payloads and their three converted messages. HTTP success alone is not this evidence.
- After the first-draw launcher fix, all four consumers were rebuilt with JUnit/full lint
  and test-signed again. Final `target-device-runs/20261002-171633-1ec55ffd/report.json`
  contains **24 passing device checks**: four rows × Debug/R8 × local/sync/events.
  Earlier failed target runs remain preserved separately; see `final-summary.json`.
- Python syntax, local documentation targets, publication metadata and `git diff --check` passed.

Failed attempts are retained separately: initial Wrapper drift used Gradle 9.3 instead of
the intended distribution; a background Identify exceeded the fixture's one-second success
wait; target-device launch hit a long initial renderer stall. Findings and fixture fixes are
documented in phase-7.md. The original failed runs are not counted as passing evidence.

Not established: physical devices/natural deep sleep, API 21/newer-OS, real VPN and
missing-permission device paths, vendor restrictions, interactive multi-window, backup/restore,
every AtomicFile kill boundary, deployed database/MQ attribution, exhaustive diagnostics/resource
leak checks, hosted CI, credential-backed signing or Central download verification. No commit,
push or remote publication. Adjacent source repositories were not edited.

## Phase 6 fixture opt-in and warning cleanup — 2026-10-02

The Kotlin consumer now registers LifecycleProbeReceiver/Activity only when built with
`-Pphase6Probe=true`. Ordinary builds omit the probe manifest overlay; the switch applies
to both Debug and non-debuggable R8 Release and does not change the SDK AAR.

- Built both variants with the switch and inspected the packaged APK manifests using
  `aapt2 dump xmltree`: both probe components are present.
- Built both variants again without the switch and inspected the packaged APK manifests:
  neither probe component is registered; SmokeActivity remains. Default APKs are the final
  local build outputs. Both Release builds completed R8.
- Renamed the overridden SyncListener.failed parameter to `status`, matching the interface,
  and renamed the captured test variable to avoid shadowing. All **4 SyncTransportTest**
  tests passed, without compiler warnings in that run.
- Device scenarios were not rerun for these fixture-registration/test-naming changes.
  The Phase 6 results below remain the earlier device evidence; all unverified acceptance
  items below remain open. No new multi-window or physical-device claim is made.

Build logs: `build/phase6-probe-enabled.log`, `build/phase6-probe-default.log`,
`build/phase6-warning-cleanup.log`. Updated device build commands are in [Phase 6](./phase-6.md).

## Phase 6 — 2026-10-02

Implementation and commands: [phase-6.md](./phase-6.md). Source starts at SDK HEAD
`c7533b9826d92784b83b2391a62f24345758d8ac` with this uncommitted Phase 6 change.
JDK 17.0.11, Gradle 8.7, AGP 8.5.2, Kotlin 1.9.25, Android compile/build-tools 34.

- **132 SDK unit tests passed**, no failures/errors/skips. Twelve new platform/state tests
  cover initial snapshot and close fencing, multiple networks, background creation/Identify,
  grace expiry and queued dispatch, inactivity after sleep, candidate interruption and
  foreground recovery ordering, Doze permission, event retention/group boundaries.
- Debug/release AAR, release lint and local Maven staging passed. API inventory and Java 11
  bytecode checks passed with unchanged **78 public types**. Platform types remain internal.
- Independent Java and Kotlin 1.9.25 consumers: Debug/Release builds, unit tests and R8 passed.
  Merged release manifest includes ACCESS_NETWORK_STATE and ProcessLifecycleInitializer.
- API 34 x86_64 emulator `emulator-5554` / `sdk_gphone64_x86_64`: the complete installed-AAR
  platform runner passed against **both Debug and actual R8 Release APKs**, each emitting
  `PHASE6_DEVICE_PASS`. The unsigned release consumer was signed locally with the standard
  debug test key for installation; no distribution artifact was published.
- Device checks: background creation without transient requests; readiness timeout;
  foreground polling; real Activity rotation/recreation retaining the client; Wi-Fi to
  cellular handover, total network loss and recovery; background event suspension and
  deduplication/group boundaries; offline and closed-state recovery fencing; process
  recreation; background polling/Identify; disableEvents; forced Doze with an Identify
  timeout and resumption; transition cutoff/late response/retained retry; network-independent
  TestData. HTTP uses an isolated loopback fixture, not the evaluation-server deployment.
- Actual **Java and Kotlin R8** launcher smoke checks also passed. Kotlin reported
  `PHASE3_PASS previousProcessData=true` for cache/anonymous restart behavior, alongside
  TestData/Custom/JSON/suspend/Flow/Identify/offline/Close checks. This does not establish
  backup restoration or process termination during every atomic-write stage.

Local logs: `build/phase6-sdk.log`, `build/phase6-consumers.log`,
`build/phase6-device.log`, `build/phase6-device-r8.log`,
`build/phase6-r8-consumer-smoke.log`; JUnit XML is under `sdk/build/test-results/`.
The device runner restores network/rotation/screen-timeout/battery settings and its reverse
mapping. Final inspection found no reverse mapping and `mForceIdle=false`, `mState=ACTIVE`.

Earlier device attempts exposed fixture field-extraction/timing assumptions that were
corrected before the complete runs above. A SCREEN_OFF test also hit a **system_server ANR**
in `AlarmManagerService$InteractiveStateReceiver`; the emulator was rebooted. Final Doze
checks use explicit `force-idle`, verify `mState=IDLE`, and exercise the SDK while CPU
execution is available. They do **not** prove natural screen-off/deep-sleep behavior.

**Not executed / not established:** physical devices, API 21/newer-OS device matrix, real
VPN, missing-permission device path, vendor restrictions, natural deep sleep, full Kotlin
compiler matrix, hosted CI, target service/database/MQ revalidation, and atomic-write kill
coverage. The multi-window launch request remained fullscreen on this emulator; it is
**unverified**, not passed. Process STARTED policy is implemented, but interactive split
screen acceptance must be completed on a capable configuration. Phase 7 remains outstanding.

## Event field-format checks removed — 2026-10-02

Removed EventProtocol field-format and individual length checks for event names, flag keys,
variation IDs, users and custom attributes. IDs are opaque, case-sensitive strings. Track
still requires a non-empty name and finite numeric value; unique variation mapping, privacy
filtering, public-model validity and event/batch resource budgets remain unchanged.

`:sdk:testDebugUnitTest :sdk:lintRelease` passed: **120 tests**, zero failures/errors/skips.
Regression coverage verifies Unicode/spaces/punctuation, long names/keys/attribute values,
non-UUID IDs and case-distinct IDs are preserved in the serialized event payload. Existing
missing/duplicate/ambiguous mapping checks still pass. The target-service verifier no longer
requires Android to mirror the service's Track-name validation. Live service, consumer and
device checks were not repeated for this change; the Phase 5 results below are historical.

## Phase 5 — 2026-10-02

Implementation, behavior and reproducible commands: [phase-5.md](./phase-5.md).

- Final SDK run: **123 tests passed**, zero failures/errors/skips. Includes the existing
  86 normal tests, 30 event state/queue/operation tests, 3 real HTTP event tests, and
  4 explicit target-server tests (3 synchronization, 1 events).
- Coverage includes filtered call-time snapshots, metadata eligibility, local/remote provenance,
  group/byte/count capacity, overlapping Flush/loss accounting, callback reentry, shared operation
  capacity, dispatch revocation/late handles, offline retention, retry budgets, terminal independence,
  physical request slots, transition deadlines, suspended-timer late responses and finite Close.
- Target service at `7ecc24aac0a5ad766f6843faabf0eaeb71f1b753`, built locally with .NET 10,
  uses Fake DB / None MQ / None cache. Real polling/evaluation/Track/Flush/final Close passed.
  The actual Android request export also passed the target Domain IsValid/message conversion
  verifier: **PHASE5_CONTRACT_PASS, 3 payloads → 3 messages** (one FlagValue, two CustomEvent).
  This specifically checks the field-free variation contract, Android appType and filtered user,
  beyond the controller's potentially misleading HTTP 200 for invalid input.
- Debug/release AARs, release lint and local Maven staging passed. API inventory and Java 11
  bytecode passed: unchanged **78 public types**; event/transport types remain internal.
- Final staged AAR: independent Java and Kotlin 1.9.25 debug/JUnit and release/R8 builds passed.
  Separate final logs are `build/phase-5-consumer-final-debug.log` and
  `build/phase-5-consumer-final-release.log`.
- API 34 emulator `emulator-5554`: both final debug consumers emitted **PHASE5_PASS**
  (Java 12:33:36 and Kotlin 12:34:00, Europe/Berlin). Actual AAR factory, Java callbacks,
  Kotlin suspend adapters, Track deduplication, evaluation, private options, Flush, offline
  retention/resumption and final Close ran against the local target service. Wire filtering
  content is independently verified by the exported request/Domain check above.

SDK command: `gradlew.bat :sdk:testDebugUnitTest -PliveIntegration :sdk:assembleDebug
:sdk:assembleRelease :sdk:lintRelease :sdk:publishReleasePublicationToLocalTestRepository`.
Then `python tools/check_api.py` and `.dotnet/dotnet run --project
tools/event-contract/EventContract.csproj -- sdk/build/phase-5-target-payload.json`.
Logs are retained in ignored `build/phase-5-*.log`.

Initial implementation checks caught a Long/Int Kotlin comparison, missing fixture user name,
missing SourceStatus argument and a test hook installed after dispatch. These were corrected
before the final passing run. Final review additionally covered callbacks executing before
delayed timer ticks and bounded request-construction failures. Target-server compilation has
the pre-existing Microsoft.OpenApi/SharpCompress package warnings described in Phase 4.

Not verified: production gateways or deployed databases/MQ, physical devices, API 21 device
execution, post-R8 device execution, hosted CI, or real Android lifecycle/Doze/network observers.
The Kotlin 1.9.24/2.2.10 matrix was not repeated. There is no persistent event queue. Adjacent
source repositories were not modified. No commit, push or remote publication was performed.

## Phase 4 — 2026-10-02

Implementation and reproducible server/consumer commands: [phase-4.md](./phase-4.md).

- **86 unit/controlled-network tests passed**, zero failures/errors/skips: 12 model,
  32 local runtime, 18 persistence, 12 online state-machine, 3 online/cache race,
  5 protocol and 4 real HTTP/WebSocket MockWebServer tests.
- **3 opt-in target-server tests passed**: Streaming, Polling and initial Streaming
  outage → target HTTP Polling → target Streaming recovery. The service uses its
  checked-in Fake provider and actual .NET API/protocol code at commit
  `7ecc24aac0a5ad766f6843faabf0eaeb71f1b753`; no production data or adjacent source edits.
  The recovery test injects initial Streaming unavailability and accelerates elapsed
  cooldown; the final observed candidate update gap was 13 ms on this local run, not a
  production latency guarantee. All real requests use the target server.
- Debug/release AARs, release lint and local Maven staging passed. API inventory and
  Java 11 bytecode check passed: unchanged **78 public types**, no transport/codec types
  in public signatures.
- Independent Java and Kotlin 1.9.25 consumer debug/release, R8 and JUnit checks passed.
- API 34 emulator `emulator-5554`, actual published AAR: both independent debug consumers
  emitted `PHASE4_PASS` for Streaming/Polling/Identify/offline/online/Close against the
  target server through `adb reverse tcp:5189 tcp:5189`. Java callbacks and Kotlin
  suspend adapters both exercised Android completion delivery.
- Final combined `:sdk:testDebugUnitTest -PliveIntegration`: **89 passed**, zero failures,
  errors or skips. Final emulator runs at 11:41 (Europe/Berlin) again emitted both PASS markers.

Server compilation succeeded with existing NuGet vulnerability warnings for Microsoft.OpenApi
2.4.1 and SharpCompress 0.30.1; this task did not change server dependencies. The first
consumer command required quoting `'-PconsumerKotlinVersion=1.9.25'` in PowerShell; the
unquoted invocation was parsed as version `1` and failed before compilation. The corrected
command passed. An initial live-test assertion named the wrong fixture flag; it was corrected
against the server's checked-in snapshot (`returns-true`) before the successful runs.

Not verified in this phase: a distributed database/MQ deployment, production service/gateway,
physical devices, minimum API 21 device execution, post-R8 device execution, hosted CI,
or real Android lifecycle/network observers. Kotlin 1.9.24/2.2.10 consumer matrix was not
repeated. Events/Track/Flush delivery and event protocol compatibility remain Phase 5.
No Git commit, push or remote artifact publication was performed.

## JSON node and duration upper-limit removal — 2026-10-02

Removed the 50,000-node JSON conversion cap, retaining the depth-64 check. Removed
fixed upper limits on startup/request/close durations, operation and coroutine waits,
polling/flush intervals and Flag grace. Existing minimums, defaults, event capacity,
runtime queue budgets and the internal two-second source-stop budget remain unchanged.
Monotonic deadline addition saturates at `Long.MAX_VALUE` to prevent overflow.

`:sdk:testDebugUnitTest :sdk:lintRelease` passed: 62 tests, no failures/errors/skips.
Regression coverage includes a 50,001-element JSON array, excessive nesting rejection,
waiting beyond five minutes, `Long.MAX_VALUE` configuration/Identify/coroutine waits,
and invalid minimum values. No device run was performed for this change.

## Cache limit removal — 2026-10-02

Removed the Flag cache's 10 MiB cap and seven-day expiry, including clock-rollback
cache misses and age-based pruning during writes. Five-context LRU eviction remains;
the disk schema and anonymous identity storage are unchanged.

`:sdk:testDebugUnitTest :sdk:lintRelease` passed. Regression coverage verifies a
snapshot above 10 MiB survives persistence/restart without evicting another context,
and cached data remains available after a year, clock rollback and subsequent writes.
No device run was performed for this change.

## Phase 3 — 2026-10-01

Scope and storage policy: [phase-3.md](./phase-3.md). Prior sections are historical.

- SDK unit tests: **57 passed**, 11 model + 29 local runtime + 17 persistence;
  no failures, errors or skips. New coverage includes first-write/restart, full-context
  matching, cache/Bootstrap arbitration, late loads, shared clear epochs, physical-write
  clear races, equal timestamps/lower cursors, expiry/LRU/byte limits, corrupt/unavailable
  storage, deadlines/capacity and anonymous reset/revision/failure outcomes.
- Release AAR, release lint and local Maven staging passed.
- API inventory / Java 11 bytecode passed, unchanged 78 public types.
- Independent Java and Kotlin 1.9.25 consumer debug/release, R8 and JUnit passed.
- API 34 emulator `emulator-5554`: Java and Kotlin debug runtime checks passed.
  Kotlin additionally logged `PHASE3_PASS previousProcessData=false` on first setup and
  `previousProcessData=true` after force-stop/relaunch with a new PID. These checks read
  the previous cache and anonymous key before changing them, then verify reset, reuse,
  namespace clear retaining memory/identity, and post-clear repopulation.

Commands: `gradlew.bat :sdk:testDebugUnitTest :sdk:assembleRelease :sdk:lintRelease
:sdk:publishReleasePublicationToLocalTestRepository`, `python tools/check_api.py`, then
`gradlew.bat -p consumer-tests -PconsumerKotlinVersion=1.9.25 :java:assembleDebug
:java:assembleRelease :java:testDebugUnitTest :kotlin:assembleDebug :kotlin:assembleRelease
:kotlin:testDebugUnitTest`. Install debug APKs using `adb install -r`; launch each
`co.featbit.consumer.<language>/.SmokeActivity`, then force-stop/relaunch Kotlin.

The first device attempt exposed the Custom built-in-endpoint configuration conflict;
the namespace matcher and fixture were corrected, with a regression test for Custom
discriminator + SDK key, before the passing runs. No configuration restriction was removed.
Final review also eliminated intermediate Bootstrap publication during cached Identify.

The Kotlin 1.9.24/2.2.10 matrix was not repeated. No live service, physical device,
backup/restore, hosted CI or post-R8 device execution was verified. Controlled fault
injection and completed-write process restart are covered; killing a process at every
AtomicFile replacement stage remains a Phases 6–7 acceptance check. No remote publication,
Git commit or push was performed, and adjacent repositories were not changed.

## Flag input limit removal — 2026-10-01

Removed SDK-imposed Flag byte/count limits, including JSON text size. Duplicate-key
validation and JSON parsing depth/node limits remain. Regression checks cover values
above 1 MiB, Full data above the former 8 MiB budget, a 50,001-record Patch, large
keys/types/reasons/options, Bootstrap, TestData replacement and parsed JSON.

- SDK unit tests: 40 passed (11 model + 29 runtime/fixture), no failures/errors/skips.
- Release AAR, release lint and local Maven publication: passed.
- API baseline and Java 11 bytecode: passed, 78 public types.
- Independent consumer builds and emulator runs were not repeated for this change.

Commands: `gradlew.bat :sdk:testDebugUnitTest :sdk:assembleRelease :sdk:lintRelease :sdk:publishReleasePublicationToLocalTestRepository`, then `python tools/check_api.py`.

The Phase 2 evidence below predates this change; its oversized-rejection test has
been replaced by acceptance coverage, and its device results are historical.

## Phase 2 — 2026-10-01

Current implementation scope: [local runtime and boundaries](./phase-2.md).
Phase 1 evidence below is historical and does not substitute for these checks.

| Check | Result |
| --- | --- |
| SDK debug/release AAR | Passed with JDK 17, AGP 8.5.2, Kotlin 1.9.25 |
| SDK unit tests | 39 passed: 11 model tests + 28 runtime/fixture tests; no failures/errors/skips |
| SDK release lint | Passed |
| Binary API and Java 11 bytecode | Passed; 78 public types; three additive static factory entries reviewed |
| Local Maven staging | Passed; AAR/source JAR/POM/module metadata in `build/test-repository` |
| Independent Java consumer | Debug/release, JUnit and R8 passed against the final staged AAR |
| Independent Kotlin 1.9.24 / 1.9.25 consumers | Debug/release, JUnit and R8 passed against the final staged AAR |
| Independent Kotlin 2.2.10 consumer | Debug/release, JUnit and R8 passed; analyzer limitation below remains |
| API 34 emulator debug runtime | Java and Kotlin 1.9.25 Activities displayed PASS; TestData, independent Custom factories, evaluation, Identify, mode changes, Close; Kotlin additionally suspend/Flow |
| Conversion/resource probe | 5,000 JSON records on the emulator and JVM; observations and limitations in phase-2.md |

Both final debug APKs were installed and launched on `emulator-5554` (API 34). The
retry button was exercised in each app: PASS persisted and the completed run count
advanced from 1 to 2 with updated completion times. These runs used Kotlin 1.9.25;
the other consumer compiler versions were built/tested but not launched on the device.

Runtime tests include immutable/coherent reads, bootstrap shadowing, equal/stale patches,
archive/restoration, exact baselines, same-key and A→B→A identity changes, elapsed deadlines,
mode supersession, callback reentry/detach/capacity, Flow cancellation/close, source failures,
physically blocked extension workers, stop timeout, safe logging, oversized atomic rejection,
anonymous preparation/adoption fencing and TestData clock rollback/unchanged timestamps.
Fake lifecycle/network inputs are covered; real Android lifecycle observers are not installed.

Commands used on the SDK root (set the JDK 17/Android SDK paths first):

```powershell
.\gradlew.bat :sdk:assembleDebug :sdk:assembleRelease :sdk:testDebugUnitTest :sdk:lintRelease :sdk:publishReleasePublicationToLocalTestRepository
python tools/check_api.py
.\gradlew.bat -p consumer-tests '-PconsumerKotlinVersion=1.9.25' :java:assembleDebug :java:assembleRelease :java:testDebugUnitTest :kotlin:assembleDebug :kotlin:assembleRelease :kotlin:testDebugUnitTest
```

Local build logs are retained in ignored `build/phase-2-sdk.log` and
`build/phase-2-consumer-<version>.log`. Runtime launchers execute additional Android checks;
consumer JUnit covers models and saved-only TestData, not a mocked Android client runtime.
Hosted CI has not been observed; its existing SDK test command automatically includes the
new runtime suite and versioned fixtures. No network, persistent-cache, anonymous-storage,
real-device or post-R8 device conformance is implied. Release APKs remain unsigned.

Kotlin 2.2.10 consumer builds exposed an AGP 8.5.2 lint-analyzer limitation: stderr reported
Kotlin metadata 2.2.0 while the analyzer expected 2.0.0 during `lintVitalAnalyzeRelease`.
The build returned success, but this consumer lint analysis is **not** recorded as fully
verified. SDK release lint uses 1.9.25 and passed separately. No metadata bypass or lint
suppression was added; correcting the consumer analyzer/toolchain is a later compatibility
task, not evidence of a runtime failure or a reason to change the SDK compiler silently.

## Phase 1 historical verification

For repeatable Android Studio and device steps, see the [manual verification guide](./manual-verification.md).

Recorded 2026-09-30 on Windows, Microsoft OpenJDK 17.0.11, Android platform/build-tools 34.
Commands are in [the implementation baseline](./phase-1.md#reproducible-local-checks).
This records local evidence, not a hosted CI run or final SDK conformance.

| Check | Result |
| --- | --- |
| Library debug and release AAR | Passed; `sdk/build/outputs/aar/` |
| SDK compiler / resolved standard library | Kotlin 1.9.25 / 1.9.25 |
| Public-model JUnit tests | 8 passed, no failures, errors or skips |
| Release lint | Passed; only pinned-version update advice is disabled |
| Public binary inventory / bytecode | Matches `sdk/api/public-api.txt`; all SDK classes target Java 11 or lower |
| Local Maven publication | AAR, source JAR, POM and Gradle module metadata staged under `build/test-repository/` |
| Java consumer | Independent AAR compile, model JUnit, debug APK and R8 release APK passed |
| Kotlin 1.9.24 consumer | Independent AAR compile, model JUnit, debug APK and R8 release APK passed |
| Kotlin 1.9.25 consumer | Independent AAR compile, model JUnit, debug APK and R8 release APK passed |
| Kotlin 2.2.10 consumer | Independent AAR compile, model JUnit, debug APK and R8 release APK passed |
| Candidate runtime graph | Android variant resolution passed; not yet used by implementation |
| Protocol/source comparison | Pinned sources reviewed; metric-name discrepancy recorded in baseline |

Each consumer has one model smoke test. SDK compiler remains 1.9.25 in all matrix rows.
The 1.9.24 consumer resolves runtime stdlib 1.9.25 through the AAR; the 1.9.25 consumer uses
1.9.25; the 2.2.10 consumer uses 2.2.10. These are separate compiler/runtime choices.
No metadata compatibility checks or R8 missing-class checks are suppressed. Consumer releases
are shrunk using the normal optimized Android rules; there is no keep-all SDK rule.
JUnit assertions execute before shrinking; APK creation does not prove post-R8 execution.

Shipped runtime graph: Kotlin stdlib 1.9.25, Coroutines core JVM 1.8.1 and JetBrains
annotations 23.0.0. Future candidate resolution additionally includes Coroutines Android 1.8.1,
OkHttp 4.12.0, Okio JVM 3.6.0, Serialization core/JSON JVM 1.6.3, lifecycle process/runtime/common
2.8.7, AndroidX annotation JVM 1.8.1, arch core 2.2.0, startup 1.1.1, profileinstaller 1.3.1,
tracing 1.0.0, concurrent futures 1.1.0 and listenablefuture 1.0. Kotlin jdk7/jdk8 bridge
artifacts resolve to 1.9.10; they do not replace selected stdlib 1.9.25. The task
`:sdk:resolveCandidateRuntime` prints the exact artifact set; use Gradle `dependencies` and
`dependencyInsight` when adopting a candidate. Android variant resolution alone does not
validate lifecycle runtime behavior or manifest merging in the future SDK.

A 2026-09-30 query to [OSV's public batch API](https://google.github.io/osv.dev/api/#querybatch)
returned no advisory matches for Maven coordinates/versions Kotlin stdlib 1.9.25,
Coroutines core JVM 1.8.1, OkHttp 4.12.0, Okio JVM 3.6.0, Serialization JSON JVM 1.6.3 and
lifecycle-process 2.8.7. This limited database lookup is not a complete transitive security
audit or a guarantee that older dependencies have no issues. Recheck when adopting candidates
and preparing a release. The known Kotlin/AGP official-support-range limitation is documented
in the baseline despite successful local builds.

Update 2026-10-01: both consumers now have launcher Activities with visible PASS/FAIL and
retry, calling their existing model checks. See [device instructions](../consumer-tests/README.md).
Java and Kotlin 1.9.25 consumer debug/release builds, R8 and model tests passed after this
change. APK manifest inspection confirmed both debug launchers. The other compiler versions
were not rerun for this Activity-only update; their earlier results above remain historical.
Follow-up on 2026-10-01: added a visible run count and completion time to both pages.
Both debug builds passed, were installed on emulator-5554, and launched successfully.
UI hierarchy inspection confirmed PASS, then a count change from 1 to 2 and a new timestamp
after tapping retry in each consumer. This verifies debug model checks and retry feedback,
not release/R8 device execution or SDK runtime behavior.

User validation update on 2026-10-01: key and name now reject null, empty and whitespace-only
strings; name must be explicitly supplied. Valid values retain their original whitespace.
All 10 public-model tests passed, including invalid-field diagnostics and preservation checks.
Release AAR, release lint, local Maven staging and the unchanged 76-type public API/Java 11
bytecode checks passed. Updated Java and Kotlin 1.9.25 consumers passed their model tests
and R8 release builds against the staged AAR. Other compiler versions and device runs were
not repeated for this validation change.

FlagRecord update on 2026-10-01: retained key/version, renamed value/declaredType to
variation/variationType, moved variationOptions to the record and removed AnalyticsMetadata
and sendToExperiment. All 11 model tests passed, including variation-option snapshot isolation,
unmodifiable access and absent/empty metadata. Release AAR, lint and local Maven staging passed.
The reviewed API baseline now contains 75 public types and passes the Java 11 bytecode check.
Java and Kotlin 1.9.25 consumer tests and R8 release builds passed against the updated AAR;
other compiler versions and device checks were not repeated for this API change.

Timestamp naming update on 2026-10-01: FlagRecord.version is now FlagRecord.timestamp,
meaning the last flag change time in Unix milliseconds. Baseline.cursor is unchanged.
Builder parameters, Kotlin named arguments, Java getTimestamp(), model tests, design documents
and the public API baseline were updated. All 11 model tests, release AAR/lint, API/bytecode
checks, local Maven staging, Java/Kotlin 1.9.25 consumer tests and R8 release builds passed.
Other compiler versions and device checks were not repeated for this rename.

Not executed: hosted GitHub Actions, physical-device or release APK runs, runtime state/transport/event
tests, Android memory measurements, live service startup/protocol verification or Maven Central
publication. Runtime factories, adapters and TestData currently have contracts only. These are
explicit later-phase work, not passing Phase 1 tests.
