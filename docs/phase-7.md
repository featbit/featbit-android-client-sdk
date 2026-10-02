# Phase 7: release preparation and combined acceptance

Implemented 2026-10-02. SDK source baseline `6eff0ddf7e04eccba3863a2404a2d34edebf2e04`
plus the current Phase 7 changes. This is local release preparation; no commit, push,
Central upload or GitHub Release was performed. Full release acceptance remains open.

## Implementation

- `tools/phase7_acceptance.py` creates an isolated Maven repository and four independent
  consumer roots/Wrappers. All resolve published coordinates, never SDK sources. It runs
  SDK tests/lint/API checks, consumer Debug/Release/JUnit/lint, records dependencies/plugins
  and artifact hashes, and optionally installs Debug/R8 APKs for emulator checks.
- SDK publication now includes sources, Dokka-generated API documentation, POM contributor
  metadata and Gradle module metadata. `sdkVersion` consistently drives the publication
  and runtime version; acceptance used `0.1.0-phase7` to exercise a non-default version.
- The default consumer Wrapper is aligned to Gradle 8.7. Kotlin 1.9.24/1.9.25 consumers
  use Gradle 8.1.1 / AGP 8.1.0; Kotlin 2.2.10 has Gradle 8.11.1 / AGP 8.10.1. The SDK
  stays on Kotlin 1.9.25. No metadata bypass, keep-all rule or lint suppression was added.
- Shared ReleaseContract checks actual artifact version, local state timestamps/provenance,
  immutable snapshots and in-memory reads under StrictMode. Kotlin AttributeSmoke checks
  automatic-attribute schema, disabled output and same-key Identify through the public source API.
- Three ReleaseAcceptanceTest cases cover lazy/disabled attribute sampling, missing fields,
  changed-value Identify, no foreground resampling, old snapshot/cache-key isolation, and
  all log levels with reentrant/throwing logger and independent loss counts. Platform sampling
  was extracted into an internal lazy helper without changing public API/defaults.
- `tools/conformance_report.py` ties every common checklist area and mobile scenario to
  implementation/test evidence from a run's JUnit XML; unsupported persistence/iOS are N/A.
- CI runs one isolated consumer row per job. A separate manually dispatched release-candidate
  workflow validates version/tag, reruns the matrix and prepares an optionally signed local
  repository. Remote publication remains a separate task.
- [Integration guide](./integration.md), [release guide](./release.md), [conformance gates](./conformance.md),
  README, plan and handoff now describe the implemented runtime and remaining acceptance.

## Verification

Final acceptance evidence directory:
`build/phase7/20261002-164239-3eb84010/`.

Command (JDK 17, ANDROID_HOME configured, rebuilt Fake/None service listening on 5189):

```powershell
python tools/phase7_acceptance.py --serial emulator-5554 --live --version 0.1.0-phase7
# After rebuilding the first-draw launcher fix in each existing consumer root:
python tools/phase7_live_device.py build/phase7/20261002-164239-3eb84010 --serial emulator-5554 --phases 0 4 5
```

SDK checks: **139 tests passed**, zero failures/errors/skips, including four opt-in live
tests and three new release-acceptance tests. Release lint, API inventory (**78 public types**)
and Java 11 bytecode checks passed. Local publication contains AAR, sources, POM/module and
a documentation JAR with 668 entries, including public client API pages.

All four consumer rows passed Debug/Release builds, JUnit and full Release lint:

| Consumer | AGP / Gradle | API-34 Debug | API-34 R8 Release |
| --- | --- | --- | --- |
| Java | 8.5.2 / 8.7 | runtime passed | runtime passed |
| Kotlin 1.9.24 | 8.1.0 / 8.1.1 | runtime + platform passed | runtime + platform passed |
| Kotlin 1.9.25 | 8.1.0 / 8.1.1 | runtime + platform passed | runtime + platform passed |
| Kotlin 2.2.10 | 8.10.1 / 8.11.1 | runtime + platform passed | runtime + platform passed |

Device: `emulator-5554`, API 34 x86_64, `sdk_gphone64_x86_64`; JDK 17.0.11.
All six Kotlin runtime launches also emitted PHASE7_ATTRIBUTES_PASS and six platform
launches emitted PHASE6_DEVICE_PASS. Java never applies a Kotlin compiler plugin. The 2.2.10
row completed full lint without the earlier metadata-analyzer incompatibility.

`report.json`, dependency/plugin logs, copied JUnit XML and `conformance.md` record results.
`source-baselines.json` records working-tree/spec hashes captured during acceptance; tools
and documentation continued to be finalized after the SDK build. The final summary is
also recorded in [verification.md](./verification.md).

After the launcher/diagnostics changes, all four consumers were rebuilt (Debug/Release,
JUnit and full lint) and their Release APKs test-signed again; logs are
`<row>-final-launcher-build.log`. The final device evidence is
`target-device-runs/20261002-171633-1ec55ffd/report.json`: **24 checks passed**,
four consumer rows × Debug/R8 × local runtime/target synchronization/target events.
Both Streaming and Polling, Identify, offline/online and Close passed, as did
Track/evaluation/privacy/Flush/retained replay/final Close delivery. Domain verification
of `target-event-payload.json` emitted `PHASE5_CONTRACT_PASS` for three payloads/messages.
`final-summary.json` ties these later fixture results to the original artifact/platform
matrix. Earlier failed target-device reports remain preserved and are not the final result.

Target evaluation-server source baseline is `7ecc24aac0a5ad766f6843faabf0eaeb71f1b753`.
The server was rebuilt with .NET 10 to `build/phase7-server` and run with DbProvider=Fake,
MqProvider=None and CacheProvider=None. Existing Microsoft.OpenApi/SharpCompress NuGet
warnings remained; server source/dependencies were not changed. Domain verification must
accompany HTTP success because invalid analytics can still receive a 200 response.

## Findings during acceptance

The first matrix attempt exposed consumer Wrapper drift to Gradle 9.3.0. The old textual
8.7 replacement failed to select the intended distribution; selection now replaces the
distribution version explicitly, and the checked-in default is 8.7. Those earlier results
do not establish the final toolchain matrix.

A later platform run timed out a successful background Identify: its response arrived just
after the fixture's one-second wait and then correctly established readiness. The successful
background-sync check now allows five seconds, within its request timeout. The explicit Doze
one-second timeout assertion is unchanged. This is a fixture latency assumption correction,
not a change to SDK timeout semantics. Failed run evidence remains in its original directory.

The initial target-device Java event run hit a first-frame OpenGL/main-thread stall: logcat
recorded a roughly 70-second frame and 4170 skipped frames. Another target run timed out
readiness. The SDK must deliver callbacks on main, so fixture waits during renderer startup
were not measuring SDK behavior alone. Both consumer launchers now post checks after the
first draw. Java fixtures additionally log operation stages and safe synchronization state.
`java-initial-renderer-stall.log` preserves the system evidence; failed target reports/logs
remain separate from later runs. The SDK's timeout policy was not relaxed.

## Boundaries

API-34 emulator and forced idle are not physical-device natural sleep. API 21/newer-OS,
VPN, missing-permission device path, vendor restrictions, multi-window, backup/restore and
atomic-write kill points remain unexecuted. Fake/None and Domain message conversion do not
prove database/MQ persistence or deployed experiment attribution. Exhaustive diagnostics
and resource-leak acceptance, hosted CI, credential-backed signing and Central download
verification remain open. See the conformance gates before making any release claim.
