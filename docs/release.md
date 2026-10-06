# Release preparation

Local commands prepare a publication and repeatable acceptance. The manually dispatched
release workflow now signs, uploads and automatically publishes to Maven Central.
Neither a successful GitHub Release nor a signed local repository proves that a
downloadable Central artifact works; independent remote-consumer verification remains required.

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
its separate `build/acceptance/<run>/` evidence directory and writes `report.json` there.
Existing evidence is retained. The script prints the Git revision and working-tree
status; commit intended changes first when evidence must identify a clean commit.
It does not create/start an emulator, publish remotely, or cover physical devices
and deployed database/MQ persistence.

### Linux/macOS: full live and emulator acceptance

From the SDK checkout, with an API 34 emulator already booted:

```bash
bash tools/run-live-acceptance.sh --check-only
bash tools/run-live-acceptance.sh
# Select explicitly when multiple emulators are running:
bash tools/run-live-acceptance.sh --serial emulator-5554
```

The Bash 3.2-compatible entry point invokes `tools/run_live_acceptance.py` using
`python3` (override with `PYTHON=/path/to/python3`). Python 3.8+, Git, Bash, JDK 17,
.NET SDK 10, Android platform 34 and build-tools 34.0.0/35.0.0 are required.
Set `JAVA_HOME`, `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) and optionally
`DOTNET_HOST_PATH`; command options `--java-home`, `--android-home`, `--dotnet`
override them. `--version` defaults to `0.1.0-SNAPSHOT`.
Linux defaults to `~/Android/Sdk`; macOS defaults to `~/Library/Android/sdk` and
uses `/usr/libexec/java_home -v 17` when JAVA_HOME is absent. Use an emulator image
matching the host architecture, including ARM64 on Apple Silicon.

Keep the SDK and `featbit` checkouts beside one another; the service must exist at
`../featbit/modules/evaluation-server`. An existing `~/.android/debug.keystore`
is required. Build a Debug app to create it, or generate a disposable test key in CI
before invoking the launcher:

```bash
mkdir -p "$HOME/.android"
if [ ! -f "$HOME/.android/debug.keystore" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -noprompt \
    -keystore "$HOME/.android/debug.keystore" -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
fi
```

The Python launcher builds and starts the Fake/None service on port 5189, waits for
its own startup log and a TCP connection, then calls the same
`acceptance.py --live --serial` matrix. It captures console/server logs in
`build/live-acceptance/<run>/`; matrix evidence is saved in `build/acceptance/<run>/`.
It rejects an occupied port and stops only its own service process group after
success, failure or a handled interruption. Child environments and working
directories do not modify the calling shell. SIGKILL or host termination cannot
run cleanup. No emulator is created, no tools are installed and nothing is published.

This entry point can be invoked by a Linux/macOS CI job after the same prerequisites
are prepared; no live CI workflow is added by this change. Linux launcher regression
tests pass under WSL with controlled tools. Full Linux/macOS live/device matrices
and a native macOS launcher run remain unverified; see the verification record.

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

Each invocation creates `build/acceptance/<timestamp>-<unique-id>/`, its Maven repository,
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

The `release-candidate.yml` workflow is displayed as **Publish Android SDK to Maven Central**.
It accepts a version only on its matching `v<version>` tag, reruns the artifact matrix,
stages a signed repository, then uploads and automatically publishes it. The `release`
environment requires four **Secrets** (not plain environment Variables): `SIGNING_KEY`,
`SIGNING_PASSWORD`, `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD`. The latter pair
comes from a Central Portal user token. Ordinary builds do not need credentials.
After signing, the workflow also creates `featbit-client-android-<version>-central-bundle.zip`
and uploads it as the `maven-central-bundle` artifact. It checks that the AAR, POM, module,
sources and documentation each have signatures and required checksums, and verifies that
the archived bytes match the staged files. Only the selected version directory is included,
with the full `co/featbit/featbit-client-android/<version>/` path; repository-level
`maven-metadata.xml` is excluded. Existing signatures and checksums are preserved.
The GitHub artifact download is an outer ZIP: extract it once to obtain
`featbit-client-android-<version>-central-bundle.zip`. The workflow uploads this inner ZIP
directly; manual download/upload is no longer necessary.
The original `signed-release-candidate` repository artifact remains available for inspection.
`tools/publish_central.py` calls the [Central Publisher API](https://central.sonatype.org/publish/publish-portal-api/)
with `publishingType=AUTOMATIC`. Validation success automatically proceeds to publication,
without a second Publish click. The workflow succeeds only after Central reports `PUBLISHED`.
It polls for up to 30 minutes, with bounded HTTP requests; validation errors, unrecoverable
HTTP errors and timeouts fail the step. Status requests retry transient network/429/5xx errors.
The deployment ID is written immediately to logs, the job summary and
`central-publication-result/report.json` (inside the downloaded artifact). This report also
contains the bundle SHA-256, latest state and sanitized validation errors. Credentials are
never intentionally logged or included in artifacts; redirects are not followed.

Uploads are not automatically retried. If an upload response is lost or status polling times
out, Central may still complete publication. Inspect the Portal and deployment ID before any
rerun; do not blindly re-upload or overwrite an already published version. Same-version runs
are serialized without cancelling an active publication. Cancelling a workflow does not cancel
Central publication. Existing environment protection rules still apply before the job starts.

The API behavior is covered by controlled tests. The automatic publication path completed
for 0.1.0 in [workflow run 37332049292](https://github.com/featbit/featbit-android-client-sdk/actions/runs/37332049292).
Its public Maven Central artifact was independently confirmed. Every subsequent version
still requires its own successful publication and fresh-download verification.
Do not print, commit or archive signing credentials.
The ordinary library/consumer CI has passed separately; see the
[current acceptance and CI record](./verification.md#release-readiness--2026-10-04).

Before formal publication, resolve every applicable acceptance gate in [conformance](./conformance.md),
review license/dependency notices, decide the release version, verify Central namespace/account
access, and align tag, release notes, source baseline and artifact hashes. Then separately
perform Central validation/upload and verify fresh Maven-coordinate downloads in independent
consumers. Publish the core SDK first. The OpenFeature Provider remains a separate repository
and toolchain. Persistent events, iOS/KMP and Compose bindings remain deferred.
Java and Kotlin sample apps are implemented under `samples/`.


## Maven Central installation after publication

Version 0.2.0 is published and independently verified. Both READMEs now use this
configuration. Java apps may use the same Kotlin DSL configuration:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

```kotlin
// App module build.gradle.kts
dependencies {
    implementation("co.featbit:featbit-client-android:0.2.0")
}
```

For future versions, activate their installation instructions only after public-download
verification. Local development instructions remain a separate optional section.

## 0.2.0 publication

- Published [release notes and migration guidance](./release-notes-0.2.0.md).
  Coordinate: `co.featbit:featbit-client-android:0.2.0`; tag: `v0.2.0`.
- Runtime and sample source baseline: `c0d7323b9e9959e4b52d4b20379d83d5fc9e51fb`.
  [Hosted CI](https://github.com/featbit/featbit-android-client-sdk/actions/runs/37487716423)
  passed for this commit. Subsequent release preparation changes are documentation only.
- Local `python -B tools/acceptance.py --version 0.2.0` passed all 22 checks;
  evidence: `build/acceptance/20261006-173158-a072baab/report.json`. This covers 155 SDK
  tests, the 79-public-type API/Java 11 check, and Java plus Kotlin 1.9.24, 1.9.25 and
  2.2.10 consumers (Debug, Release/R8, unit tests and Release lint). This run did not
  repeat live-service or device checks. Tooling tests passed (35 total, five POSIX-only
  skips on Windows), and `spotlessCheck` passed.
- Tag `v0.2.0` identifies `7210251fbe5e3f9f452ef15e370da265f1009702`; both
  [commit CI](https://github.com/featbit/featbit-android-client-sdk/actions/runs/37489448132)
  and [tag CI](https://github.com/featbit/featbit-android-client-sdk/actions/runs/37489866694) passed.
- [Publication workflow](https://github.com/featbit/featbit-android-client-sdk/actions/runs/37489895116)
  completed successfully; Central deployment `8b28e24c-20ad-49d1-b921-4b2fa4312cff`
  reached `PUBLISHED`. Public artifact/signature comparison and fresh Java/Kotlin
  consumption passed. See [evidence](./verification.md#maven-central-020-publication--2026-10-06).

## 0.1.0 release handoff (historical)

- Prepared [release notes](./release-notes-0.1.0.md); intended tag: `v0.1.0`.
- [Current evidence](./verification.md#release-readiness--2026-10-04) separates automated
  checks from maintainer-confirmed device/deployment validation.
- Complete this documentation commit and its hosted CI before creating the release tag.
- Configure all four `release` environment secrets and run **Publish Android SDK to Maven Central**
  on the matching tag. This dispatch authorizes automatic publication after validation.
- Retain the signed artifact hashes, Central deployment report and workflow URL. After
  `PUBLISHED`, verify fresh downloads before activating the README instructions above
  and publishing the GitHub Release notes.
