# FeatBit Android Client SDK 0.2.0

Status: published to Maven Central on 2026-10-06. Tag: `v0.2.0`.
Coordinates: `co.featbit:featbit-client-android:0.2.0`.

This release separates identity adoption from flag readiness and makes typed flag
evaluation consistent with the flag's declared type. Review the migration notes
before upgrading from 0.1.0.

## Identity switching

- New `FeatBitClient.identifyContext` and `identifyAnonymousContext` return an
  `Operation<IdentityReceipt>`. Success confirms that the SDK has adopted the user;
  it does not promise that the user's flag data is ready. Use `awaitReady` when needed.
- `identify` and `identifyAnonymous` both prepare and adopt the user on the worker,
  then report success after readiness. Returning an Operation does not confirm adoption.
- An operation that times out before adoption cannot switch the user later. A readiness
  timeout after adoption does not roll the user back.
- Java and Kotlin samples keep the previous selection while adoption is pending,
  update it after adoption succeeds, and retain the adopted user if readiness times out.

## Flag evaluation and events

- Boolean, number and string accessors, including their Detail variants, require the
  corresponding declared flag type. JSON accessors require the JSON type.
- A type mismatch returns the caller's default; Detail results expose `WRONG_TYPE`.
  Generic `variation` and `variationDetail` still choose parsing from the declared flag
  type, independently of the default value's type.
- `jsonTextVariation` preserves the JSON flag's raw text without validating JSON syntax.
- Eligible evaluations of a selected remote variation can record an evaluation event
  even when local conversion fails. The event identifies that remote variation; it
  does not send the caller's default or a local conversion error field.

## Migration from 0.1.0

- Custom implementations of `FeatBitClient` must implement the two new identity methods.
- Code that assumed `identify` had already switched users when it returned must instead
  observe the relevant Operation. Use the context methods to observe adoption separately
  from readiness; see [identity adoption](./identity-adoption.md).
- Use an accessor matching the flag's declared type. Code relying on permissive
  conversion across declared types can now receive its default value.
- Numbers remain `Double`. This release does not provide exact `Long` precision.

Android API 21+ and Java 11 bytecode requirements remain unchanged. The OpenFeature
provider is a separate project and is not included in this SDK artifact.

## Validation and publication

Source: `7210251fbe5e3f9f452ef15e370da265f1009702`.
[Publication workflow](https://github.com/featbit/featbit-android-client-sdk/actions/runs/37489895116)
succeeded with Central state `PUBLISHED`. Public AAR, POM, Gradle metadata, sources
and documentation match the signed workflow bundle; checksums and PGP signatures passed.

The Java and Kotlin 1.9.24, 1.9.25 and 2.2.10 artifact matrix passed. A separate
Java/Kotlin project downloaded 0.2.0 from Maven Central using a fresh dependency cache;
Debug and R8 Release builds, unit tests, Release lint and formatting passed.
This final download verification did not repeat device or live-service checks.
See the [verification record](./verification.md#maven-central-020-publication--2026-10-06).
