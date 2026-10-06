# FeatBit Android Client SDK 0.2.0

Status: prepared for release; not yet published. Intended tag: `v0.2.0`.
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

Local evidence and hosted CI must be checked against the release source before tagging.
The release workflow repeats the independent Java and Kotlin 1.9.24, 1.9.25 and 2.2.10
artifact matrix, signs the artifacts, and waits for Central's `PUBLISHED` state.
Fresh public Maven downloads must then be verified before reporting release completion.
See [release procedure](./release.md) and [verification history](./verification.md).
