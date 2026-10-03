# FeatBit Café product authority

Audience: developers evaluating the FeatBit Android SDK. A simulated coffee purchase makes
feature-flag changes visible; Flags and Inspect explain the SDK results.

The approved product contract is [README.md](./README.md),
[interaction-design.md](./interaction-design.md), and [setup-and-acceptance.md](./setup-and-acceptance.md).
The approved compositions are `ui-overview-v2.png`, `ui-checkout.png`,
`ui-connection-users.png`, and `ui-flag-editing.png`. This file does not replace those contracts.
Kotlin and the future Java app share this authority.

## Implementation direction

- Audience and task: working credential-free Local demo, then real Live configuration and SDK inspection.
- Layout: Demo / Flags / Inspect, native sheets and Back; 600dp+ uses a navigation rail.
- Visual language: warm neutral surfaces, espresso actions, amber warnings, restrained green success,
  Roboto, real coffee photograph. English UI text. No default lavender component surfaces.
- First viewport: business hero and compact checkout; Flags exposes its evaluation action;
  Inspect groups connection, events, and recent activity. Large text remains scrollable.
- Interaction: boolean changes checkout layout while preserving selected size and amount;
  editable Local flags remain distinct from remote Live evaluations. Actual Outcomes stay truthful.

The app displays the approved overview's photo region directly from the unchanged reference
asset using Android BitmapRegionDecoder; it does not substitute a new latte photograph.
Review captures are native emulator captures under `.impeccable/review/`.
Generated concepts are not measurement-grade Android screenshots: native accessibility targets,
font scaling, system bars, sheet topology from the written contract, and tablet rail remain
explicit platform adaptations. No unsupported claim of pixel-exact rendering on every device.

Classic keeps its order action and simulation helper above navigation while its longer price/size
content scrolls. This preserves the written 48dp touch-target requirement: conceptual compact
radio spacing cannot accommodate those targets and the full-width photo in one phone viewport.
