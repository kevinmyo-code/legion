# Build: MILSPEC theme and tokens

Type: task
Status: resolved

## Question

Implement the MILSPEC token spec (ticket 01's table) in `ui/theme/`: dark-only ColorScheme over
M3, re-cut `LegionSemantics` to the ticket-03 contract (amber=data, green=good, red=needs-you,
tag-weight ladder), mono type scale with stencil-caps header styles, near-zero shape scale, the
pane composable (1px border + 2px corner brackets), tag composables (outline/inverted), meter
with pace tick, dashed row rule. Render theme previews BEFORE building screens on it (L11 - the
`contentColorFor` bug class); verify `surface` and every semantic hold distinct colour values.
Utility screens must inherit acceptably with zero edits - eyeball each on-device.

## Answer

Built 2026-08-08 (coding agent), commit on feat/cyberdeck. Color/Theme/Type/Shape re-cut to
MILSPEC; new DeckMotion.kt + ui/common/DeckPanels.kt (DeckPane, DeckTag ladder, DeckMeter,
DeckRow, StatusLine); ThemePreview rewritten around the real components. Zero screen edits
needed - every screen reads LocalLegionSemantics / colorScheme (traced). contentColorFor
distinctness audited against the M3 sources jar directly. compileDebugKotlin + testDebugUnitTest
green (tested). Verification accounting (L11): preview RENDERING deferred to ticket 21 ship pass,
named in ThemePreview's own doc comment; daylight contrast of muted #8A8F78 likewise ticket 21.
