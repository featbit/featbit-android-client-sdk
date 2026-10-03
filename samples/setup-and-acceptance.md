# Demonstration environment and behavioral acceptance

This language-independent specification defines the demonstration environment and expected
observable behavior for every implementation. Existing Kotlin evidence is recorded in
[kotlin/VERIFICATION.md](./kotlin/VERIFICATION.md); Java remains planned.
Build/run commands, device networking, and engineering checks live in the
[implementation guide](./implementation-guide.md).

## Live environment preparation

Use a dedicated demonstration environment and its **client-side SDK key**. Configure exactly
the same environment, values, and rules when comparing the two apps. Get endpoint base URLs
from the deployment's connection configuration; do not append SDK-internal protocol paths.

| Flag | Type | Variations / values | Serving rule |
| --- | --- | --- | --- |
| `sample-new-checkout` | Boolean | `false`, `true` | Default false; `plan` equals `pro` serves true |
| `sample-promo-message` | String | `A little treat, on us.` and `Fresh coffee, made for you.` | Default first value |
| `sample-discount-percent` | Number | `10`, `0` | Default 10 |
| `sample-menu-config` | JSON | Canonical menu from README | Default canonical menu |

Enable the flags for the demonstration environment. Variant identifiers are assigned by FeatBit;
do not invent them in the app or supply local records as remote evidence. Prepare the canonical
menu using the full JSON in [the shared design](./README.md#5-flag-and-business-contract).

The expected initial Live result is Alex/free using classic checkout at USD 4.50. After switching
to Sam/pro, compact checkout still costs USD 4.50. Changing the Number flag to zero yields USD 5.00.
Changing either user's attributes happens through the fixed preset Identify call, not server-rule
simulation inside the sample.

Configure a numeric custom-event metric with event name `sample-order-completed` if inspecting
order-value analytics in the control plane. The app sends the displayed USD amount as the numeric
value. SDK Track/Flush operation results remain the sample's immediate evidence; a metric/report
view and persisted backend data require separate service-side verification. The sample does not
create experiments or claim statistical assignment/conversion analysis.

## Common scenario matrix

Execute each scenario for every implementation with the same setup. Capture visible evidence and operation
results where relevant. Compare meaning and data, not exact timestamps or number of coalesced
status notifications. SDK events may deduplicate; do not require one server event per UI tap.

| ID | Operation / setup | Expected visible result | Behavioral evidence |
| --- | --- | --- | --- |
| S01 | Fresh process, no credentials | Local, Alex/free, compact checkout, Regular, USD 4.50 | TestData local ready; no production network/events/cache |
| S02 | Toggle checkout false then true | Classic/compact switch; selection and amount retained | TestData updates commit and notify |
| S03 | Edit promo and discount to 0, then 100 | Correct text; USD 5.00 then USD 0.00 | Successful typed reads; no size-price differences |
| S04 | Discount 99.9 | USD 0.01 with HALF_UP; discount amount USD 4.99 | Same decimal rounding and displayed amounts in every implementation |
| S05 | Save finite discount 101 or valid JSON with invalid menu schema | Business warning and fallback | SDK validity and business-schema validity are separate |
| S06 | Enter malformed JSON or nonfinite number | Inline error, Save blocked | No invalid update is submitted |
| S07 | Remove a local flag, then restore it | Catalog row remains; fallback then initial value | Remove/update results and snapshot changes observed |
| S08 | Restore all after edits/removals | Four initial values restored; unrelated UI/user/config retained | One TestData replace; inspect COMMITTED versus saved-only |
| S09 | Go offline in Local and edit | Saved for resume; preview not falsely updated | SAVED_FOR_NEXT_START; resumed snapshot applies latest values |
| S10 | Local to Live to Local in same process | Local edits/removals restored | Restored local data; no production cache use in Local |
| S11 | Submit invalid Live form while Local runs | Field error; Local still usable | Running connection is unaffected |
| S12 | Apply valid but unreachable Live endpoints | Waiting/timeout; fallback/current-context data available | Create differs from remote readiness; no recreate loop |
| S13 | Connect prepared environment; switch Alex to Sam | Classic to compact, both USD 4.50 | Remote rule evaluated; session remains connected through user change |
| S14 | Identify with unavailable service | Target user shown with pending then unconfirmed timeout state | Timeout is not identity rollback; no prior-user value leakage |
| S15 | Change remote flag while viewing Demo | Affected UI updates | SDK subscription drives refresh, not periodic UI polling |
| S16 | Browse Flags, explicitly evaluate, then change that flag | Timestamped result becomes Out of date; current snapshot refreshes | Browsing uses bulk snapshot; no automatic re-evaluation of detail |
| S17 | Explicit offline, order, then online | Order simulated; SUPPRESSED; online may still wait | Offline, event, and online outcomes accurately reflected |
| S18 | Live order then Flush | Amount equals captured displayed total; actual Track/Flush result | ACCEPTED is not delivery; outer Outcome checked before Flush value |
| S19 | Repeat identical orders; order during an in-flight Flush | UI records actions, SDK deduplication explained | Do not assume every tap sends; Flush covers its accepted boundary |
| S20 | Live events disabled | Business/sync still usable; events explanation | Track suppression; no event delivery claims |
| S21 | Invalid key / terminal sync and independent event failures | Separate diagnostics and reconnect path | No conflation of synchronization and event status |
| S22 | Rotate, navigate, dismiss pending form | Same client/context and operation progress; no duplicated actions | No old screen or user results overwrite current state |
| S23 | Kill process and relaunch | Local/Alex/default flags; no restored credential draft | SDK Live cache may remain on disk, untouched by Local start |
| S24 | Run installed variants and change one variant's Local/config state | Other variant unchanged; distinguishable launcher labels | Independent settings, cache, and drafts |
| S25 | Dark mode, large fonts, narrow/wide windows | Readable, operable native UI and consistent business behavior | Visible controls, accurate values, and unchanged semantics |

Record the implementation identifier, SDK version, device/OS, scenario result, and actual scope.
Kotlin implements the fallback settings below and has focused emulator form checks; see
[verification scope](./kotlin/VERIFICATION.md). Controlled failure/recovery checks remain
pending, and Java is not implemented. Existing SDK fallback tests do not prove that the
sample configuration UI works.
Attach implementation evidence links; this specification alone is not a PASS claim.

## Streaming fallback settings acceptance

Use a controlled service/proxy that can fail Streaming temporarily while keeping Polling
healthy. Keep the app foreground and connectivity available during the failure-window test;
a complete network outage cannot demonstrate successful Polling fallback. Use the existing
SDK timing; do not confuse the sample's five-second readiness wait with the fallback window.

| ID | Scenario | Expected behavior |
| --- | --- | --- |
| S26 | Fresh Live Streaming draft | Fallback off; only Streaming URL required; no fallback-active status |
| S27 | Enable fallback with missing/invalid Polling URL | Field appears with inline validation; Apply preserves existing client; a valid URL enables submission |
| S28 | Toggle fallback off; switch to Polling and back; rotate | Drafts retained; inactive URLs excluded from validation/submission; direct Polling never receives fallback=true |
| S29 | Submit Streaming with fallback enabled; sustain transient Streaming failure while Polling is healthy | In foreground, after the SDK's continuous 30-second window, effective mode becomes Polling; Configured stays Streaming; Polling fallback active appears; data can refresh |
| S30 | Restore Streaming after fallback | SDK probes after cooldown; valid synchronized recovery restores effective Streaming and clears fallback-active indication without user action; failed probes retain Polling |
| S31 | Disable fallback, use direct Polling, or receive terminal authentication failure | Disabled fallback keeps transient Streaming retries; direct Polling does not probe Streaming; terminal errors remain visible and are not bypassed |
| S32 | Edit without Apply; leave/reopen form; switch Local/Live; inspect large text/keyboard | Running settings unchanged before Apply; Local ignores Live drafts; form remains usable; Events configuration independent; process restart resets draft defaults |
