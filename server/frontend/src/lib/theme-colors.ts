/**
 * The two `theme-color` values, as sRGB hex, in one file that both the app and
 * `vite.config.ts` (the PWA manifest) import.
 *
 * They are the page ground of ADR 0053 in light and dark, converted from
 * `oklch(0.98 0.004 285)` and `oklch(0.17 0.006 285)` by
 * `python scripts/make_icons.py --hex`. They have to be hex, not OKLCH: the
 * browser chrome and the manifest take a plain colour. If the ground in
 * `index.css` moves, re-derive these; `theme.test.ts` fails if `index.html`
 * disagrees with them.
 */
export const THEME_COLOR_LIGHT = '#f8f8fb'
export const THEME_COLOR_DARK = '#0f0f12'
