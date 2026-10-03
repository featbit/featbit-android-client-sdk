# Release preparation

Phase 7 prepares a local publication and repeatable acceptance. Formal Maven Central
publication is a separate task. Neither a successful GitHub Release nor a signed local
repository proves that a downloadable Central artifact works.

## Local commands

### Windows: full live and emulator acceptance

Start one API 34 emulator in Android Studio, then run from the SDK root in
Windows PowerShell 5.1 or PowerShell 7:

```powershell
.\tools\run-live-acceptance.ps1
# Optional preflight without building, starting the service or running tests:
.\tools\run-live-acceptance.ps1 -CheckOnly
# Select explicitly when more than one emulator is running:
.\tools\run-live-acceptance.ps1 -Serial emulator-5554
```

This wrapper checks JDK 17, Python 3.8+, Android platform 34/build-tools 34.0.0 and
35.0.0, the existing debug keystore, .NET SDK 10 and the adjacent
`featbit/modules/evaluation-server` checkout. It uses environment settings or common
Windows installation paths; override with `-JavaHome`, `-AndroidHome`, `-DotnetPath`
or `-Python` if needed. `-Version` defaults to `0.1.0-SNAPSHOT`.

The wrapper builds and starts its own Fake/None server on port 5189, then runs all
four consumer rows through `acceptance.py --live --serial`. It rejects an occupied
service port rather than reusing or stopping an unknown process. Its `finally` block
stops only its own server and restores process environment variables and the working
directory, including when a build or check fails. The device helpers restore their
temporary settings/mappings. Allow the run to finish; forcibly closing the terminal
can prevent cleanup. Keep the emulator free from manual interaction during checks.
Platform network checks wait up to 45 seconds for Android to establish or remove
the requested Internet-capable transport, then assert SDK state with the ordinary
12-second deadline. `NETWORK_READY` records independent OS and SDK state. An
Android transition timeout remains a failed run; rebuild the probe APK when updating
the platform script. Each platform subprocess has a 480-second overall budget.

Launcher/server logs are in `build/live-acceptance/<run>/`. The nested runner prints
its separate `build/phase7/<run>/` evidence directory and writes `report.json` there.
Existing evidence is retained. The script prints the Git revision and working-tree
status; commit intended changes first when evidence must identify a clean commit.
It does not create/start an emulator, publish remotely, or cover physical devices
and deployed database/MQ persistence.

### Individual entry points

Use `tools/acceptance.py` as the combined acceptance entry point. It calls
`tools/platform_device_checks.py` for platform checks and `tools/live_device_checks.py`
for installed-APK live checks when requested. These helpers also support focused reruns;
there is no need to run every historical development phase separately.

Set JAVA_HOME to JDK 17 and ANDROID_HOME to the Android SDK. Install platform 34 and
build-tools 34.0.0 and 35.0.0. Run from this repository:

```sh
python tools/acceptance.py
# Also install/run Debug and actual R8 Release fixtures on an existing emulator:
python tools/acceptance.py --serial emulator-5554
# Require the explicitly started Fake/None target evaluation service:
python tools/acceptance.py --live
# Also run installed Debug/R8 consumers against the target:
python tools/acceptance.py --live --serial emulator-5554
```

Each invocation creates `build/phase7/<timestamp>-<unique-id>/`, its Maven repository,
independent consumer roots/Wrappers, logs, JUnit evidence and `report.json`. No directory
is deleted. Java does not apply Kotlin's compiler plugin. Shared fixture sources are
copied, but consumers never substitute SDK sources or depend on an SDK Gradle project.
FeatBit coordinates resolve exclusively from that run's repository. Different consumer
rows use their own plugin/Gradle versions. `--rows java` or `--rows kotlin-2.2.10` narrows
requested checks and must not be described as the full matrix.

Live SDK tests always execute: `-PliveIntegration` disables both Gradle up-to-date reuse
and build-cache reuse for the test task, including direct Gradle invocations. The runner
passes a unique `-PliveEventPayload=<run>/target-event-payload.json`; the test writes there
only after successful delivery assertions. All four live checks must pass and this run's
payload must exist before Domain validation. A previous run's payload is never copied.
Direct Gradle runs default to `sdk/build/phase-5-target-payload.json`; that one output is
removed before each live test execution, so failing tests cannot leave an old success payload.
Evidence admission and device-script cleanup regressions run with
`python -B -m unittest discover -s tools -p 'test_*.py'` and in both CI workflows.

The device runner uses the existing debug test key to install R8 APKs; this is not a
distribution signing key. It reads PID-scoped logs so stale PASS markers cannot pass a run.
Kotlin rows additionally run the platform fixture for both variants. Only opt-in fixture
APKs register the exported lifecycle probe; never distribute them.

The SDK remains Kotlin 1.9.25 / AGP 8.5.2 / Gradle 8.7 / JDK 17, targeting Java 11.
Live mode requires .NET 10 and the adjacent evaluation-server checkout for actual Domain
validation of the exported payload. To rerun only device interoperability on already built
and test-signed APKs: `python tools/live_device_checks.py <evidence-directory> --serial emulator-5554`.
Consumer configurations:

| Compiler | AGP | Gradle | JDK |
| --- | --- | --- | --- |
| Java, no Kotlin plugin | 8.5.2 | 8.7 | 17 |
| Kotlin 1.9.24 | 8.1.0 | 8.1.1 | 17 |
| Kotlin 1.9.25 | 8.1.0 | 8.1.1 | 17 |
| Kotlin 2.2.10 | 8.10.1 | 8.11.1 | 17 |

Resolved dependency/plugin reports are saved for every row; compiler and resolved stdlib
are separate values. The 1.9.24 consumer resolves the SDK's stdlib 1.9.25. Kotlin 2.2.10
uses its own runtime. Older consumer AGP may warn about compileSdk 34; the SDK producer's
Kotlin 1.9.25/AGP 8.5.2 combination remains outside Kotlin's fully supported range. Passing
local tests does not turn that into an upstream support guarantee.

Toolchain references: [Kotlin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html),
[Android R8 support](https://developer.android.com/build/kotlin-support), and
[AGP 8.10 requirements](https://developer.android.com/build/releases/agp-8-10-0-release-notes).

## Publication metadata and signing

The SDK publication includes release AAR, sources, Dokka HTML in a javadoc-classifier JAR,
POM, and Gradle module metadata with transitive dependencies. Group/artifact are
`co.featbit:featbit-client-android`; override development version with `-PsdkVersion=<version>`.
MIT license, project/SCM and contributor metadata are included. `getVersion()` uses the
same Gradle version as the publication. Public API/Java 11 checks inspect the actual AAR.

The separate `release-candidate.yml` workflow accepts a version only on its matching
`v<version>` tag, reruns the artifact matrix and stages a signed repository. It requires
the `release` environment and SIGNING_KEY/SIGNING_PASSWORD secrets. Signing is opt-in;
ordinary builds do not need credentials. No remote publishing endpoint is configured.
The hosted release-candidate workflow and credential-backed signing require a real authorized run before
being claimed verified. Do not print, commit or archive signing credentials.
The ordinary library/consumer CI has passed separately; see the
[hosted CI record](./verification.md#hosted-library-and-consumer-ci--2026-10-03).

Before formal publication, resolve every applicable acceptance gate in [conformance](./conformance.md),
review license/dependency notices, decide the release version, verify Central namespace/account
access, and align tag, release notes, source baseline and artifact hashes. Then separately
perform Central validation/upload and verify fresh Maven-coordinate downloads in independent
consumers. Publish the core SDK first. The OpenFeature Provider remains a separate repository
and toolchain. Persistent events, iOS/KMP, Compose bindings and sample apps remain deferred.
