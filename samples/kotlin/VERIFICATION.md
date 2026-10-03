# Kotlin sample verification

Baseline: 2026-10-03, Windows / JDK 17 / Gradle 8.7 / Android API 34 emulator.
SDK: locally published `co.featbit:featbit-client-android:0.1.0-SNAPSHOT`.
This is sample evidence, not the SDK release-acceptance matrix.

## Automated checks

- Debug and minified R8 Release APK builds succeeded.
- Three business unit tests passed: decimal HALF_UP pricing and range fallback,
  valid menu parsing / exact IDs / unknown fields, invalid menu shapes.
- Debug and Release lint completed without errors; warnings remain for native layout
  structure, manifest compatibility guidance, and resource/style cleanup (13 Debug warnings).
- Two device instrumentation tests passed after the UI correction batch (`OK (2 tests)`,
  56.654 seconds), covering Local editing/restore/remove, invalid business values,
  explicit-read staleness, offline saved-only/resume, identity and Activity recreation;
  and Live Polling/Streaming/targeting/Track/Flush/offline/terminal recovery.
  Raw output: `../build/device-tests-final.txt`.

## Scope

The Live instrumentation uses `tools/protocol_fixture.py` on loopback. It runs real
SDK HTTP/WebSocket requests, user targeting, Track, Flush, offline suppression and
terminal-status recovery against deterministic fictional data. The fixture recorded
`sample-order-completed` with numericValue `4.5` and user `sample-sam`.
This does not establish deployed FeatBit database/message-queue acceptance.

API 21 is the build minimum, not a device-tested claim. Physical devices, the future
Java sample, production TLS endpoints, Create/Close resource-failure injection,
capacity rejection, and the full SDK release matrix are outside this evidence.

## Artifacts

- Debug: `build/outputs/apk/debug/kotlin-debug.apk`
- R8 Release: `build/outputs/apk/release/kotlin-release.apk`
- Unit reports: `build/reports/tests/testDebugUnitTest/index.html`
- Lint: `build/reports/lint-results-debug.html` and `lint-results-release.html`
- Native screenshots: `../.impeccable/review/`

Both APK variants use local debug signing for demonstration. No real connection key
is embedded; configure Live at runtime. Process death starts Local again.

## Native visual evidence

Captured on API 34 emulator: 1080x2220 at density 440, font scale 1.0 and 1.3,
light/dark, and an 800dp-wide virtual tablet (1200x1920 at density 240).
The 432x872 `hero-repro.png` is a native viewport aligned to the concept phone frame.
Approved coffee artwork is displayed from the unchanged overview's original photo region.
First-entry Flags/Inspect/detail captures now require no forced scrolling in the test helper.

During configuration changes the original emulator renderer stalled and produced black
captures/ANR. Those were rejected as evidence. A cold start using SwiftShader recovered the
emulator; the final suite and captures above ran on the recovered instance. This is emulator
evidence, not a hardware rendering/performance result.

The final R8 Release APK was installed and cold-launched on the restored phone configuration;
`release-phone.png` shows Local/Alex/Regular/USD4.50. Release Live TLS was not exercised.
The final protocol receipt summary is `../build/fixture-events.json`.

## Visual review

The independent finish reviewer returned `ship` for the final verdict pass:
remaining findings 1 (detail mode chip / Classic action visibility), 4 (drawn feedback
icons), and 6 (photo aspect / side bands) were scored resolved. This scores those
corrections, not pixel-exact equivalence on every device. Classic's pinned action
and scrolling price breakdown are the documented 48dp native adaptation.
The final tablet capture was recaptured fully ready using the R8 Release APK.
