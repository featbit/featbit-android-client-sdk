---
name: FeatBit Café Android samples
description: Warm café surfaces for a native feature-flag demonstration.
colors:
  cafe-primary: "#703D1C"
  cafe-on-primary: "#FFFFFF"
  cafe-tonal: "#FAEAD8"
  cafe-surface: "#FFFCFA"
  cafe-panel: "#F7F4F1"
  cafe-text: "#141821"
  cafe-muted: "#596170"
  cafe-outline: "#DDD7D1"
  cafe-good-bg: "#E8F3E7"
  cafe-good: "#19622B"
  cafe-warn-bg: "#FFF0D3"
  cafe-warn: "#714900"
  cafe-error: "#B3261E"
typography:
  headline:
    fontFamily: sans-serif-medium
    fontSize: 28sp
    fontWeight: 500
  title:
    fontFamily: sans-serif-medium
    fontSize: 20sp
    fontWeight: 500
  section:
    fontFamily: sans-serif-medium
    fontSize: 16sp
    fontWeight: 500
  body:
    fontFamily: sans
    fontSize: 14sp
  supporting:
    fontFamily: sans
    fontSize: 12sp
  json:
    fontFamily: monospace
    fontSize: 14sp
rounded:
  button-helper: 9dp
  surface: 10dp
  size-choice: 11dp
  checkout: 12dp
  size-group: 13dp
  badge: 20dp
spacing:
  small: 4dp
  related: 8dp
  component: 12dp
  section: 16dp
  page-inline: 18dp
  sheet-inline: 24dp
components:
  button-primary:
    backgroundColor: "{colors.cafe-primary}"
    textColor: "{colors.cafe-on-primary}"
    rounded: "{rounded.button-helper}"
    padding: 4dp 12dp
  checkout:
    backgroundColor: "{colors.cafe-surface}"
    rounded: "{rounded.checkout}"
    padding: 8dp 10dp
  warning:
    backgroundColor: "{colors.cafe-warn-bg}"
    textColor: "{colors.cafe-warn}"
    rounded: "{rounded.surface}"
    padding: 10dp 12dp
---

# Design System: FeatBit Café

## Overview

**Creative North Star: "FeatBit Café"**

Warm neutral surfaces, espresso actions, restrained status colors, native Android controls, and the approved coffee photograph define the implemented visual system. Roboto on the reviewed Android environment keeps the interface familiar and readable. The photograph and purchase preview provide the café character; diagnostic content remains compact and clear.

This is an extraction of the Kotlin implementation, with shared resources intended for the future Java sample. It does not supersede [README.md](./README.md), [interaction-design.md](./interaction-design.md), or [setup-and-acceptance.md](./setup-and-acceptance.md). Those documents and the approved `ui-overview-v2.png`, `ui-checkout.png`, `ui-connection-users.png`, and `ui-flag-editing.png` remain the product and composition authority. [PRODUCT.md](./PRODUCT.md) records that direction.

**Key Characteristics:**
- Warm surfaces and espresso action emphasis.
- A real coffee photograph preserved from the approved asset.
- Native Views, Material controls, and visible state labels.
- Scrollable content and platform adaptations for larger text and tablets.

Tokens above preserve native dp/sp units; they are not browser CSS dimensions. Light resources are normative in frontmatter; exact night replacements and native component metadata are in [.impeccable/design.json](./.impeccable/design.json). Sources are `shared/res/values/`, `shared/res/values-night/`, `shared/res/layout/`, and Kotlin `CafeViews.kt`, `CafeForms.kt`, and `MainActivity.kt`. Future code changes must update this extraction.

## Colors

Espresso and peach-toned selection surfaces sit against warm paper neutrals.

- **Primary:** `cafe-primary` identifies primary actions, selected navigation, and promotional emphasis. `cafe-on-primary` supplies foreground contrast. `cafe-tonal` supports selected indicators and badges.
- **Neutral:** `cafe-surface` is the page/sheet background; `cafe-panel` supports neutral notes. `cafe-text` carries main information, `cafe-muted` supporting text, and `cafe-outline` separators and outlines.
- **Semantic:** `cafe-good`/`cafe-good-bg` mark confirmed success; `cafe-warn`/`cafe-warn-bg` convey waiting, paused, fallback, and validation warnings. `cafe-error` marks errors and the removal action.

**The Evidence Rule.** Color accompanies the actual state label; green must not imply remote confirmation or delivery beyond the documented outcome.

Night resources replace all thirteen color roles. The night `Theme.Cafe` explicitly maps primary, primary-container, surface, on-surface, on-surface-variant, outline, and error. Unlike the day theme, it does not explicitly map secondary/container and surface-container roles: those remain inherited from Material3. Do not describe every night component role as a custom café override.

## Typography

The theme requests Android `sans`; emphasized labels use `sans-serif-medium`. Roboto is the approved direction and the platform rendering on the reviewed emulator, not a bundled font guarantee. JSON editors and activity timestamps use the platform monospace face.

The headline role is the compact checkout hero. Toolbar titles use the title role, section headings the section role, and labels generally use body or supporting sizes. Additional observed sizes are 10sp for compact discount details, 11sp for badges/helpers, 13sp for descriptions and key/value rows, 22sp for product/strong totals, 23sp for the avatar initial, and 24sp for compact pricing. These are contextual values rather than a new global type scale.

`CafeViews.label` adds 2dp line spacing with a 1.0 multiplier. Native font metrics determine the resulting line height; no fixed CSS line-height or tracking has been introduced.

## Layout

Pages use a vertical `NestedScrollView`, 18dp horizontal padding, and 20dp bottom padding. The toolbar is 56dp high; compact top-level navigation is 72dp high. At screen widths of 600dp and above, a navigation rail replaces bottom navigation and content is centered with width `min(620dp, screenWidthDp - 80dp)`. Forms and details hide the main navigation and retain Back.

Above font scale 1.15, compact checkout pricing/actions become vertical. Size choices also become vertical when there are more than three sizes or a label exceeds twelve characters. Editor content scrolls inside its native sheet. Sheet content uses 24dp horizontal/bottom padding and 18dp top padding.

Classic keeps the order action and simulation helper above navigation while the photo, sizes, and price breakdown scroll. This is an explicit native adaptation preserving 48dp radio/button targets; conceptual tight radio spacing and the full-width photo do not all fit in one phone viewport. Do not promise pixel-exact rendering across devices.

## Elevation & Depth

Tonal surfaces, outlines, and dividers carry most of the hierarchy. Bottom navigation explicitly has zero elevation. Native Material buttons, dialogs, sheets, ripples, focus states, and motion retain their theme behavior; the app does not define a custom shadow or animation scale. Do not invent CSS shadows or motion timings as extracted tokens.

## Shapes

Shared `shape()` containers use the surface radius and optional 1dp outline. The code-created button helper uses its own slightly smaller radius; the XML `Cafe.Button` fallback style uses 10dp. Checkout and the segmented size group have distinct radii shown in frontmatter. Badges use rounded silhouettes; the avatar uses a 30dp radius inside a 42dp square.

## Components

- **Actions:** Native Material buttons, sentence case, 14sp text, at least 48dp tall. Primary and outlined variants come from the Material theme. The helper uses 12dp horizontal and 4dp vertical padding. Icon actions have 48dp minimum dimensions and 12dp padding. Disabled, focus, and ripple treatment remain native.
- **Inputs:** Material outlined `TextInputLayout`/`TextInputEditText`, 14sp text, password visibility affordance for the key, inline native errors. Multiline fields start at five lines. JSON uses monospace. No custom corner/focus-width token is asserted for inherited controls.
- **Navigation:** Demo / Flags / Inspect, primary ink when checked and muted ink otherwise, with tonal active indicators. Native rail on wide screens; no browser hover substitute.
- **Status and notes:** Connection summary uses a status dot, two text lines, 14dp horizontal/9dp vertical padding. Warnings use an icon and text, rounded amber background, and polite accessibility announcements. Neutral notes use panel/muted roles.
- **Checkout:** Selected sizes use primary/on-primary; unselected sizes use surface/muted. The outlined checkout container holds the amount and action, reflowing vertically for large text. Classic uses native radio choices and a separate pinned action area.
- **Flags:** Rows use native selectable feedback, 56dp minimum height, and a 24dp chevron. The implementation separates full snapshot text from abbreviated row previews; editor validity appears inline with text, an icon, and semantic color.
- **Sheets:** User choice and local editing use expanded native bottom sheets with a drag handle and explicit surface background. Detailed state behavior remains governed by the interaction contract.
- **Photography:** `CafeViews.coffee()` decodes rectangle `(83, 411, 480, 598)` from the unchanged `cafe-reference.png` using `BitmapRegionDecoder`. The displayed region keeps its 397:187 ratio using `FIT_CENTER`, with rounded clipping. Do not generate or substitute a new photograph.

## Do's and Don'ts

- **Do** preserve the approved café palette, photo, and product contract across Kotlin and the future Java sample.
- **Do** keep dimensions in dp, text in sp, and primary controls at least 48dp tall.
- **Do** use native screenshots to assess native rendering, including night and larger text.
- **Don't** turn conceptual artwork into an all-device pixel-exact claim.
- **Don't** invent web tokens, bespoke shadows, or animation timings for inherited Material behavior.
- **Don't** report Java implementation, live-service verification, or screenshot coverage beyond the evidence actually obtained.

The recorded native review set is `.impeccable/review/{release-phone,phone-classic,phone-detail-stale,phone-editor-json,tablet,phone-dark,phone-font130}.png`. These captures document reviewed states, not every possible state/device. HTML detectors are inapplicable and were not run for this native implementation.
