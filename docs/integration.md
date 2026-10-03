# Android integration

This is a Kotlin implementation with Java-compatible public APIs. The local development
artifact is `co.featbit:featbit-client-android:0.1.0-SNAPSHOT`; it is not a published Central
release. Use the isolated Maven repository printed by `tools/acceptance.py` for
local integration. Android API 21+ and Java 11 bytecode are required. Preserve the merged
AndroidX Startup process-lifecycle initializer. INTERNET and ACCESS_NETWORK_STATE are
ordinary manifest permissions. No foreground service, wake lock or battery exemption is needed.

## Installation and ownership

```kotlin
repositories { maven { url = uri("/absolute/path/to/acceptance/repository") }; google(); mavenCentral() }
dependencies { implementation("co.featbit:featbit-client-android:0.1.0-SNAPSHOT") }
```

Retain an application-scoped client. Do not construct one per evaluation or Activity rotation.
Creation is asynchronous and separate from remote readiness. `Outcome.isSuccess` must be
checked before using `value`; errors carry safe diagnostic codes. Callbacks/listeners run on
Android main; keep them short. Do not block main waiting for a callback. Kotlin adapters
use the same operations; coroutine cancellation detaches its wait, not the underlying work.

Java (imports from `co.featbit.android.api`):

```java
User user = User.builder("example-user").name("Example User").build().getValue();
Outcome<ClientOptions> options = ClientOptions.builder().user(user).sdkKey("CLIENT_SDK_KEY")
    .streamingUrl("wss://evaluation.example.com")
    .eventsUrl("https://events.example.com").build();
if (!options.isSuccess()) throw new IllegalArgumentException("Invalid SDK configuration");
ClientFactory.getDefault().create(applicationContext, options.getValue()).observe(created -> {
    if (!created.isSuccess()) return; // handle created.getCode()
    FeatBitClient client = created.getValue(); // retain in your application owner
    client.awaitReady(5000).observe(ready -> {
        boolean enabled = client.boolVariation("new-checkout", false);
        // A timeout settles this wait; background synchronization may recover later.
    });
});
```

Kotlin (inside an application-owned coroutine):

```kotlin
val user = User.builder("example-user").name("Example User").build().value!!
val options = ClientOptions.builder().user(user).sdkKey("CLIENT_SDK_KEY")
    .streamingUrl("wss://evaluation.example.com")
    .eventsUrl("https://events.example.com").build()
check(options.isSuccess)
val adapters = ClientAdapters.getDefault() // co.featbit.android.kotlin
val created = adapters.await(ClientFactory.getDefault().create(applicationContext, options.value!!), 6000)
check(created.isSuccess)
val client = created.value!!
val ready = adapters.await(client.awaitReady(5000), 6000)
val enabled = client.boolVariation("new-checkout", false)
```

Never put server SDK keys in a mobile app. Example credentials here are fictional.
The service evaluates targeting, segments and experiment assignment; this SDK reads those
results locally. Reads and state/version queries perform no network/disk I/O.

## Reads, status and subscriptions

Typed reads have detailed counterparts with MATCH, CLIENT_NOT_READY, FLAG_NOT_FOUND,
WRONG_TYPE or ERROR. Generic `variation` returns immutable `FbValue` according to the
declared type. JSON helpers preserve JSON null; `jsonTextVariation` returns raw text.
`allVariations` returns an immutable snapshot and does not collect evaluation events.

```java
String version = client.getVersion(); // also SdkInfo.getVersion(), without creating a client
ConnectionInformation state = client.getConnectionInformation();
Registration status = client.subscribeStatus(next -> renderStatus(next)).getValue();
ChangeSubscription changes = client.subscribeFlag("new-checkout", change -> renderFlag()).getValue();
// Render changes.getInitialValues() to avoid a separate initial-read/registration race.
changes.getRegistration().close();
status.close();
```

```kotlin
val version = client.getVersion()
val state = client.getConnectionInformation()
val statusJob = scope.launch { adapters.status(client).collect { renderStatus(it) } }
// Cancel when the UI stops observing. Cancellation removes this subscription.
statusJob.cancel()
```

`configuredMode` is intent; `effectiveMode` includes fallback/background changes. Pause reasons
may coexist. Local data availability is independent of remote confirmation: cache, Bootstrap
and TestData cannot confirm the service. Nullable success/failure timestamps belong to the
current user context; Identify resets them. `candidateFailure` is separate from authoritative
Polling failure. Event errors do not change synchronization status. Queries after Close remain
available; they are not service health checks. Status streams may coalesce intermediate states.

## Identity, local storage and mode changes

```java
client.identify(User.builder("signed-in-user").name("Signed In").build().getValue(), 5000)
    .observe(result -> handleIdentityResult(result));
client.setOffline(5000).observe(result -> handleModeResult(result));
boolean offline = client.isOffline();
client.setOnline(5000).observe(result -> handleModeResult(result));
```

```kotlin
val changed = adapters.await(client.identify(nextUser, 5000), 6000)
val offline = adapters.await(client.setOffline(5000), 6000)
val online = adapters.await(client.setOnline(5000), 6000)
val synchronized = adapters.await(client.awaitReady(5000), 6000)
```

Offline creation permits missing endpoints/key; switching online then fails without suitable
configuration and starts no requests. Online completion is not readiness. Offline suppresses
new analytics and retains already accepted events within their limits. Old responses cannot
revive prior waits or change the new context. Background/network pauses remain independent;
online/Identify cannot reset a terminal subsystem. Create a new client to retry terminal failure.

Endpoint schemes are case-insensitive: Streaming accepts `ws`/`wss`, while Polling and
events accept `http`/`https`. Configuration, online transitions, request construction and
cache namespace matching share this policy. Scheme casing does not create a separate cache;
deployment path casing and escaping remain significant and are preserved. Endpoints require
a host and must not contain user-info, a query or a fragment. These requirements are checked
for enabled online paths; offline creation still defers endpoint validation until going online.

Enable `anonymousEnabled(true)` explicitly. An explicit user wins; otherwise a random stored anonymous
key is used. Login uses `identify(user, timeout)`; logout/return uses `identifyAnonymous(timeout)`;
`resetAnonymousIdentity(timeout)` explicitly rotates it. These methods return Operations in Java
and use `adapters.await` in Kotlin. No hardware-derived identifier or inferred account linking is used.

Cache and anonymous files live under `noBackupFilesDir`; ordinary Android backup excludes them.
They are not encrypted by this SDK. Cache defaults on, matches the complete context/environment,
and retains five contexts per namespace using LRU. There is no byte cap or age expiry. Bootstrap
wins at creation; target-context cache wins after Identify. Neither confirms online readiness.
`clearCache(CURRENT_CONTEXT or NAMESPACE, timeout)` preserves current memory and anonymous identity;
later valid commits may repopulate disk. Disable with `cacheEnabled(false)`.

Enable `automaticAttributes(true)` explicitly. It adds string attributes under `featbit.sdk.`:

| Suffix | Value |
| --- | --- |
| applicationId | Android package name |
| applicationVersion | Application versionName, when available |
| osName | Android |
| osVersion | Build.VERSION.RELEASE |
| deviceManufacturer | Build.MANUFACTURER |
| deviceModel | Build.MODEL |
| version | SDK artifact version |

Unavailable/empty values are omitted. Caller keys with this prefix are rejected when enabled.
Identify resamples; foregrounding alone does not. Old events retain old attributes and cache
matching includes the effective attributes. Disabled collection does not inspect these fields.

## Headers, privacy and events

Both builders expose the same methods in Java/Kotlin:

```java
builder.synchronizationHeader("X-Gateway-Key", "fictional-sync-value")
    .eventHeader("X-Gateway-Key", "fictional-event-value")
    .privateAttribute("email").privateAttribute("featbit.sdk.deviceModel");
```

```kotlin
builder.synchronizationHeader("X-Gateway-Key", "fictional-sync-value")
    .eventHeader("X-Gateway-Key", "fictional-event-value")
    .allAttributesPrivate(true)
```

Headers are static for the client's lifetime. Duplicate names are case-insensitive; invalid
characters and protocol-owned names (Authorization, Host, Content-Type, Content-Length,
Connection, Upgrade, User-Agent, X-User-Agent, Transfer-Encoding, Accept-Encoding and
Sec-WebSocket-*) are rejected. Synchronization/event scopes are independent. No redirects,
including same-origin redirects, are followed. Custom sources cannot configure built-in
synchronization headers/endpoints; event headers remain independent. No wrapper metadata API exists.

Privacy filtering defaults off and uses exact attribute names. It applies before event retention
and deduplication, including automatic attributes. Key/name, variation and metric fields remain.
Synchronization and cache retain complete attributes. Filtering is not database erasure: service
EndUser profile overwrite and later synchronization can restore attributes. Empty filtered
collections must remain accepted by the target service.

Events default on and require eventsUrl online. `disableEvents(true)` is creation-time only:
no event construction, queue, delivery or periodic retry; Track is suppressed and Flush reports
DISABLED. Flag synchronization, Identify, cache and subscriptions still work. Offline and logging
settings are separate controls.

`track("checkout")` / `track("purchase", 12.5)` returns an Outcome. Identical calls deduplicate
within a group, keeping the first timestamp. Events are memory-only: process death loses them.
`flush()` covers outstanding accepted work at call time. ALL_DELIVERED, EMPTY and
PROCESSED_WITH_LOSS differ from DEFERRED, DISABLED, timeout and terminal errors. A timeout
detaches the wait, not delivery. Uncertain retries can duplicate delivery; there is no exactly-once
guarantee. `close()` attempts bounded final delivery and reports undeliveredEvents/cleanupComplete;
after closing, reads remain available, and no new work is authorized.

## Defaults and lifecycle

| Setting | Default / boundary |
| --- | --- |
| mode / offline | Streaming / false |
| pollingFallback / backgroundPolling | false / false |
| anonymous / automaticAttributes | false / false |
| disableEvents / private attributes | false / no filtering |
| cacheEnabled / transitionFlush | true / false |
| startup wait / request timeout / close timeout | 5 s / 10 s / 5 s; minimum 1 ms |
| foreground polling / flush interval | 30 s / 30 s; minimum 1 s |
| background polling interval | 15 min; minimum 15 min |
| Flag grace | 0; minimum 0 |
| eventCapacity | 10,000; range 1–100,000 |
| logLevel | WARN |

Durations have no fixed upper cap. Event retention also has an 8 MiB encoded budget, 256 groups,
50 events/256 KiB per batch, one physical send, three attempts and 24 elapsed hours maximum age.

Explicit background polling requires pollingUrl and is still subject to OS execution/network
opportunities. Foreground restores explicit Polling, original Streaming or its retained fallback
mode. Grace applies only to already dispatched Flag work; optional transition flush has an
independent two-second maximum. Doze suspends permission, no timers catch up, and ordinary
analytics delivery stays paused in background even when polling is enabled. See [platform details](./phase-6.md).

## TestData and custom sources

```java
TestData data = TestDataFactory.getDefault().create(Collections.singletonList(
    BootstrapFlag.create("new-checkout", "true", ValueType.BOOLEAN).getValue())).getValue();
ClientOptions local = data.clientOptions(user).getValue();
// Create with ClientFactory as above; then subscribe and mutate:
data.update(BootstrapFlag.create("new-checkout", "false", ValueType.BOOLEAN).getValue());
data.remove("new-checkout"); // reads fall back; update again to restore
```

```kotlin
val data = TestDataFactory.getDefault().create(listOf(
    BootstrapFlag.create("new-checkout", "true", ValueType.BOOLEAN).value!!)).value!!
val local = data.clientOptions(user).value!!
// Create as above. Observe operation results, including SAVED_FOR_NEXT_START while paused.
val removed = adapters.await(data.remove("new-checkout"), 2000)
```

TestData is locally ready, without production cache, analytics or real networking. Bind to one
client at a time; Close permits reuse. It does not simulate server targeting/experiments. Closing
UI subscriptions is separate from closing the application client. Explicit offline is a mode of
a normal client; TestData is a local source.

For working custom-source implementations compiled against the actual AAR, see
[Java LocalFactory](../consumer-tests/java/src/main/java/co/featbit/consumer/java/ModelSmoke.java)
and [Kotlin LocalFactory](../consumer-tests/kotlin/src/main/kotlin/co/featbit/consumer/kotlin/ModelSmoke.kt).
Use `source(factory)`; implement the public datasource contracts only. Honor start/stop,
session-bound sinks, coherent full/patch commits, error containment and resource ownership.
Late sinks after Identify/pause/Close have no authority. LOCAL sources cannot confirm remote
readiness; REMOTE cache needs a deployment/source/format-specific cacheDiscriminator and SDK key.
Built-in fallback/recovery is inapplicable. Public store/platform replacement is unsupported.

`sink.submit(update)` prepares and commits synchronously on the calling thread and returns an
already-settled `Operation<SourceUpdateResult>`. Read `SourceUpdateResult.code` to distinguish
`COMMITTED` from `INVALID`, `INACTIVE` or `CLOSED`; a successful outer Outcome alone does not
mean the update committed. The current sink does not emit the public `BACKPRESSURED` or
`SUPERSEDED` enum values. It has no update queue or submission admission sequence.
Non-overlapping calls retain call order; overlapping calls are ordered by actual commit.
Serialize submissions when upstream ordering matters, and submit large snapshots off the UI
thread. An await timeout cannot preempt the synchronous `submit()` call. Completion callbacks
run asynchronously on Android's main dispatcher and use the normal registration capacity;
registration rejection or detachment does not undo the commit or discard its queryable result.

## Diagnostics

Use `logLevel(LogLevel.NONE)` to silence logging, or `logger((level, diagnostic) -> ...)` in
Java / `logger { level, diagnostic -> ... }` in Kotlin. Loggers receive SDK-owned diagnostic
codes and safe fields, never external Throwable/messages, token URLs, credentials or raw users.
No logger is installed by default. Error Outcomes remain available with logging disabled.
Repeated codes are limited to once per 60 elapsed seconds (128 remembered codes);
loss accounting is independent of log frequency. Do not infer successful delivery from absence
of logs. Keep custom loggers nonblocking. See [verification and limitations](./phase-7.md).
