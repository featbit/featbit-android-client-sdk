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
| Real lifecycle/network/Doze | AndroidPlatformMonitor, PlatformState | PlatformLifecycleTest; phase6_device.py on installed Debug/R8 consumers |
| Attribute schema and same-key resampling | RuntimeFactory.enrich | Kotlin AttributeSmoke on installed Debug/R8 consumers |
| Version consistency/no read I/O | SdkInfo, LocalClient | ReleaseContract with StrictMode, all consumer versions |
| Callback/Flow disposal | Execution, CoroutineAdapters | LocalRuntimeTest and consumer RuntimeSmoke |
| Diagnostics | Execution.Diagnostics | loggerIsBoundedRateLimitedAndExceptionIsolated; status sanitization and transport tests |

## Outstanding release gates

These cannot be converted to PASS by JVM mocks, historical results, or API-34 emulator logs:

- Physical-device natural sleep/App Standby, vendor restrictions, VPN, missing-permission
  device path, interactive multi-window and API 21/newer-OS execution matrix.
- Backup/restore and process termination at each AtomicFile replacement boundary.
- Deployed database/MQ attribution and EndUser profile behavior. Fake/None protocol and
  Domain conversion tests are valuable but do not cover persistence through a deployment.
- Physical-platform automatic-attribute variations beyond the current emulator schema.
  ReleaseAcceptanceTest verifies unavailable values, lazy disabled collection, changed-value
  Identify resampling, no foreground resampling and preservation of older context snapshots.
- Exhaustive diagnostics injection at every path and log level, slow/reentrant logger and
  full resource-leak measurement. All-level loss-count/reentrant/throwing-logger and existing
  bounded-worker/rate-limit tests provide partial evidence, not exhaustive path coverage.
- Hosted CI and credential-backed release signing/Central validation and fresh download.

P06/P08/P09 durable events and iOS are N/A because unsupported, not passed. No persistent
event queue, OpenFeature Provider, Compose/KMP integration or samples are added here.

Full release acceptance remains open until applicable mandatory scenarios have evidence.
Phase 7 tooling/documentation completion is distinct from a production release declaration.
