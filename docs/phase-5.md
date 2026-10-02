# Phase 5: Events, Flush and Close

Implemented 2026-10-02 in the existing Kotlin runtime with Java-compatible public APIs.
Public signatures and types are unchanged. Real Android lifecycle/network observers were
subsequently connected in [Phase 6](./phase-6.md); the results below describe Phase 5.

## Collection and privacy

- Successful individual remote evaluations collect only after current-context online confirmation.
  Conversion runs outside the state gate; the immutable view is checked again before admission,
  so Identify cannot mix an old result with a new user. Bootstrap, local Custom/TestData,
  fallback, failed conversion and bulk reads do not produce evaluation events. Remote Custom
  metadata follows the same validation as built-in remote data. Synchronization terminal failure
  does not independently disable events.
- Track works before readiness, defaults to numeric value 1.0 and requires a non-empty name
  and a finite value. EventProtocol does not enforce server-side field formats or individual
  length limits for event names, flag keys, variation IDs, users or custom attributes.
  Values are preserved without trimming, rewriting or case normalization.
- Events capture the call-time user, selected variation, value and timestamp. Named private
  attributes or all custom attributes are removed before queueing, hashing or retaining data.
  This includes automatic `featbit.sdk.*` attributes. Key/name are retained; sync/cache contexts
  remain complete. Filtering does not promise server-wide erasure or stop later profile updates.
- Selected variation metadata must uniquely map the raw value to an ID. Missing or ambiguous
  mappings and duplicate IDs suppress the event without changing the evaluated result.
  IDs are opaque strings compared exactly; UUID syntax is not required. Existing public-model
  validity checks, privacy filtering and total event/batch resource budgets remain in place.

## Queue, groups and delivery

- In-memory only: configurable event count, 8 MiB encoded-content budget including open dedup
  keys and a reserved batch, and 256 nonempty groups. JVM object overhead is additional but
  bounded by those counts. New unique events are dropped when full; duplicates remain duplicates.
- Deduplicate the complete filtered payload except timestamp, with canonical attribute ordering,
  keeping the first timestamp. Evaluation and metric payloads stay distinct. Explicit/periodic
  flush, background/foreground, offline and Close seal groups. Identify alone does not seal.
  Identical Track calls within a group may produce only one event. No missed-timer catch-up groups.
- At most 50 events / 256 KiB per batch and one physical HTTP attempt. Batches retain original
  payloads across retries and mode changes. At most three attempts; retry jitter is 1–1000 ms
  then 1–2000 ms, with Retry-After seconds/HTTP dates honored up to the 24-hour event age limit.
  Invalidated requests retain their physical slot until their completion/cancellation callback.
  A revoked dispatched attempt consumes its attempt budget and uses a 1 s / 2 s resumption delay.
- Each request uses requestTimeoutMillis, event-only headers, raw SDK-key authorization and Android
  SDK identification. URLs are bases: append `api/public/insight/track`, preserving prefixes.
  Redirects and implicit transport retries are disabled; response bodies are not retained.
- Any 2xx acknowledges a batch. 400/408/429, 3xx, 5xx and transport failures receive bounded
  retry. Exhaustion loses only that batch. Other 4xx permanently stop this instance's event
  collection/delivery, finalize the queue and fail waiting/subsequent Flush operations.
  Network recovery, Identify and online transitions cannot clear the terminal event state.
- Event age is at most 24 elapsed hours, including offline time. Expiry invalidates the affected
  attempt before finalizing retained work. Uncertain delivery retries can duplicate server events.

## Flush, offline and closure

`flush()` seals the open group and covers outstanding accepted work through its admission
high-water mark, including in-flight events. Later events do not extend the wait. It uses
requestTimeoutMillis as its wait budget. Results are EMPTY, ALL_DELIVERED or PROCESSED_WITH_LOSS;
disabled, deferred, terminal, closed, capacity and timeout outcomes use OutcomeCode.
Concurrent Flush calls independently observe shared outcomes; loss is counted once per event.
Completed history and capacity-rejected calls are outside later Flush coverage. A timeout detaches
only the wait; delivery continues and a later Flush can cover remaining work.

Offline suppresses new collection and sends, retains accepted queues within original limits,
invalidates in-flight authority and settles existing Flush waits as DEFERRED. An offline Flush
is EMPTY when no work remains, otherwise DEFERRED. Resume does not require resynchronizing old
events; new evaluation events still require confirmation in the new online period. Disabled
events return DISABLED for Flush and never create payloads, transport, sends, retries or loss counts.

Controlled lifecycle inputs seal at background and foreground, collect while backgrounded and
pause ordinary delivery. Optional transition flushing has two seconds and covers only pre-background
work. Late acknowledgements/errors cannot finalize retained work after revocation. Actual Android
observer wiring and platform suspension/Doze validation are Phase 6 work.

Close freezes final coverage and stops admission, using its single finite budget for delivery and
cleanup. Offline/disabled/terminal conditions prohibit final sending. Undelivered final-coverage
events are counted in CloseResult; missing physical cancellation acknowledgement makes cleanup
incomplete at the deadline. Repeated Close observes the same result. Caller-owned resources are
not closed. Process death can lose queued events; there is no durable event queue.

Local Diagnostics retains cumulative event counts for capacity, invalid metadata, expiry,
retry exhaustion, terminal failure and Close loss. Logger Diagnostic.field contains the decimal
cumulative count for these codes. Logging may be throttled, but counting is not; Close schedules
final totals on the existing bounded diagnostic worker. No raw user, event, credential or exception
content is logged. Without an enabled logger, these internal counters are not a public telemetry API.

## Target contract and verification

Inspected `featbit` commit `7ecc24aac0a5ad766f6843faabf0eaeb71f1b753`, JS SDK
`210f4e6d4c032fd73d5bf9f16920507d645c2711`, and sdk-spec
`3f08faa77dbf70bea208bd8ab946c2aa0b38ffad`. Unlike the older handoff note, the target service
has already removed `sendToExperiment` from VariationInsight and generates FlagValue messages
without it. JS/shared core text still refers to the retired field; Android follows the accepted
field-free architecture. MetricInsight accepts `appType: Android` and `type: CustomEvent`.
No adjacent repository source was changed.

The target InsightController applies its own field validation and returns 200 even when every
payload is invalid. Android deliberately does not duplicate those field-format rules; a 2xx
acknowledges the HTTP batch, not downstream persistence of every possible input. The live test therefore
exports actual Android wire payloads, then `tools/event-contract` references the target Domain
project and checks IsValid plus actual message conversion. This verifies one evaluation and two
Track messages, privacy removal and Android applicationType, beyond merely receiving HTTP 200.

Use the Fake/None service configuration and startup instructions in [phase-4.md](./phase-4.md).
With .NET 10 and the service listening on localhost:5189:

```powershell
.\gradlew.bat :sdk:testDebugUnitTest -PliveIntegration
& "$env:USERPROFILE\.dotnet\dotnet.exe" run --project tools/event-contract/EventContract.csproj -- sdk/build/phase-5-target-payload.json
```

Normal SDK tests exclude live suites. Explicit live tests fail if the service is absent. The
contract tool's EvaluationServerDir MSBuild property can point at another target checkout.
Independent published-AAR Java/Kotlin consumers opt in with Activity extra `phase5=true` and
must log PHASE5_PASS. Results and unverified deployment/device boundaries are in
[verification.md](./verification.md).
