# UI image briefs and review notes

Generated with the built-in image generation tool, not the CLI/API fallback. These are
consolidated final prompts incorporating the correction passes, for future design continuity.
`ui-design.png` now mirrors `ui-overview-v2.png`; both contain the current overview.
Images are concept artifacts, not implementation or device-verification evidence.

## Shared visual prompt

High-fidelity native Android Material Views UI concept for FeatBit Café. English UI, readable
Roboto, warm-white surfaces, espresso-brown actions, restrained amber warnings, meaningful
green confirmation. Flat front-facing complete Android screens, status/gesture bars and safe
areas, no perspective, no iOS controls, no invented SDK telemetry. Maintain the established
coffee photograph/style, navigation, and visual hierarchy. Diagrams show the shared experience
of Kotlin and Java; language-specific launcher names need not appear in each screen.

## Overview — ui-overview-v2.png

Preserve the three-screen Demo/Flags/Inspect board. Show Sam, avatar S, plan: pro in Demo.
Compact checkout combines amount and order button into a horizontal action surface with
base price/discount below the amount, with the discount shortcut immediately after the badge.
Show four flag-detail shortcuts: promo, checkout layout, menu, discount. Keep the simulated-payment
disclaimer. The Flags panel demonstrates Local Demo and includes Restore all demo flags.
Live mode hides this button. Remove the entire list evaluation panel; evaluation stays in details.
The Local-only helper in the board is a design annotation, not required app copy. Inspect distinguishes
local Track acceptance from transport delivery and storage confirmation.

## Checkout comparison — ui-checkout.png

Two full screens, classic flag=false with Alex/free and compact flag=true with Sam/pro.
Classic has a vertical radio list and subtotal/discount/total breakdown with full-width button;
compact has segmented sizes and unified amount/button surface. Both select Regular, base USD 5.00,
10% discount, USD 4.50 total and the same promo. No prices beside individual sizes: all sizes cost
the same. Preserve selection, pricing, and event behavior; only layout differs. Both layouts
show four flag-detail shortcuts. Compact puts the discount shortcut immediately after the
discount badge, not beside the total; with zero discount it remains beside the total.
Classic keeps its order action above navigation while its content scrolls.

## Connection and identity — ui-connection-users.png

Three screens: Live connection form with masked client key, sync mode and URL, enabled Events,
missing Events URL validation; a waiting/timeout Demo with fallback USD 5.00 and fallback promo
Fresh coffee, made for you.; a user switch sheet before submission with Alex/free current and
Sam/pro selected. The enabled Switch user button submits Identify. Events helper says Send evaluation and custom analytics events.
Do not describe Events as receiving flag updates. The footer explicitly requires the sheet to
remain open while pending and close when an admitted operation settles, including timeout;
immediate rejection keeps it open. A timeout does not undo an admitted identity change.
The waiting screen is a condensed state illustration; use the checkout board for the complete
menu/layout. The fixed hero headline itself is not a flag shortcut.

## Local editing and evaluation — ui-flag-editing.png

Four concept panels: Boolean/String editor variants (one flag per actual editor); Number 101
with an allowed-save business fallback warning; valid menu JSON editor with regular ID/default;
detail screen showing current false and an older explicit true/MATCH evaluation marked Out of date.
Show Save, Remove flag, Restore default, and Evaluate flag as appropriate. Expected type is a
sample contract, not fabricated server metadata. The combined Boolean/String panel is an editor
comparison, not a batch-save feature. Actual editors use the sheet/full-height behavior in the
interaction specification.

## Streaming fallback proposal — ui-connection-fallback-proposal.png

Concept board implemented in Kotlin, with focused emulator form checks recorded in
[kotlin/VERIFICATION.md](./kotlin/VERIFICATION.md); live failure/recovery acceptance remains
pending. Three Connection forms:
Streaming with fallback off (default), Streaming with fallback on and a required Polling URL,
and direct Polling without Streaming URL or fallback controls. Preserve Events and Apply.
The enabled helper is exactly:

> While the app is in the foreground, automatically switches to polling after 30 seconds of continuous temporary streaming failures. Periodically attempts to restore streaming.

Keep this copy complete and readable. Settings do not change the running client until Apply;
invalid active endpoints preserve the existing client. URLs are fictional placeholders.
This board extends the transport settings of the connection/identity board; it does not
replace its user-switch or error-state contract.

## Review scope

Visually inspected generated boards for legibility, navigation, user presets, prices, and SDK
semantics. Corrected generated per-size prices, the Events helper, timeout fallback copy, and
the pending-user label/progress representation. Written contracts govern exact copy and behaviors
abbreviated in the pictures. Native layout measurements, dark-theme/font-scale behavior, and
runtime accessibility remain implementation-time checks, not validated by these images.

## Revision 2026-10-03

Replaced overview, checkout and connection/identity boards in place using image edits.
Retained the detail/editor board because detail evaluation remains supported. Checked the
Local restore action, absent list evaluation, four checkout shortcuts and discount placement,
and the automatic-dismissal annotation. These are shared Kotlin/Java implementation references,
not new device-test evidence. Use native 48dp touch targets and the written contracts for exact
layout/copy. The independent `shared/assets/cafe-reference.png` remains unchanged: it supplies
the app coffee crop and must not be replaced with the revised overview.
