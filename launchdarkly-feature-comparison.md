# LaunchDarkly Android Feature Comparison and Scope Decisions

Date: 2026-09-29. Status: design comparison, not an implementation or test-passing claim.

## 1. Fixed baseline and scope

- LaunchDarkly repository: android-client-sdk.
- Sole comparison baseline: commit `0f40e44b989d46e7d4a8c0c0475029c85c5bbe08`. All evidence links are pinned to this commit; neither a floating main branch nor live API documentation establishes the version baseline.
- The first entry in this snapshot's [CHANGELOG](https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/CHANGELOG.md) is 5.16.0. This table pins a source commit and does not equate it directly with the Maven Central 5.16.0 binary or corresponding tag.
- FeatBit baseline: [current development plan](./plan.md), Kotlin 1.9.25, not yet implemented. Inclusion is a planning commitment, not proof of compatibility or conformance.
- The comparison focuses on Android SDK integration and extensibility. It does not cover every overload, internal implementation, or LaunchDarkly server product capability. FDv2/EAP is distinguished from traditional data sources.
- This table records accepted scope without automatically adding undecided features to the first release. Future decisions update this table, plan.md, and acceptance requirements together. Changing upstream versions requires rechecking evidence and replacing the baseline, not silently reusing conclusions.

Statuses: **First release** = included; **Deferred** = explicitly postponed; **Not provided** = explicitly excluded; **Pending decision** = uncommitted and excluded from coverage claims; **Different contract** = related capability whose API or behavior follows FeatBit specifications.

## 2. Evaluation, identity, and status

| ID | LaunchDarkly capability/evidence at the fixed baseline | FeatBit status | Decision and validation requirements |
| --- | --- | --- | --- |
| E01 | Boolean, Double, String evaluation and detailed APIs [S1][S2] | First release | LaunchDarkly reads typed LDValue data and checks the requested type at evaluation. FeatBit converts selected strings; eager/on-demand/cached conversion is a measured implementation choice, not mandatory all-type precomputation. Validate conversion, fallback, cache, and coherent event eligibility separately. |
| E02 | intVariation / intVariationDetail [S1] | Not provided (first release) | Dedicated integer evaluation is explicitly unnecessary for now. Retain Number and detailed evaluation, without an Int flag type or upstream integer-truncation semantics. |
| E03 | jsonValueVariation / Detail returning unified LDValue [S1] | First release / different contract | Generic variation/Detail and JSON helpers share an immutable model; verify FeatBit declared-type dispatch separately from explicit JSON parsing. |
| E04 | allFlags returning current flag values [S1] | First release / different contract | FeatBit read-all returns raw string details without analytics collection; do not copy the Map<String, LDValue> return type. |
| E05 | Identify, initialization waits, readiness queries [S1][S2] | First release | One current user, bounded waits, generation isolation. Cache availability does not mean remote synchronization. |
| E06 | Global/per-flag listeners and status listeners [S1] | First release / different contract | Listener handles and Flow; notification conditions, subscriptions surviving deletion, and coalescing follow plan.md. |
| E07 | offline configuration and runtime setOffline/setOnline [S1][S3] | First release / different contract | Explicit offline queries, idempotent/concurrent transitions, bounded results. Offline stops synchronization/event work while retaining local values and accepted queues; online resumes under configuration/lifecycle gates. Terminal states are not reset, and successful transition does not mean synchronization readiness. |
| E08 | Named environments through secondaryMobileKeys [S3] | Not provided (first release) | Unified multi-environment management is excluded. Applications manage independent clients, retaining environment data/cache/event isolation, without a named-environment manager or cross-environment bulk operations. |
| E09 | generateAnonymousKeys [S3][S16] | First release / different contract | LaunchDarkly reuses generated keys per context kind, caches a new key before persistence and logs storage errors; the inspected public client has no corresponding anonymous reset API. FeatBit retains explicit enablement, application-scoped keys and persist-before-adopt reset. Use simple common identity outcomes; persistence/adoption stages remain internal. Timeout/supersession does not imply rollback of an admitted write. Do not infer account links; test backup and storage failure. |
| E10 | Automatic environment attributes and applicationInfo [S3] | Automatic attributes included; manual application metadata pending | Automatic collection defaults off; schema, sampling, and conflicts follow FeatBit. A dedicated manual application ID/version configuration API remains undecided. |
| E11 | evaluationReasons configuration [S3] | Detailed reasons included; switch not provided | Detailed APIs always return reason categories and preserve available server explanations without enabling evaluationReasons. Use FeatBit's reason model, without promising LaunchDarkly format compatibility. |
| E12 | getConnectionInformation / getVersion [S1] | First release / different contract | Synchronous local immutable snapshot and actual SDK artifact version. Snapshot/subscriptions share configured/effective modes, synchronization state/pause reasons, current-context local availability/remote confirmation, latest success/failure timestamps and safe errors, and fallback/recovery status. Times belong to the current context; candidate probes are not authoritative success; event failures are separate. Follow plan.md semantics rather than copying the upstream model. |

## 3. Synchronization, caching, and mobile behavior

| ID | LaunchDarkly capability/evidence at the fixed baseline | FeatBit status | Decision and validation requirements |
| --- | --- | --- | --- |
| S01 | Traditional Streaming source [S4] | First release / different protocol | LaunchDarkly uses SSE; FeatBit uses its own WebSocket protocol. Similar capability does not imply protocol compatibility. |
| S02 | Explicit Polling source [S5] | First release | Foreground polling with context isolation, cursors, and bounded requests. |
| S03 | FDv2 default pipeline Streaming fallback to Polling and recovery [S6][S7] | First release / different mechanism | FeatBit implements its own fallback and recovery. Each recovery attempt pauses Polling, retains local reads and uses one Streaming candidate with one deadline; failure resumes eligible Polling. The brief remote-update gap is accepted. Fallback defaults off and enables recovery when selected. Do not copy upstream timeout constants or protocols. |
| S04 | FDv2 initializer/synchronizer pipeline configuration [S6][S8] | Pipeline not provided | Upstream baseline marks this EAP. FeatBit implements its own protocol and one custom-source entry point, without FDv2, selector, or protocol-fallback compatibility commitments. |
| S05 | Foreground/background effects on connections and background polling [S4][S5] | First release | Background polling defaults off and requires explicit enablement, subject to platform execution limits. Transitions preserve context, cursors, and fallback policy without ordinary background event delivery. |
| S06 | Context persistence and maxCachedContexts [S3][S9] | First release | Match deployment/environment/complete context, with clearing, ordered writes, and retention limits; formats are not interoperable. |
| S07 | serviceEndpoints configuration [S3] | First release / different protocol | Configure FeatBit synchronization, polling, and event endpoints with path prefixes; this does not imply LaunchDarkly Relay Proxy connectivity. |
| S08 | HTTP timeouts, headerTransform, useReport, wrapper [S10] | Timeouts/headers included; wrapper excluded | Headers use static creation-time configuration, scoped separately to synchronization/events, protecting protocol/authentication headers and disabling redirects. No dynamic headerTransform callback commitment. Dedicated wrapper metadata is excluded; REPORT is upstream-specific and is not ported. |

## 4. Events, privacy, and extensibility

| ID | LaunchDarkly capability/evidence at the fixed baseline | FeatBit status | Decision and validation requirements |
| --- | --- | --- | --- |
| A01 | Evaluation events, Track, numeric metrics [S1][S11] | First release / different contract | FeatBit deduplicates using its experiment metadata and flush groups; no promise of identical LaunchDarkly statistical semantics. |
| A02 | Additional LDValue data in trackData/trackMetric [S1] | Not provided (first release) | Additional JSON data is explicitly unnecessary for now. Track retains event name and optional number (default 1.0), without an extra JSON payload parameter. |
| A03 | Event disablement, capacity, periodic flush configuration [S11][S12] | First release | disableEvents defaults false; disables collection/delivery without disabling flag synchronization or logs. |
| A04 | Flush, Close [S1][S2] | First release / different contract | LaunchDarkly exposes void Close without an asynchronous close-result handle. FeatBit retains bounded completion results and requires cleanup/result settlement to progress despite ordinary completion overload or blocked callbacks. A preallocated close slot is an implementation option, not a public contract. Do not copy void flush signatures or infer equivalent completion guarantees. |
| A05 | privateAttributes / allAttributesPrivate [S11] | First release / different contract | SDK analytics custom-attribute filtering only: named/all filtering, default off, complete synchronization context retained. Server changes may follow; no promise that attributes are not persisted. See plan.md for EndUser overwrites and synchronization restoration limits. |
| A06 | Diagnostic telemetry, diagnosticOptOut, sampling interval [S3][S11] | Not provided (first release) | Telemetry and opt-out are excluded. No diagnostic upload or sampling configuration; retain safe local logs, failure states, and loss counters. |
| A07 | logAdapter and log levels [S3] | First release | Disable/replace logger, with exception isolation, rate limiting, and sensitive-data protection. |
| X01 | dataSource(ComponentConfigurer<DataSource>) [S3][S13] | First release / different result contract | Public factory, cooperative lifecycle and controlled update sink. FeatBit submissions use immutable typed models and one standard asynchronous commit result, without runtime schema negotiation or a separate receipt/timeout protocol. Model evolution follows SDK API compatibility; record timestamps/cursors retain their ordering roles. Custom replaces built-in synchronization and does not automatically join built-in fallback/recovery. |
| X02 | PersistentDataStore interface and package-private configuration [S3] | No public replacement | Upstream configuration is explicitly test-only. FeatBit retains internal substitution; cache configuration and clearing remain public. |
| X03 | Package-private PlatformState [S14] | No public replacement | Internal fake platforms and host hooks remain; do not expose the entire platform. |
| X04 | TestData / FlagBuilder dynamic test data [S15] | First release / different contract | Java/Kotlin local source in the core AAR, supporting initial values, updates/deletion, and subscriptions. One active client per binding; no targeting simulation, networking, analytics, or production flag-cache access. |
| X05 | Hooks, addHook [S1][S3] | Not provided (first release) | Evaluation/Identify operation hooks excluded; flag subscriptions and host lifecycle hooks remain in scope. |
| X06 | Plugins, registerPlugin [S1][S3] | Not provided (first release) | General plugins excluded. Custom sources remain, without implying third-party observability plugin support. |
| X07 | Custom EventProcessor configuration [S3] | Not provided (first release) | Keep an internal substitution boundary for tests, without public processor interfaces, factories, or injection settings. Built-in events, disableEvents, capacity, flush intervals, private filtering, and headers remain supported. |

## 5. Remaining decisions and accepted scope

The following guidance does not create additional first-release commitments:

1. X04 public TestData and E07 runtime online transitions are included; E02 integer APIs are excluded. None remain pending.
2. A05 private filtering is SDK-first; later server changes are not prerequisites. A02 additional event data and A06 telemetry/opt-out are excluded. None remain pending.
3. Decide E10 manual application metadata according to customer needs. E12 connection information/SDK version and S08 custom headers are included; wrapper metadata is excluded. E08 unified multi-environment management, X05 Hooks, X06 Plugins, and X07 custom event processors are excluded from the first release and no longer pending.
4. Anonymous keys, automatic attributes, and background polling are explicitly included. Apply plan.md enablement rules and P05, A01/A02, M23/M29 acceptance requirements; inclusion does not imply default enablement.

FeatBit exposes `bootstrap(flags)` in the first release: immutable all-user application defaults, with no public scope type or context-bound variant. Creation prefers configured bootstrap; after Identify/anonymous transitions, prefer the target full-context cache (including a coherent empty snapshot) and use bootstrap only on cache miss/unavailability. Preserve existing Identify isolation and remote completion requirements. This is a FeatBit scope decision, not a claim of LaunchDarkly Android bootstrap equivalence. Cache clearing, deferred persistent events, multiprocess limitations, and Kotlin/Java consumer matrices remain as planned. This table does not label unverified upstream counterparts as missing; specifically, deferring FeatBit persistent events does not establish that upstream supports them.

During acceptance, add implementation and test links for every included row. Pending, deferred, and excluded rows do not count as passed. Claim alignment only after verifying concrete semantics and tests, never through a blanket claim of complete LaunchDarkly equivalence.

## 6. Evidence pinned to the source commit


[S1]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/LDClientInterface.java
[S2]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/LDClient.java
[S3]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/LDConfig.java
[S4]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/StreamingDataSource.java
[S5]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/integrations/PollingDataSourceBuilder.java
[S6]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/DataSystemComponents.java
[S7]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/FDv2DataSourceConditions.java
[S8]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/integrations/DataSystemBuilder.java
[S9]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/ContextDataManager.java
[S10]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/integrations/HttpConfigurationBuilder.java
[S11]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/integrations/EventProcessorBuilder.java
[S12]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/Components.java
[S13]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/subsystems/DataSource.java
[S14]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/PlatformState.java
[S15]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/integrations/TestData.java
[S16]: https://github.com/launchdarkly/android-client-sdk/blob/0f40e44b989d46e7d4a8c0c0475029c85c5bbe08/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/PersistentDataStoreWrapper.java
