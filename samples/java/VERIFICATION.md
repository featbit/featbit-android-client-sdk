# Java sample verification

Date: 2026-10-04 (Europe/Berlin).

## Artifact and environment

- Java application ID: `co.featbit.sample.java`; launcher: **FeatBit Café Java**.
- SDK: `co.featbit:featbit-client-android:0.1.0-SNAPSHOT`, published from this workspace
  to `build/test-repository` before building. No remote publication.
- Windows, Microsoft JDK 17.0.11, Gradle 8.7, AGP 8.5.2, compile/target API 34, min API 21.
- Device: `emulator-5554`, Android API 34. Phone: 1080×2220 / 440 dpi.
- Wide-window pass: same emulator with 1600×2560 / 240 dpi overrides; this is an emulated
  tablet configuration, not a separate physical tablet. Overrides were reset afterward.
- Dark/large-text pass: phone configuration, night mode, font scale 1.3; restored afterward.

## Executed checks

| Check | Result and actual scope |
| --- | --- |
| Root `spotlessApply` and `spotlessCheck` | Passed for repository configuration; no Kotlin source changes |
| `:java:assembleDebug`, `:java:assembleRelease` | Passed; Release has R8 enabled and local debug signing |
| `:java:testDebugUnitTest` | 4 passed: decimal HALF_UP/range, exact menu IDs/extra fields, malformed menu shapes, strict JSON syntax |
| `:java:lintDebug`, `:java:lintRelease` | Passed with 0 errors; existing-style warnings include shared layout overdraw/nested weights, unused resource, manifest attributes, toolbar action placement and English text concatenation |
| `:java:assembleDebugAndroidTest` | Passed; tests are Java and live only in the separate test APK |
| Main device suite with `-e live true` | `OK (3 tests)`, 78.732 seconds; detailed scope below |
| Wide-window visual test | `OK (1 test)`, 13.964 seconds; Demo/checkout/Flags/Inspect/Connection; navigation rail and reachable actions |
| Dark + font 1.3 visual test | `OK (1 test)`, 16.517 seconds; same screens, scrolling and reachable checkout/Apply actions |
| R8 Release UI smoke | Installed and launched; Local ready at USD 4.50, demo order, Flags navigation and explicit evaluation returning MATCH |

The main device suite covered:

- Both checkout layouts and all four shortcuts opening the matching flag details.
- No Evaluate action on the Flags list; explicit detail reads and stale-result marking.
- All four local editors, discount rounding/range fallback, valid JSON with invalid menu schema,
  removal and atomic Restore all.
- Offline TestData saving without falsely updating the preview, then applying on resume.
- User-sheet completion dismissal, selected size/user retention and same session after recreation.
- Streaming fallback default/off/on, required Polling URL, inactive URL exclusion, direct Polling,
  drafts across recreation, invalid Apply leaving Local active, and cleartext host validation.
- Real HTTP/WebSocket calls to the shared loopback protocol fixture: Polling, Sam targeting,
  Streaming, Track ACCEPTED, Flush ALL_DELIVERED, offline Track SUPPRESSED, terminal sync
  rejection and return to Local.

`SampleDeviceTest.configurationScreens` is optional: enable it with `-e visual true` and select
that method with `-e class co.featbit.sample.java.SampleDeviceTest#configurationScreens`.
Use `-e capturePrefix <name>` to distinguish configurations. The ordinary suite skips this test
unless explicitly enabled; the Live test also skips unless `-e live true` is supplied.

## Evidence

- Unit XML: `samples/java/build/test-results/testDebugUnitTest/TEST-co.featbit.sample.java.BusinessTest.xml`.
- Lint: `samples/java/build/reports/lint-results-debug.html` and `lint-results-release.html`.
- Device captures: app external `files/screenshots/`, copied locally to
  `samples/.impeccable/review/java/screenshots/` (ignored review artifacts).
- Captures include `phone-demo`, `phone-classic`, `phone-flags`, `phone-detail`, all four editors,
  users, connection, Inspect, Live Demo/Inspect and terminal status. `tablet-*` and `dark-large-*`
  include top and scrolled action-area views. `samples/.impeccable/review/java/release-phone.png`
  records the Release startup.
- Shared design boards remain unchanged; they are reference designs, not runtime evidence.

Independent native visual review: **ship** after inspecting 25 Java captures and comparing
current Kotlin source with the shared boards. No material Java-specific visual discrepancy
was found. Long flag keys wrap mid-word at larger text sizes, matching the shared layout.

## Limits

The fixture is not a deployed FeatBit environment and does not prove database/MQ persistence.
Physical devices, API 21 execution, full release/OS matrix, TalkBack, keyboard interactions,
rare Create/Close/registration-capacity failures, and full S01–S32 acceptance were not run.
S29–S30 timed Streaming failure/fallback/recovery remain unverified for this sample; fallback
form checks and SDK tests are not substituted for them. Release received the stated Local UI
smoke, not the full Debug Live or configuration suite. Kotlin's historical evidence is separate.
