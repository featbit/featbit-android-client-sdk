# UI image briefs and review notes

Generated with the built-in image generation tool, not the CLI/API fallback. These are
consolidated final prompts incorporating the correction passes, for future design continuity.
Use the original `ui-design.png` only as a visual-style reference; it has superseded content.
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
base price/discount near the amount. Keep the simulated-payment disclaimer. In Flags,
label the result Last explicit evaluation and give its observation time. Inspect distinguishes
local Track acceptance from transport delivery and storage confirmation.

## Checkout comparison — ui-checkout.png

Two full screens, classic flag=false with Alex/free and compact flag=true with Sam/pro.
Classic has a vertical radio list and subtotal/discount/total breakdown with full-width button;
compact has segmented sizes and unified amount/button surface. Both select Regular, base USD 5.00,
10% discount, USD 4.50 total and the same promo. No prices beside individual sizes: all sizes cost
the same. Preserve selection, pricing, and event behavior; only layout differs.

## Connection and identity — ui-connection-users.png

Three screens: Live connection form with masked client key, sync mode and URL, enabled Events,
missing Events URL validation; a waiting/timeout Demo with fallback USD 5.00 and fallback promo
Fresh coffee, made for you.; a user switch sheet showing Alex/free as Previous and Sam/pro as
the target while waiting. Events helper says Send evaluation and custom analytics events.
Do not describe Events as receiving flag updates. Use an indeterminate spinner, not a percentage
progress bar. A timeout does not undo an admitted identity change.

## Local editing and evaluation — ui-flag-editing.png

Four concept panels: Boolean/String editor variants (one flag per actual editor); Number 101
with an allowed-save business fallback warning; valid menu JSON editor with regular ID/default;
detail screen showing current false and an older explicit true/MATCH evaluation marked Out of date.
Show Save, Remove flag, Restore default, and Evaluate flag as appropriate. Expected type is a
sample contract, not fabricated server metadata. The combined Boolean/String panel is an editor
comparison, not a batch-save feature. Actual editors use the sheet/full-height behavior in the
interaction specification.

## Review scope

Visually inspected generated boards for legibility, navigation, user presets, prices, and SDK
semantics. Corrected generated per-size prices, the Events helper, timeout fallback copy, and
the pending-user label/progress representation. Written contracts govern exact copy and behaviors
abbreviated in the pictures. Native layout measurements, dark-theme/font-scale behavior, and
runtime accessibility remain implementation-time checks, not validated by these images.
