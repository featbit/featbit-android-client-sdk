# Phase 4: Online synchronization

Implemented 2026-10-02. This is a Kotlin implementation with Java-compatible public APIs.
It extends the existing factories, immutable runtime, cache coordinator and independent
consumer projects. Public API signatures are unchanged.

## Implemented behavior

- `SyncProtocol` builds client-key tokens, prefix-preserving URLs, raw-key HTTP authorization,
  user JSON, data-sync messages and application-level ping. Wire timestamps are parsed as
  exact nonnegative `Long` values, without a Double intermediate. Unknown messages are ignored;
  invalid envelopes/duplicate keys are rejected; malformed sibling records are skipped.
  Flag bytes/counts remain uncapped; JSON nesting is limited to 64.
- `OkHttpSyncTransport` owns its resources, does not follow redirects or automatically retry
  HTTP requests, and bounds physical exchanges to four. Native handle attachment after
  invalidation is canceled. Incoming socket messages are decoded on OkHttp's ordered listener
  lane, not the Android main thread or the state gate. Serialization and request construction
  run on bounded effect workers. Dependencies: OkHttp 4.12.0 and Serialization JSON 1.6.3.
- `OnlineSync` shares the client's metadata gate. Every attempt has revocable authority;
  dispatch arbitration precedes native request creation, and late attachment honors revocation.
  Identify creates a replacement session even for the same key or A → B → A. Old messages,
  errors, closures and retry work cannot settle current waits or change current data.
- Streaming sends JSON ping every 18 seconds, reconnects after 36 seconds without received
  activity, and requires initial valid data within the configured request timeout. A handshake
  or ping is not readiness. `1000` reconnects; authorized `4003` terminates synchronization.
  HTTP `401`/`403` are terminal authentication/authorization failures. Other HTTP failures,
  including redirects, `408`, `429` and `5xx`, use paced recovery without following Location.
- Transient retries use exponential caps of 1–60 seconds with full jitter (minimum 1 ms),
  and no lifetime attempt limit. Valid Retry-After seconds/HTTP dates delay subsequent attempts.
  Initialization/Identify wait expiry does not stop current-context recovery. All scheduling
  uses elapsed time and saturated deadlines, not wall time.
- Polling requests are serialized, start promptly, carry complete users and paired cursors,
  and only accept `304` for the exact bound remote baseline. Late cache loads cannot become
  the baseline of a request that already captured cursor zero. Full responses replace data;
  equal-timestamp patches remain ordered; patches against changed baselines are rejected.
- Optional fallback starts after a continuous 30-second transient Streaming failure window.
  It is disabled by default. Valid data resets the window; suspension ends the current window.
  Fallback selection survives Identify/offline/background transitions. Explicit Polling never probes.
- Recovery freezes/fences Polling before one candidate, retaining readable data. After a
  60-second cooldown, a single 15-second candidate requests a full snapshot (cursor zero).
  Candidate commits validate the frozen baseline and atomically publish data and Streaming mode.
  Failed/expired/invalidated candidates resume Polling; repeated cooldowns grow to five minutes,
  resetting after 60 seconds of stable Streaming. Candidate failures remain separate from
  authoritative failures. Elapsed candidate deadlines cannot be extended by suspension or callbacks.
- Controlled foreground/network inputs implement grace, optional background Polling,
  foreground mode restoration, offline revocation and bounded cleanup. Background Polling
  defaults off and uses its separate interval. Real Android observers were subsequently
  connected in [Phase 6](./phase-6.md); these controlled-input results describe Phase 4.
- Synchronization headers apply to each handshake/reconnect/candidate/poll. Event headers
  never enter these requests. Diagnostics contain SDK codes, never payloads, credentials,
  token URLs or transport exception text.

## Usage

This Phase 4 example disables events to isolate synchronization. Phase 5 now supports enabled
analytics with eventsUrl configured; see [phase-5.md](./phase-5.md).

```java
ClientOptions options = ClientOptions.builder()
    .sdkKey("<client-sdk-key>")
    .user(User.builder("user-123").name("Example").build().getValue())
    .streamingUrl("wss://flags.example.com/deployment")
    .pollingUrl("https://flags.example.com/deployment")
    .pollingFallback(true)
    .disableEvents(true)
    .build().getValue();
ClientFactory.getDefault().create(context, options).observe(created -> {
    if (!created.isSuccess()) return;
    FeatBitClient client = created.getValue();
    client.awaitReady(5000).observe(ready -> {
        // Timeout ends this wait only; later synchronization can still succeed.
    });
});
```

Select `.mode(SyncMode.POLLING)` for explicit Polling (omit `pollingFallback(true)`).
Kotlin uses the same builders and `ClientAdapters.getDefault().await(operation, timeout)`.
Configured URLs are bases; the SDK appends `streaming` or
`api/public/sdk/client/latest-all` while preserving deployment prefixes.
The AAR declares INTERNET permission. The application controls cleartext policy; the SDK
does not relax it. Test consumer manifests permit cleartext solely for the localhost fixture.

## Target-server observations

Sources inspected on 2026-10-02:

| Repository | HEAD |
| --- | --- |
| sdk-spec | `3f08faa77dbf70bea208bd8ab946c2aa0b38ffad` |
| featbit-js-client-sdk | `210f4e6d4c032fd73d5bf9f16920507d645c2711` |
| featbit (evaluation-server) | `7ecc24aac0a5ad766f6843faabf0eaeb71f1b753` |

The target API returns empty HTTP `200` for an empty incremental Polling result. It can also
send no data for an unchanged incremental Streaming request. Neither is treated as new
confirmation. After invalid Polling data or initial Streaming data timeout, retry with cursor
zero for a full snapshot. Recovery candidates request full data immediately so unchanged
server state can still confirm takeover. Empty valid full envelopes do establish readiness.
The server accepts Android token generation, raw client-key HTTP auth and the SDK identifier
headers. Event compatibility was not tested in Phase 4; the subsequent Phase 5 contract checks
and current event behavior are documented in [phase-5.md](./phase-5.md).

## Reproducible verification

Normal unit/MockWebServer checks run with `:sdk:testDebugUnitTest`; live checks are excluded
unless `-PliveIntegration` is supplied. They never silently pass when the server is absent.

For the checked-in fictional service fixture, build the evaluation server with .NET 10, then
run its `src/Api/bin/Debug/net10.0/Api.dll` from `src/Api` with these process environment values:
`DbProvider=Fake`, `MqProvider=None`, `CacheProvider=None`, and
`ASPNETCORE_URLS=http://127.0.0.1:5189`. This does not write to the user's databases.

```powershell
.\gradlew.bat :sdk:testDebugUnitTest -PliveIntegration --tests '*LiveSyncIntegrationTest'
```

These tests use the public fictional key from `Domain/Shared/FakeSeedData.cs`. They exercise
real target HTTP/WebSocket code, evaluated fixture values, Identify and online/offline recovery.
The fallback test injects only initial Streaming unavailability and advances elapsed scheduling;
Polling and the recovered socket use the actual target service. This is not a distributed
database/MQ deployment or a real network partition test.

After staging the AAR and building independent consumers, optional emulator checks use:

```powershell
adb reverse tcp:5189 tcp:5189
adb shell am start -S -n co.featbit.consumer.java/.SmokeActivity --ez phase4 true
adb shell am start -S -n co.featbit.consumer.kotlin/.SmokeActivity --ez phase4 true
adb logcat -d -s FeatBitConsumer:I '*:S'
```

Install each debug APK first. Each opt-in run must emit `PHASE4_PASS`; regular consumer
launches continue to run their existing local/persistence checks. Remove only the test reverse
mapping afterward with `adb reverse --remove tcp:5189`, and stop the test service process.
Actual outcomes and acceptance limitations are recorded in [verification.md](./verification.md).
