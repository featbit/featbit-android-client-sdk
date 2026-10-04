# FeatBit Client SDK for Android

[Kotlin](./README.md) | [Java](./README.java.md)

## Introduction

This is the client-side SDK for the open-source feature flag management platform
[FeatBit](https://github.com/featbit/featbit).

The SDK is implemented in Kotlin with Java-compatible public APIs and supports Android
API 21 and later. It is intended for a single-user context: FeatBit evaluates targeting
rules on the server, and your app reads the synchronized flag values locally.

> **Publication pending:** Version `0.1.0` has passed the recorded local and hosted
> checks, with physical-device and deployed-service validation confirmed by the maintainer.
> Maven Central publication and fresh-download verification remain pending. The local
> development build defaults to `0.1.0-SNAPSHOT`; use the local installation below for now.
> See the [0.1.0 release notes](./docs/release-notes-0.1.0.md) and
> [prepared Central installation instructions](./docs/release.md#maven-central-installation-after-publication).

## Get Started

### Installation

Build and publish the SDK to a local Maven repository using JDK 17 and Android SDK 34:

```sh
# Run from the SDK repository root.
bash gradlew :sdk:publishReleasePublicationToLocalTestRepository
```

On Windows, use `.\gradlew.bat` instead of `bash gradlew`. This creates the repository
under `build/test-repository/`; it does not publish to Maven Central.

Add that repository to your app's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("/absolute/path/to/featbit-android-client-sdk/build/test-repository") }
        google()
        mavenCentral()
    }
}
```

Then add the dependency to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("co.featbit:featbit-client-android:0.1.0-SNAPSHOT")
}
```

The SDK uses Java 11 bytecode. See the [integration guide](./docs/integration.md)
for Android manifest, lifecycle, and consumer setup details.

### Prerequisite

Before using the SDK, obtain your environment's **client-side secret** (`sdkKey`)
and SDK URLs. Do not embed a server-side secret in an Android app.

- [How to get the environment secret](https://docs.featbit.co/sdk/faq#how-to-get-the-environment-secret)
- [How to get SDK URLs](https://docs.featbit.co/sdk/faq#how-to-get-the-sdk-urls)

Streaming uses a `ws://` or `wss://` URL; polling and events use `http://` or `https://`.
The event URL is required for online clients unless event collection is disabled.
For local development, see [Android cleartext connections](./docs/integration.md#android-cleartext-connections),
including emulator host addressing and the app's network security policy.

### Quick Start

The following Kotlin example creates a streaming client, waits for initial remote
flag data, and evaluates a flag. Call it from an application-owned coroutine and
retain the returned client for reuse throughout the app.

```kotlin
import android.content.Context
import co.featbit.android.api.ClientFactory
import co.featbit.android.api.ClientOptions
import co.featbit.android.api.FeatBitClient
import co.featbit.android.api.User
import co.featbit.android.kotlin.ClientAdapters

suspend fun createClient(context: Context): FeatBitClient {
    val user = User.builder("a-unique-key-of-user").name("Bob").build()
    check(user.isSuccess) { "Invalid user: ${user.code}" }

    val options = ClientOptions.builder()
        .sdkKey("your_client_sdk_key")
        .streamingUrl("wss://evaluation.example.com")
        .eventsUrl("https://events.example.com")
        .user(user.value!!)
        .build()
    check(options.isSuccess) { "Invalid configuration: ${options.code}" }

    val adapters = ClientAdapters.getDefault()
    val created = adapters.await(
        ClientFactory.getDefault().create(context.applicationContext, options.value!!),
        6_000,
    )
    check(created.isSuccess) { "Client creation failed: ${created.code}" }
    val client = created.value!!

    val ready = adapters.await(client.awaitReady(5_000), 6_000)
    if (!ready.isSuccess) {
        // This wait failed or timed out. Inspect ready.code for the reason.
        // A timeout does not stop background synchronization.
    }

    val enabled = client.boolVariation("new-checkout", false)
    // Use enabled to select your checkout experience.
    return client
}
```

Client creation and remote readiness are separate operations. Builders and asynchronous
operations return `Outcome<T>`; check `isSuccess` before accessing `value`. Flag reads
return the supplied fallback when a usable value is unavailable.

Java applications use the same builders and callback-based `Operation.observe(...)`.
See the [Java README](./README.java.md) for a complete callback-based guide.
The remaining Kotlin snippets assume a retained `client`, a validated `user`, and
imports from `co.featbit.android.api` unless noted otherwise.

## Examples

- [Kotlin sample app](./samples/kotlin): local demo and live connections, user switching,
  flag evaluation, and custom events. Follow the [build and run guide](./samples/implementation-guide.md).
- [Java sample app](./samples/java): the same café design and shared resources, with Java callbacks.
- [Java integration examples](./docs/integration.md): callback-based usage of the public API.

## SDK

### FeatBitClient

`FeatBitClient` provides flag reads, synchronization state, subscriptions, identity
changes, and event delivery. Retain one application-scoped client for your active
user context; do not create a new client for each read or Activity rotation.

Use `ClientOptions.builder()` to configure the client and `ClientFactory.getDefault()`
to create it, as shown in Quick Start.

#### FeatBitClient Using Streaming

Streaming is the default synchronization mode:

```kotlin
val options = ClientOptions.builder()
    .sdkKey("your_client_sdk_key")
    .streamingUrl("wss://evaluation.example.com")
    .eventsUrl("https://events.example.com")
    .user(user)
    .build()
```

#### FeatBitClient Using Polling

```kotlin
val options = ClientOptions.builder()
    .sdkKey("your_client_sdk_key")
    .mode(SyncMode.POLLING)
    .pollingUrl("https://evaluation.example.com")
    .pollingIntervalMillis(30_000)
    .eventsUrl("https://events.example.com")
    .user(user)
    .build()
```

The default foreground polling interval is 30 seconds; the minimum is one second.

#### Streaming with Polling Fallback

Polling fallback is disabled by default. To enable it, supply a polling URL:

```kotlin
val options = ClientOptions.builder()
    .sdkKey("your_client_sdk_key")
    .streamingUrl("wss://evaluation.example.com")
    .pollingUrl("https://evaluation.example.com")
    .pollingFallback(true)
    .eventsUrl("https://events.example.com")
    .user(user)
    .build()
```

The SDK can fall back to polling when streaming fails and recover to streaming.
Use `client.getConnectionInformation()` to inspect the configured and effective modes.

#### User

`User` identifies the person whose flag values the SDK requests. Both `key` and `name`
must be nonblank. Add custom attributes with `attribute(...)`:

```kotlin
val country = AttributeValue.text("FR")
check(country.isSuccess)
val result = User.builder("unique-key-for-bob")
    .name("Bob")
    .attribute("country", country.value!!)
    .build()
check(result.isSuccess)
val user = result.value!!
```

Attributes can be used in targeting and are included in analytics unless filtered.
Use `privateAttribute("country")` or `allAttributesPrivate(true)` on the options builder
to filter custom attributes from events. These settings do not remove attributes from
synchronization requests or the local cache.

Anonymous identity is opt-in through `anonymousEnabled(true)`. See the
[identity guide](./docs/integration.md#identity-local-storage-and-mode-changes)
for login, logout, and anonymous identity reset behavior.

### Bootstrap

Provide initial flag values through `bootstrap(...)` when they are already available:

```kotlin
val flag = BootstrapFlag.create("new-checkout", "true", ValueType.BOOLEAN)
check(flag.isSuccess)
val options = ClientOptions.builder()
    .sdkKey("your_client_sdk_key")
    .streamingUrl("wss://evaluation.example.com")
    .eventsUrl("https://events.example.com")
    .user(user)
    .bootstrap(listOf(flag.value!!))
    .build()
```

Bootstrap values are available locally before remote synchronization. Remote data
replaces them when received. Bootstrap does not confirm online readiness.

### Logger

The default log level is `WARN`, but no logger is installed by default. Provide a
logger to receive SDK diagnostic codes:

```kotlin
val builder = ClientOptions.builder()
    .logLevel(LogLevel.WARN)
    .logger { level, diagnostic ->
        android.util.Log.d("FeatBit", "$level: ${diagnostic.code}")
    }
// Add user, SDK key, and endpoints before calling build().
```

Available levels are `NONE`, `ERROR`, `WARN`, `INFO`, and `DEBUG`. Set `NONE` to silence
logging. Diagnostics contain SDK-owned codes and safe fields; keep the logger
nonblocking. Operation outcomes remain available even when logging is disabled.

### Evaluation

Flag values are read locally and synchronously without network or disk I/O.
Each typed read has a detailed counterpart:

- `boolVariation` / `boolVariationDetail`
- `stringVariation` / `stringVariationDetail`
- `numberVariation` / `numberVariationDetail`
- `jsonVariation` / `jsonVariationDetail`
- `jsonTextVariation` / `jsonTextVariationDetail`

Use `variation` / `variationDetail` for generic `FbValue` values. All reads take a
flag key and a fallback value. Detailed reads include the reason, such as `MATCH`,
`CLIENT_NOT_READY`, `FLAG_NOT_FOUND`, or `WRONG_TYPE`.

```kotlin
val enabled = client.boolVariation("new-checkout", false)
val detail = client.boolVariationDetail("new-checkout", false)
val message = client.stringVariation("welcome-message", "Welcome")
val discount = client.numberVariation("discount", 0.0)
```

`allVariations()` returns a snapshot without collecting evaluation events.

### Offline Mode

Set `.offline(true)` on the options builder to start offline, or change an existing client:

```kotlin
client.setOffline(5_000).observe { result ->
    if (!result.isSuccess) {
        // Handle result.code.
    }
}
```

Offline clients use available in-memory, Bootstrap, or matching cached values;
otherwise reads return their fallbacks. Offline mode suppresses new analytics and
retains already accepted events within the queue limits.

Call `client.setOnline(5_000)` to resume online intent. This requires valid online
configuration; completion does not mean remote flag data is ready. Use `awaitReady`
when you need that confirmation.

### Events

#### Wait for ready

```kotlin
client.awaitReady(5_000).observe { ready ->
    if (ready.isSuccess) {
        val enabled = client.boolVariation("new-checkout", false)
        // Update application state.
    } else {
        // Handle ready.code, for example TIMED_OUT or TERMINAL_FAILURE.
    }
}
```

For a built-in online client, readiness requires remote confirmation. Offline and
local custom sources have distinct local readiness results. A timeout settles the
wait without stopping synchronization. Kotlin callers can use `ClientAdapters.await`,
as shown in Quick Start.

#### Subscribe to flag(s) changes

Subscribe to all flag changes or a particular flag:

```kotlin
val allChanges = client.subscribeChanges { change ->
    // Inspect change.keys and change.allFlagsChanged.
}
val checkoutChanges = client.subscribeFlag("new-checkout") {
    val enabled = client.boolVariation("new-checkout", false)
    // Update the checkout UI.
}
check(allChanges.isSuccess)
check(checkoutChanges.isSuccess)

val checkoutSubscription = checkoutChanges.value!!
val initialValues = checkoutSubscription.initialValues
// Render initialValues; it is captured when the subscription is registered.

// When the UI stops observing:
checkoutSubscription.registration.close()
allChanges.value!!.registration.close()
```

Callbacks and listeners run on Android's main thread; keep them short and never
block that thread waiting for completion. Registration can fail, so check the
returned outcome. `ClientAdapters` also provides `changes`, `flagChanges`, and
`status` flows. Closing a subscription does not close the client.

### Switch user after initialization

Use `identify` when the active user changes, for example after login:

```kotlin
val nextUser = User.builder("another-unique-key-of-user").name("Alice").build()
check(nextUser.isSuccess)
client.identify(nextUser.value!!, 5_000).observe { result ->
    if (!result.isSuccess) {
        // Handle result.code.
    }
}
```

Old synchronization responses cannot replace the new user's data. Identify changes
the complete user context; supply the attributes needed for the new user.

### Data synchronization

The SDK uses WebSocket streaming or polling to keep local flag values synchronized.
Persistent caching is enabled by default and is scoped to the environment and full
user context. Disable it with `.cacheEnabled(false)`.

Synchronization follows Android lifecycle, connectivity, and device-idle state.
Background polling is opt-in through `.backgroundPolling(true)` and requires a
polling URL. Its default and minimum interval is 15 minutes, subject to Android
execution and network availability. Normal event delivery pauses in the background.

### Network failure handling

When connectivity is temporarily lost, available local flag values remain readable.
The SDK retries recoverable connection failures and resumes synchronization when
permitted. A matching persistent cache can also supply values on a later start.
Cache and Bootstrap availability do not establish remote readiness.

Inspect `getConnectionInformation()` or `subscribeStatus(...)` to distinguish
local data availability, connection failures, and lifecycle pauses. See the
[integration guide](./docs/integration.md#reads-status-and-subscriptions) for details.

### Disable Events Collection

Evaluation and custom metric events are enabled by default for online clients.
To disable them, set `.disableEvents(true)` on the options builder before creation.
This removes the requirement for an event URL; flag synchronization and reads continue
to work. `track` is suppressed and `flush` reports `DISABLED`.

### Experiments (A/B/n Testing)

Evaluate the relevant flag before recording the associated experiment metric:

```kotlin
val enabled = client.boolVariation("new-checkout", false)
// Use enabled to show the assigned checkout experience.

// Call when the user completes the corresponding business action.
val tracked = client.track("purchase", 12.5)
if (!tracked.isSuccess) {
    // Handle tracked.code.
}
```

`track("purchase")` uses a default numeric value of `1.0`. Instrument your app's
business actions explicitly; the Android SDK does not automatically capture pageviews
or clicks. Events are queued in memory and can be lost if the process exits.

Use `client.flush().observe { result -> ... }` to request delivery of outstanding
accepted events. Inspect the outcome and flush result rather than assuming every
accepted event has been delivered.

### Close

When the application owner no longer needs the client, release its resources:

```kotlin
client.close().observe { result ->
    if (result.isSuccess) {
        val summary = result.value!!
        // Inspect summary.undeliveredEvents and summary.cleanupComplete.
    }
}
```

Close attempts bounded final event delivery. Do not close an application-scoped client
on Activity rotation, and do not rely on Android process termination to invoke Close.

## Getting support

- For SDK questions, bugs, or feature requests, [open an issue](https://github.com/featbit/featbit-android-client-sdk/issues/new).
- For FeatBit configuration questions, see the [SDK FAQ](https://docs.featbit.co/sdk/faq).

## See Also

- [Android integration and configuration](./docs/integration.md)
- [Kotlin and Java sample setup](./samples/implementation-guide.md)
- [Development notes and build commands (previous README)](./DEVELOPMENT.md)
- [Release preparation and remaining acceptance gates](./docs/release.md)
