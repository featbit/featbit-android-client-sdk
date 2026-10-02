# Phase 2: local runtime

Implemented 2026-10-01. This document records the historical Phase 2 local SDK milestone.
Cache and anonymous-storage limitations below were subsequently resolved by
[Phase 3](./phase-3.md), built-in networking by [Phase 4](./phase-4.md), events by
[Phase 5](./phase-5.md), and Android platform observers by [Phase 6](./phase-6.md).
Later-phase boundaries below describe what was unavailable at Phase 2, not the current SDK.
See [verification](./verification.md) for current evidence and remaining acceptance gaps.

## Public entry points

The three existing interfaces now expose additive Java-static `getDefault()` factories:

```java
TestData data = TestDataFactory.getDefault().create(Collections.singletonList(
    BootstrapFlag.create("enabled", "true", ValueType.BOOLEAN).getValue())).getValue();
ClientFactory.getDefault().create(context.getApplicationContext(),
    data.clientOptions(user).getValue()).observe(created -> {
        if (!created.isSuccess()) return;
        FeatBitClient client = created.getValue();
        client.awaitReady(5000).observe(ready -> {
            if (ready.isSuccess()) {
                boolean enabled = client.boolVariation("enabled", false);
                // Keep the client for application use; close when its owner is finished.
            }
        });
    });
```

Kotlin uses the same factories and `ClientAdapters.getDefault().await(operation, timeout)`.
Creation returns the client after local setup and source validation; use `awaitReady` to
wait for a source commit. Default callbacks run on Android's main thread. Polling
`getResult()` is thread-safe and remains available after close. Cancelling an observation
or suspend wait detaches that observer without cancelling shared work.

`TestData.clientOptions(user)` fixes events and production cache off. Explicit conflicts
are rejected. One TestData instance binds to one client until close settles. All users
share its saved values; offline changes are saved for the next start. Updates use full
local snapshots, so equal milliseconds and wall-clock rollback cannot discard a mutation.
Unchanged flags preserve their timestamps. Removal and re-addition are supported.

## Implemented contracts

- Immutable atomic result views, full replacement, equal/newer patches, stale-patch
  rejection, archives, remote cursor and SDK-issued exact-baseline confirmation.
- Bootstrap local defaults, per-context shadowing and reset at Identify. Cache is
  unavailable in this phase; no production cache reads or writes occur.
- Boolean, decimal Double, string, generic declared-type, parsed JSON and raw JSON-text
  reads; raw-string read-all; reasons, fallback and server explanation preservation.
  Typed conversion does not reject solely by declared type. Raw JSON text is returned
  unchanged, including malformed text; the parsed helper validates JSON. JSON null succeeds.
- Identify invalidates old sessions for same-key changes and A→B→A; no previous-user
  data remains visible. New sources receive immutable user/context snapshots.
- Bounded readiness/identity/mode waits, offline/online intent, terminal-state fencing,
  fake foreground/network inputs, retained reads after close and shared idempotent close.
- Public Full/Patch/NoChange sink results. Preparation runs outside the state gate;
  publication revalidates the captured view and session. Submission currently completes
  synchronously on the caller's thread and returns an already-settled Operation, without an
  update queue. Overlapping calls are ordered by actual commit; non-overlapping calls retain
  order. Completion callbacks are asynchronous; large submissions should run off the UI thread.
- Custom create/start/stop on two fixed workers with a 64-entry queue; no replacement
  threads for blocked extensions. Failed scheduling or extension exceptions produce safe
  status; uncooperative stop produces `cleanupComplete=false` within the close budget.
- Global/key/status listeners, atomic initial change snapshots, bounded key-union
  coalescing, registration closure and exception containment. Flow cancellation unregisters;
  runtime close completes Flows. Global change Flow uses full invalidation under conflation.
- Bounded result registrations, separate close registration capacity, and safe asynchronous
  rate-limited logs. Detached already-queued callbacks retain a delivery slot until drained,
  preventing repeated attach/detach from accumulating unbounded main-thread work.
- Opt-in automatic attributes sampled from application context; only application context
  is retained. Anonymous preparation/adoption has an internal asynchronous repository port,
  tested for supersession, failure and revision fencing. No unpersisted anonymous key is
  exposed as a restart-stable identity.

Budgets use Android elapsed realtime, including sleep, and are checked at completion as
well as by one 50 ms deadline ticker/client. OS scheduling can delay observation. Flag
timestamps and displayed success/failure times use wall time. No transport or event sender
was started by the Phase 2 implementation.

## Historical Phase 2 boundaries

Built-in online creation returns `DISABLED / builtin_transport_unavailable`; it does not
simulate remote readiness. An offline client may be created without endpoints; setOnline
first validates the frozen configuration and retains offline intent on failure. Online
Custom requires disabled events in this phase (`events_unavailable` otherwise).

Cache clearing returns `DISABLED / cache_persistence_unavailable`. Initial anonymous-only
creation and public anonymous transitions without a repository return
`anonymous_persistence_unavailable`; disabled anonymous mode returns `anonymous_disabled`.
Actual cache/anonymous persistence belongs to Phase 3. Cache-hit/valid-empty-cache races
and persisted-input corruption remain Phase 3 tests, not passed by the local fixtures.

Built-in transport, grace/background-polling execution, real lifecycle observers, event
collection/sending and event-admission revalidation belong to Phases 4–6. Local lifecycle
inputs currently pause Custom immediately. Reads linearize at one immutable view; Phase 5
must retain that boundary when adding event eligibility/admission. With no sender/queue,
disabled or offline Track is suppressed and Flush is empty; other unavailable paths are
explicit. Close has no final event flush in this phase.

## Conversion and input policy

Select on-demand conversion for now. Store raw strings, and parse only the requested type
outside the state gate. No parsed-value cache or eagerly retained object tree. Double uses
IEEE-754 precision; complete decimal grammar rejects hexadecimal, suffixes and nonfinite
results. The dependency-free JSON reader accepts objects, arrays, scalars and null, with
64 levels, with no parsed-node count limit; duplicate object properties use their last value.

Flag inputs have no SDK-imposed byte or record-count caps, including Bootstrap,
Full/Patch updates, TestData, metadata and JSON text. Duplicate Bootstrap keys remain
invalid; TestData reports `duplicate_flag_key`, and the configuration builder retains
`invalid_configuration`. Update envelopes retain their existing validity checks.
JSON parsing retains the depth limit above. Unknown types and malformed selected strings remain raw readable data;
conversion failure is local to the requested read. There is no whole-store history or
tombstone eviction policy; patch replaces current records.

The Kotlin consumer's `ANDROID_PROBE` compares 5,000 JSON records on the connected API 34
emulator. One debug run observed: startup 573 ms; first parsed read 0.51 ms; 5,000 repeated
parses 805 ms; parsing all records 718 ms; reading a caller-retained parsed object 1.74 ms.
Process used-heap snapshots were 3.24 MB before setup, 9.67 MB with raw records,
20.90 MB after repeated parsing and 11.12 MB after collecting all parsed results.
GC occurred between samples: these are noisy whole-process observations, **not** retained
SDK heap sizes or a benchmark guarantee. They support avoiding mandatory eager parsing;
hot-read caching could reduce repeated cost but would need its own bounded policy and
representative device measurements. The JVM probe provides a repeatable comparison entry
point, not Android performance evidence. These observations do not establish a maximum supported data size or guarantee
performance on every device or live service workload.

## Fixtures and verification

`sdk/src/test/resources/fixtures/v1/conversions.tsv` is a versioned local fixture, exercised
by `LocalRuntimeTest`; it is not a nonexistent shared spec test suite. Baseline is sdk-spec
`3f08faa77dbf70bea208bd8ab946c2aa0b38ffad`. The adjacent checkout has pre-existing edits in
`spec/conformance.md`, `spec/identity.md`, `spec/public-api.md` plus untracked Chinese mobile
documents. English specification files and this repository's plan/architecture were read;
no adjacent repository was modified. This is not conformance to an immutable published spec.

See [verification](./verification.md) for current build, consumer and device evidence.
