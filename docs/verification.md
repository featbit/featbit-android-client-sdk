# Verification record

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
