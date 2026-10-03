---
map: web-revamp
ticket: 02
title: "Design tokens, light and dark, manifest and icons"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-pick-the-web-design-language]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Design tokens, light and dark, manifest and icons

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D2.

## Build

- `server/frontend/src/index.css`: replace the stock neutral `:root` and `.dark` blocks with the
  ADR 0053 tokens; add `--shared`, `--shared-foreground`, `--done`, `--unverified-bg`,
  `--unverified-fg`, `--estimate-fg` and map them in `@theme inline`.
- Fonts as ADR 0053 names them, via `@fontsource-variable/*` (bundled, never fetched at runtime:
  CLAUDE.md section 7 "Assets are bundled").
- `src/lib/theme.ts`: `getThemePref()` / `setThemePref('system'|'light'|'dark')`, localStorage key
  `legion.theme.v1` in try/catch; `applyTheme()` toggles `dark` on `<html>` and listens to
  `prefers-color-scheme`. Inline pre-paint script in `index.html`.
- `vite.config.ts` manifest colours and `index.html` light/dark `theme-color` metas from the palette.
- `scripts/make_icons.py`: draw from the chosen palette, not `Color.kt`. Regenerate the icons.
- Restyle the existing `components/ui/*` primitives to the tokens. No raw colour left in `src/`
  outside `index.css` (grep for `#rrggbb` and `oklch(` in `*.tsx` returns nothing).

## Verification

- [ ] vitest: `theme.ts` (system follows media query; stored pref wins; unreadable storage = system).
- [ ] `npm run build && npm test` green, count from the JSON reporter.
- [ ] Playwright shots of `/login`, `/`, `/lists` at 390x844 and 1440x900, light and dark, kept in
      `research/shots/02/`.
