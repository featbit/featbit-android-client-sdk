# Identity adoption and readiness

Added on 2026-10-06. `FeatBitClient` directly exposes identity adoption operations, allowing
callers to wait for identity adoption separately from flag readiness. No capability cast is needed.
Custom implementations of `FeatBitClient` must implement both new methods.

The named `identify` method now uses the same asynchronous preparation path as
`identifyAnonymous`. This changes its pre-completion timing: an immediate flag read can still
use the old user until the worker adopts the new identity. Its timeout now includes queueing,
preparation and readiness, sharing one deadline. Wait for the operation's success before reading
new-user flags. A timeout after adoption does not undo the switch or stop synchronization.

| API | Successful result means | Timeout meaning |
| --- | --- | --- |
| `identify(user, timeout)` | User adopted and readiness policy satisfied | May already have adopted the user; no rollback |
| `identifyAnonymous(timeout)` | Persistent anonymous user adopted and readiness policy satisfied | May be preparing identity or already waiting for flags |
| `identifyContext(user, timeout)` | User adopted; no readiness guarantee | Operation TIMED_OUT means this request did not adopt and cannot adopt later |
| `identifyAnonymousContext(timeout)` | Persistent anonymous user adopted; no readiness guarantee | Same adoption-only guarantee |

All four methods schedule preparation on the SDK worker. Receiving an Operation is not proof
of adoption; it may already have completed by the time it is returned. Check its result or observe
completion. The timeout includes worker queueing/preparation. For the adoption-only methods,
once adoption wins, SUCCESS and its
IdentityReceipt are retained, even if callback delivery occurs after the deadline. A receipt's
generation is scoped to its client and proves a past adoption, not that the user is still current.

New identity requests supersede pending preparations, including requests made through the old
APIs. Close and timeout prevent late preparation callbacks from adopting. Anonymous preparation
can still create/persist a key even if its adoption is cancelled by a later request. It does not
reset a previously persisted anonymous key. Network synchronization and readiness are separate:
adoption can succeed offline or with a terminal data source, while a subsequent awaitReady may fail.

## Kotlin

Run in a coroutine, with no concurrent user switch in another application component:

```kotlin
val adapters = ClientAdapters.getDefault()
val operation = client.identifyContext(userB, 5_000L)
val adopted = adapters.await(operation, 6_000L)
if (adopted.isSuccess) {
    val ready = adapters.await(client.awaitReady(10_000L), 11_000L)
    if (ready.isSuccess) {
        val enabled = client.boolVariation("new-checkout", false)
    }
}
```

For an anonymous user, replace the first operation with
`client.identifyAnonymousContext(5_000L)` and configure `anonymousEnabled(true)`.
The external coroutine wait has its own deadline. If that wait times out, adoption is unknown
until the original `operation.getResult()` settles. Cancelling the coroutine or detaching an
observer never cancels the underlying adoption. Do not treat a longer outer timeout as an
absolute guarantee that callbacks will have run.

## Java

```java
Operation<IdentityReceipt> operation = client.identifyContext(userB, 5000L);
Outcome<Registration> observation = operation.observe(adopted -> {
    if (!adopted.isSuccess()) return;
    Outcome<Registration> readinessObservation = client.awaitReady(10000L).observe(ready -> {
        if (!ready.isSuccess()) return;
        boolean enabled = client.boolVariation("new-checkout", false);
    });
    // Handle readinessObservation failure; it describes observer registration, not readiness.
});
// Handle observation failure; the operation may still execute. Inspect its retained result.
```

Serialize application identity changes, or associate callbacks with your own request token.
An old successful receipt must not reopen reads after a later request switches the user.
Existing callers that want adoption plus readiness can continue to use identify/identifyAnonymous.
