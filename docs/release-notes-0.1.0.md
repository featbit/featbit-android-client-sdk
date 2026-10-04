# FeatBit Android Client SDK 0.1.0

Status: prepared for release; not yet published. Intended tag: `v0.1.0`.
Coordinates: `co.featbit:featbit-client-android:0.1.0`.

The first Android core SDK release provides a Kotlin implementation with Java-compatible
public APIs for applications running Android API 21 and later.

## Features

- Local typed flag evaluation, Bootstrap values, TestData and custom data sources.
- Streaming and Polling synchronization, reconnect, optional Polling fallback and
  Streaming recovery, with isolation across user changes and obsolete requests.
- User identification, persistent anonymous identity and context-isolated flag caching.
- Process lifecycle, connectivity and device-idle integration, with optional background polling.
- Evaluation and custom Track events, privacy filtering, bounded in-memory queues,
  retries, Flush and bounded Close behavior.
- Operation callbacks, subscriptions and Kotlin coroutine/Flow adapters.
- Independent Java/Kotlin consumers and Java/Kotlin sample applications.

## Compatibility and validation

The SDK targets Java 11 bytecode and Android API 21+. Independent consumers cover Java
and Kotlin 1.9.24, 1.9.25 and 2.2.10, including R8 Release execution on the emulator.
The complete 0.1.0 local acceptance matrix and hosted consumer CI passed. The maintainer
confirmed physical-device behavior and deployed event persistence, EndUser updates and
experiment attribution. See the [verification record](./verification.md#release-readiness--2026-10-04)
for source baselines, evidence and manual-validation scope.

## Scope and limitations

Events are retained in memory and do not survive process death. Track acceptance is not
proof of delivery or persistence. Host applications control Android network security policy.
OpenFeature Provider, durable event queues, iOS/KMP and Compose bindings are outside this release.
The minimum Android API is not a claim that every device/OS combination has been tested.
Detailed coverage limitations remain in [conformance](./conformance.md).

## Publication checklist

Before publishing these notes as a GitHub Release:

- Record the final tag/commit, successful signing workflow and signed artifact hashes.
- Complete Central publication and verify fresh Java/Kotlin downloads and R8 execution.
- Activate the [prepared installation instructions](./release.md#maven-central-installation-after-publication)
  in both READMEs and change this document's status to published.

This release-documentation update introduces no SDK API or runtime behavior changes.
