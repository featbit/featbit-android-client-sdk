# FeatBit Client SDK for Android — Java

[Java](./README.java.md) | [Kotlin](./README.md)

## Introduction

This is the client-side SDK for the open-source feature flag management platform
[FeatBit](https://github.com/featbit/featbit).

The SDK is implemented in Kotlin with Java-compatible public APIs and supports Android
API 21 and later. It is intended for a single-user context: FeatBit evaluates targeting
rules on the server, and your app reads the synchronized flag values locally.

> Version `0.2.0` is available from Maven Central. See the
> [release notes and migration guidance](./docs/release-notes-0.2.0.md).

## Get Started

### Installation

Add Maven Central to your app's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Add the dependency to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("co.featbit:featbit-client-android:0.2.0")
}
```

The SDK uses Java 11 bytecode. Java apps can use the same Gradle Kotlin DSL
configuration. See the [integration guide](./docs/integration.md) for manifest,
lifecycle, and consumer setup details.

<details>
<summary>Build and consume a local development version</summary>

Build and publish the SDK to a local Maven repository using JDK 17 and Android SDK 34:

```sh
# Run from the SDK repository root.
bash gradlew :sdk:publishReleasePublicationToLocalTestRepository
```

On Windows, use `.\gradlew.bat` instead of `bash gradlew`. This creates the repository
under `build/test-repository/`; it does not publish to Maven Central.

Java apps can use Gradle Kotlin DSL build files. Add the repository to your app's
`settings.gradle.kts`:

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

</details>

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

The following Java example creates a streaming client, waits for initial remote
flag data, and evaluates a flag using callbacks. Retain the `FeatureFlags` owner
for the application lifetime and call `start(context)` once. Java callers do not
need `ClientAdapters` or coroutine code.

```java
import android.content.Context;
import android.util.Log;
import co.featbit.android.api.ClientFactory;
import co.featbit.android.api.ClientOptions;
import co.featbit.android.api.FeatBitClient;
import co.featbit.android.api.Outcome;
import co.featbit.android.api.Registration;
import co.featbit.android.api.User;

// Retain one FeatureFlags instance in your application owner.
public final class FeatureFlags {
    private FeatBitClient client;

    // Call once when initializing your application-owned SDK client.
    public void start(Context context) {
        Outcome<User> user = User.builder("a-unique-key-of-user").name("Bob").build();
        if (!user.isSuccess()) {
            Log.e("FeatBit", "Invalid user: " + user.getCode());
            return;
        }

        Outcome<ClientOptions> options = ClientOptions.builder()
                .sdkKey("your_client_sdk_key")
                .streamingUrl("wss://evaluation.example.com")
                .eventsUrl("https://events.example.com")
                .user(user.getValue())
                .build();
        if (!options.isSuccess()) {
            Log.e("FeatBit", "Invalid configuration: " + options.getCode());
            return;
        }

        Outcome<Registration> creationObserver = ClientFactory.getDefault()
                .create(context.getApplicationContext(), options.getValue())
                .observe(created -> {
                    if (!created.isSuccess()) {
                        Log.e("FeatBit", "Client creation failed: " + created.getCode());
                        return;
                    }
                    client = created.getValue();

                    Outcome<Registration> readyObserver = client.awaitReady(5_000)
                            .observe(ready -> {
                                if (!ready.isSuccess()) {
                                    Log.w("FeatBit", "Readiness wait ended: " + ready.getCode());
                                    // A timeout does not stop background synchronization.
                                }
                                boolean enabled = client.boolVariation("new-checkout", false);
                                // Use enabled to select your checkout experience.
                            });
                    if (!readyObserver.isSuccess()) {
                        Log.e("FeatBit", "Cannot observe readiness: " + readyObserver.getCode());
                    }
                });
        if (!creationObserver.isSuccess()) {
            Log.e("FeatBit", "Cannot observe creation: " + creationObserver.getCode());
        }
    }
}
```

Client creation and remote readiness are separate operations. Builders return
`Outcome<T>` immediately; asynchronous SDK methods return `Operation<T>`, whose
result is delivered through `observe(...)`. Check `isSuccess()` before using
`getValue()`. Flag reads return the supplied fallback when a usable value is unavailable.

There are two results to check when using `observe`: its return value reports whether
the callback was registered, while the callback receives the operation's actual result.
Closing the returned `Registration` detaches the callback without cancelling the
operation. `Operation.getResult()` can read an already-settled result, or returns
`null` while pending; do not busy-wait or block Android's main thread.

Timeout values are in milliseconds: `5_000` means five seconds. An operation can
complete earlier. A readiness timeout ends that wait without stopping synchronization.

The remaining Java snippets assume a retained `FeatBitClient client`, a validated
`User user`, and imports from `co.featbit.android.api.*`. Place statement snippets
inside an application method. Check configuration outcomes before passing their
values to `ClientFactory.create`, as in Quick Start.

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

```java
Outcome<ClientOptions> options = ClientOptions.builder()
        .sdkKey("your_client_sdk_key")
        .streamingUrl("wss://evaluation.example.com")
        .eventsUrl("https://events.example.com")
        .user(user)
        .build();
```

#### FeatBitClient Using Polling

```java
Outcome<ClientOptions> options = ClientOptions.builder()
        .sdkKey("your_client_sdk_key")
        .mode(SyncMode.POLLING)
        .pollingUrl("https://evaluation.example.com")
        .pollingIntervalMillis(30_000)
        .eventsUrl("https://events.example.com")
        .user(user)
        .build();
```

The default foreground polling interval is 30 seconds; the minimum is one second.

#### Streaming with Polling Fallback

Polling fallback is disabled by default. To enable it, supply a polling URL:

```java
Outcome<ClientOptions> options = ClientOptions.builder()
        .sdkKey("your_client_sdk_key")
        .streamingUrl("wss://evaluation.example.com")
        .pollingUrl("https://evaluation.example.com")
        .pollingFallback(true)
        .eventsUrl("https://events.example.com")
        .user(user)
        .build();
```

The SDK can fall back to polling when streaming fails and recover to streaming.
Use `client.getConnectionInformation()` to inspect the configured and effective modes.

#### User

`User` identifies the person whose flag values the SDK requests. Both `key` and `name`
must be nonblank. Add custom attributes with `attribute(...)`:

```java
Outcome<AttributeValue> country = AttributeValue.text("FR");
if (!country.isSuccess()) {
    throw new IllegalArgumentException("Invalid country: " + country.getCode());
}
Outcome<User> result = User.builder("unique-key-for-bob")
        .name("Bob")
        .attribute("country", country.getValue())
        .build();
if (!result.isSuccess()) {
    throw new IllegalArgumentException("Invalid user: " + result.getCode());
}
User user = result.getValue();
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

```java
Outcome<BootstrapFlag> flag = BootstrapFlag.create("new-checkout", "true", ValueType.BOOLEAN);
if (!flag.isSuccess()) {
    throw new IllegalArgumentException("Invalid bootstrap flag: " + flag.getCode());
}
Outcome<ClientOptions> options = ClientOptions.builder()
        .sdkKey("your_client_sdk_key")
        .streamingUrl("wss://evaluation.example.com")
        .eventsUrl("https://events.example.com")
        .user(user)
        .bootstrap(java.util.Collections.singletonList(flag.getValue()))
        .build();
```

Bootstrap values are available locally before remote synchronization. Remote data
replaces them when received. Bootstrap does not confirm online readiness.

### Logger

The default log level is `WARN`, but no logger is installed by default. Provide a
logger to receive SDK diagnostic codes:

```java
ClientOptions.Builder builder = ClientOptions.builder()
        .logLevel(LogLevel.WARN)
        .logger((level, diagnostic) ->
                android.util.Log.d("FeatBit", level + ": " + diagnostic.getCode()));
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

```java
boolean enabled = client.boolVariation("new-checkout", false);
EvaluationDetail<Boolean> detail = client.boolVariationDetail("new-checkout", false);
String message = client.stringVariation("welcome-message", "Welcome");
double discount = client.numberVariation("discount", 0.0);
```

`allVariations()` returns a snapshot without collecting evaluation events.

### Offline Mode

Set `.offline(true)` on the options builder to start offline, or change an existing client:

```java
Outcome<Registration> observer = client.setOffline(5_000).observe(result -> {
    if (!result.isSuccess()) {
        // Handle result.getCode().
    }
});
if (!observer.isSuccess()) {
    // Handle callback registration failure: observer.getCode().
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

```java
Outcome<Registration> observer = client.awaitReady(5_000).observe(ready -> {
    if (ready.isSuccess()) {
        boolean enabled = client.boolVariation("new-checkout", false);
        // Update application state.
    } else {
        // Handle ready.getCode(), for example TIMED_OUT or TERMINAL_FAILURE.
    }
});
if (!observer.isSuccess()) {
    // Handle callback registration failure: observer.getCode().
}
```

For a built-in online client, readiness requires remote confirmation. Offline and
local custom sources have distinct local readiness results. A timeout settles the
wait without stopping synchronization. Java callers use `observe(...)` as above.

#### Subscribe to flag(s) changes

Subscribe to all flag changes or a particular flag:

```java
Outcome<ChangeSubscription> allChanges = client.subscribeChanges(change -> {
    // Inspect change.getKeys() and change.getAllFlagsChanged().
});
if (!allChanges.isSuccess()) {
    // Handle allChanges.getCode().
    return;
}
Outcome<ChangeSubscription> checkoutChanges = client.subscribeFlag("new-checkout", change -> {
    boolean enabled = client.boolVariation("new-checkout", false);
    // Update the checkout UI.
});
if (!checkoutChanges.isSuccess()) {
    allChanges.getValue().getRegistration().close();
    // Handle checkoutChanges.getCode().
    return;
}

ChangeSubscription checkoutSubscription = checkoutChanges.getValue();
java.util.Map<String, EvaluationDetail<String>> initialValues =
        checkoutSubscription.getInitialValues();
// Render initialValues; it is captured when the subscription is registered.

// When the UI stops observing:
checkoutSubscription.getRegistration().close();
allChanges.getValue().getRegistration().close();
```

Callbacks and listeners run on Android's main thread; keep them short and never
block that thread waiting for completion. Registration can fail, so check the
returned outcome. Use `subscribeStatus(...)` for connection status callbacks.
Closing a subscription does not close the client.

### Switch user after initialization

Use `identify` when the active user changes, for example after login:

```java
Outcome<User> nextUser = User.builder("another-unique-key-of-user").name("Alice").build();
if (!nextUser.isSuccess()) {
    throw new IllegalArgumentException("Invalid user: " + nextUser.getCode());
}
Outcome<Registration> observer = client.identify(nextUser.getValue(), 5_000).observe(result -> {
    if (!result.isSuccess()) {
        // Handle result.getCode().
    }
});
if (!observer.isSuccess()) {
    // Handle callback registration failure: observer.getCode().
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

```java
boolean enabled = client.boolVariation("new-checkout", false);
// Use enabled to show the assigned checkout experience.

// Call when the user completes the corresponding business action.
Outcome<TrackResult> tracked = client.track("purchase", 12.5);
if (!tracked.isSuccess()) {
    // Handle tracked.getCode().
}
```

`track("purchase")` uses a default numeric value of `1.0`. Instrument your app's
business actions explicitly; the Android SDK does not automatically capture pageviews
or clicks. Events are queued in memory and can be lost if the process exits.

Use `flush()` to request delivery of outstanding accepted events:

```java
Outcome<Registration> observer = client.flush().observe(result -> {
    if (result.isSuccess()) {
        FlushResult delivery = result.getValue();
        // EMPTY, ALL_DELIVERED, or PROCESSED_WITH_LOSS.
    } else {
        // Handle result.getCode(), for example DISABLED, DEFERRED, or TIMED_OUT.
    }
});
if (!observer.isSuccess()) {
    // Handle callback registration failure: observer.getCode().
}
```

Inspect the outcome and flush result rather than assuming every accepted event has
been delivered.

### Close

When the application owner no longer needs the client, release its resources:

```java
Outcome<Registration> observer = client.close().observe(result -> {
    if (result.isSuccess()) {
        CloseResult summary = result.getValue();
        // Inspect summary.getUndeliveredEvents() and summary.getCleanupComplete().
    } else {
        // Handle result.getCode().
    }
});
if (!observer.isSuccess()) {
    // Handle callback registration failure: observer.getCode().
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
