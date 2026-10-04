# Conformance scope and release gates

The specification is a draft, not a published certification. Current baseline:
sdk-spec `3f08faa77dbf70bea208bd8ab946c2aa0b38ffad` plus working English changes
to mobile README/conformance and common conformance/identity/public-api. Accepted Android
decisions are recorded in handoff: no Flag byte/count caps, no cache byte/age cap,
and no client-side event field-format limits. Do not silently restore older draft limits.

`tools/conformance_report.py <evidence-directory>` produces a requirement → implementation
→ test → execution result table from the **copied JUnit XML of that run**, not source names
alone. It covers every common checklist area and M01–M30/P01–P10/A01–A02/platform entries.
Missing/failed representative tests return a failing exit code. Named checks are deliberately
representative: passing one does not mean every clause or device combination in a row passes.
The acceptance runner also saves actual AAR hashes, dependency/plugin reports, commands and
device properties. The phase report identifies which evidence directory was actually executed.

## Coverage layers

| Contract | Implementation | Executable evidence |
| --- | --- | --- |
| API/configuration/immutable models | api package, RuntimeFactory | ModelTest, Java/Kotlin ModelSmoke, ReleaseContract |
| Local evaluation/identity/TestData/extension lifecycle | LocalClient, Values, LocalTestData | LocalRuntimeTest; Java/Kotlin RuntimeSmoke |
| Cache and anonymous persistence | Persistence | PersistenceTest, OnlineCacheTest, Kotlin PersistenceSmoke |
| Streaming/Polling/fallback/headers | OnlineSync, SyncProtocol, SyncTransport | OnlineSyncTest, SyncProtocolTest, SyncTransportTest; LiveSyncIntegrationTest |
| Analytics/privacy/Flush/terminal isolation | Events, EventProtocol | EventsTest, EventTransportTest; LiveEventIntegrationTest plus tools/event-contract |
| Real lifecycle/network/Doze | AndroidPlatformMonitor, PlatformState | PlatformLifecycleTest; platform_device_checks.py on installed Debug/R8 consumers |
| Attribute schema and same-key resampling | RuntimeFactory.enrich | Kotlin AttributeSmoke on installed Debug/R8 consumers |
| Version consistency/no read I/O | SdkInfo, LocalClient | ReleaseContract with StrictMode, all consumer versions |
| Callback/Flow disposal | Execution, CoroutineAdapters | LocalRuntimeTest and consumer RuntimeSmoke |
| Diagnostics | Execution.Diagnostics | loggerIsBoundedRateLimitedAndExceptionIsolated; status sanitization and transport tests |

## Current release readiness

The complete local 0.1.0 matrix passed in `build/acceptance/20261004-122019-37bff871/`.
Hosted CI passed for `8b739c2` across Java and all three Kotlin consumer rows.
On 2026-10-04 the maintainer confirmed successful physical-device validation and
real FeatBit deployment checks for event persistence, EndUser updates and experiment
attribution. These manual checks are maintainer-confirmed, not agent-executed.
See the [current evidence record](./verification.md#release-readiness--2026-10-04).

The agreed automated acceptance and manual real-environment validation steps are closed.
Credential-backed release-candidate execution, signing, Central validation/publication and
fresh remote downloads remain pending.

## Detailed coverage limitations

The manual confirmation did not include device/OS inventories, per-scenario logs or
fault-injection results. It must not be expanded into an exhaustive PASS for the following:

- Every natural sleep/App Standby, vendor restriction, VPN, missing-permission,
  multi-window and API 21/newer-OS combination.
- Backup/restore and process termination at each AtomicFile replacement boundary.
- All physical-platform automatic-attribute variations. ReleaseAcceptanceTest covers
  unavailable values, disabled collection, Identify resampling and context snapshots.
- Exhaustive diagnostics injection and full resource-leak measurement. Existing
  logger, bounded-worker and rate-limit tests provide partial evidence.

These are coverage limitations, not newly inferred failures of the maintainer's checks.
Retain them when describing release support; manual confirmation does not constitute
full certification of the draft specification.

P06/P08/P09 durable events and iOS are N/A because unsupported, not passed. Persistent
events, OpenFeature Provider and Compose/KMP integration are outside this core SDK.
Java and Kotlin samples are implemented separately under `samples/`.

A production publication claim additionally requires the remaining signing, Central and
fresh-download steps to complete.
