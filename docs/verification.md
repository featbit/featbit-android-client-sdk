# Phase 1 verification

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
