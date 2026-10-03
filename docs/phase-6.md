# Phase 6: Android lifecycle and suspension

Implemented 2026-10-02. See [verification](./verification.md) for actual results and
unexecuted physical-device acceptance. The public API and defaults are unchanged.

## Installation and visibility

`ClientFactory.getDefault()` automatically installs an application-scoped adapter using
`androidx.lifecycle:lifecycle-process:2.8.7` and its merged AndroidX Startup initializer.
Preserve that initializer when customizing the host manifest. This release has no public
manual-visibility mode. Lifecycle setup failure returns
`INVALID / lifecycle_observer_unavailable` before synchronization.

Registration and lifecycle inspection run on main. Storage, source validation and transport
work remain asynchronous. Initial visibility, network and idle snapshots are adopted before
`LocalClient.start()`, serialized with subsequent platform signals. The creation elapsed
deadline includes waiting for main-thread installation and anonymous identity preparation.

Visibility means the process lifecycle is at least STARTED. A visible, paused Activity in
multi-window still counts as foreground. AndroidX delays process STOP to absorb rotation and
brief Activity transitions; SDK grace and transition-flush budgets start at that process
signal, not an individual Activity's pause. See the Android
[ProcessLifecycleOwner contract](https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner).

Close immediately fences callbacks and releases the client reference. Native unregistration
is posted to main; a blocked main thread can delay detachment but cannot revive a closed
client. Creation failure/timeout uses the same cleanup path. No Activity is retained.

## Connectivity and suspension

- `ACCESS_NETWORK_STATE` is merged alongside `INTERNET`; neither requires a dangerous
  runtime permission. An API-21-compatible callback tracks Internet-capable networks,
  including VPN, with an initial snapshot and idempotent set reconciliation. Losing one
  network does not pause a surviving connection. No network is requested to stay alive.
- Availability is a hint, not service reachability/readiness. Unavailable optional network
  access/registration degrades to bounded request-based recovery and a safe diagnostic.
  The adapter follows Android's [network-state guidance](https://developer.android.com/develop/connectivity/network-ops/reading-network-state).
- API 23+ device-idle state and its protected broadcast withdraw execution permission during
  Doze. This pauses synchronization and ends transition flushing; idle exit cannot renew
  that allowance. If the OS suspends execution before notification, elapsed deadlines and
  callback authority checks apply at the first execution opportunity.
- No service, wake lock, exact alarm, WorkManager persistence or battery exemption is added.
  Background execution and exact polling intervals are not guaranteed.

## State-machine integration

- Default background policy pauses synchronization. Optional Flag grace retains only
  already-dispatched authoritative work, never replacements, reconnects or candidates.
  Background creation/Identify cannot borrow grace to start foreground synchronization.
- Explicit background polling switches immediately, independently of Flag grace. The old
  source is fenced first. The minimum/default interval remains 900,000 ms; polls schedule
  after completion and never catch up missed ticks.
- Background ends the failure window and cancels recovery candidates immediately.
  Foreground resumes the retained effective mode first. An overdue probe cannot precede
  the first resumed Polling attempt, which retains its ordinary request deadline.
- Grace, request, candidate and stream inactivity deadlines fence callbacks even when data
  arrives before the first ticker callback after sleep. Expired client waits settle before
  lifecycle resumption schedules new synchronization.
- Background and foreground seal separate event groups. Ordinary background delivery stays
  disabled with background polling. Optional transition flushing retains its independent
  two-second deadline, shortened by platform withdrawal. Late responses cannot acknowledge
  retained work or introduce terminal errors.
- Offline, Close, disableEvents, privacy filtering and independent subsystem terminal states
  retain precedence. TestData ignores physical connectivity but obeys visibility and Close.

## Verification entry points

Use JDK 17 / Android SDK 34 and the existing independent consumers:

```powershell
.\gradlew.bat :sdk:testDebugUnitTest :sdk:lintRelease :sdk:assembleDebug :sdk:assembleRelease :sdk:publishReleasePublicationToLocalTestRepository
python tools/check_api.py
.\gradlew.bat -p consumer-tests -Pphase6Probe=true :java:assembleDebug :java:assembleRelease :java:testDebugUnitTest :kotlin:assembleDebug :kotlin:assembleRelease :kotlin:testDebugUnitTest
adb -s emulator-5554 install -r consumer-tests/kotlin/build/outputs/apk/debug/kotlin-debug.apk
python tools/platform_device_checks.py --adb adb --serial emulator-5554
```

`PlatformLifecycleTest` covers initial-state adoption, network-set handovers, grace,
candidate cancellation, elapsed deadlines, idle withdrawal and event groups. Existing
event, protocol, storage and source tests continue to run.

The probe Receiver/Activity are not registered in ordinary Debug or Release builds.
Only `-Pphase6Probe=true` merges their test manifest into both variants, preserving actual
non-debuggable R8 device verification. These exported, unprotected components belong only
to the opt-in consumer fixture; do not distribute that APK. The SDK AAR is unaffected.

The device runner uses this test-only receiver/Activity and public AAR APIs. It starts an
isolated HTTP fixture on loopback port 5196, temporarily changes emulator network, rotation
and idle settings, and restores them on exit. Before changing settings it checks `tcp:5196`:
an existing mapping to host `tcp:5196` is reused and retained; a conflicting mapping stops
the run without device changes. A missing mapping is created with `--no-rebind` to reject
a concurrent bind. Cleanup removes only a mapping created by this run, checks that its
destination is still unchanged, and reports cleanup failure if it has been replaced or
cannot be removed. It force-stops
only the consumer fixture app. It does not erase app data or publish anything. This is not
production-server interoperability or analytics database-persistence evidence.

Run script regressions with `python -B -m unittest discover -s tools -p 'test_*.py'`.
Mapping tests execute the script's setup and failure cleanup against simulated ADB;
they do not establish new emulator or physical-device acceptance.

Physical-device deep sleep, vendor restrictions, real VPN handovers, backup restoration
and process termination at every atomic-write stage remain separate checks. Forced emulator
idle is not proof of physical deep sleep. Samples remain deferred; full release acceptance
belongs to Phase 7.

Specification reviewed: sdk-spec `3f08faa77dbf70bea208bd8ab946c2aa0b38ffad` plus its current
English mobile README/conformance edits; Android supplement, event grouping, this plan and
architecture, and Phase 2–5 handoffs. The adjacent checkout was read only; the working spec
is not an immutable published baseline.
