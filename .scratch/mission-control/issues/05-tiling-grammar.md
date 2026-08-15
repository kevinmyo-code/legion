# Console tiling grammar: grid, panel sizes, 48dp floor

Type: prototype
Status: resolved
Blocked by: 03

## Question

What is the layout grammar that turns a module root into a tiled console without wrecking it on a
phone?

The charting decision fixed the hybrid: **module roots tile, drilldowns stay single-subject and
roomy, anything tappable keeps a 48dp target.** `ref-d` tiles four live panels at once - on a
desktop browser. LEGION runs on a 6" phone held one-handed.

**Resolves:**

1. **The grid.** Column count, gutter, outer margin inside the bezel. Whether it is a true grid or
   a set of sanctioned panel shapes (full-width, half-width, third).
2. **The panel size vocabulary.** How many shapes exist, what each is for, and the minimum content
   each can carry legibly. A half-width panel holding a hero number plus a sparkline is the
   obvious unit; prove it at real sizes.
3. **Tap targets.** A half-width panel that is tappable must still present 48dp. Decide whether
   the whole panel is the target or a designated row within it, and what the pressed state looks
   like when the panel is an outlined frame rather than a filled card.
4. **Scroll behaviour.** Does the tiled root scroll, or fit? `ref-d` fits everything at once, which
   is part of its appeal, and a phone almost certainly cannot. If it scrolls, decide what stays
   pinned - the status line and the hard-key row at minimum.
5. **Drilldown layout.** The counterpart rule: what "single-subject and roomy" means dimensionally,
   so a drilldown is visibly a different mode from a root and not just a shorter list.
6. **Density limits.** The smallest type size and tightest row height that survive ticket 10's
   daylight floor. Set them here as a floor the build tickets may not undercut.
7. **Landscape and large text.** What happens at the system's largest font scale, and whether
   tiling collapses to a single column below some width.

**Method.** Extend the ticket 03 mock. Build HOME at real phone dimensions - not a desktop
viewport - with the real panel inventory it will carry.

**Deliverable.** A dimensioned grammar the build tickets read, plus the artifact link. This is the
ticket that decides how many surfaces one build ticket can reasonably carry, so the fog's build
slicing waits on it.

## Answer

Dimensioned grammar at `https://claude.ai/code/artifact/ca212901-36ad-4b3f-94d1-b7062ac2afc8`,
which draws HOME at 1dp = 1px inside the phone's real insets.

**The phone was connected, so this ticket is measured rather than assumed.** Everything below is
derived from ADB readings taken 2026-08-14, not from a guess about a typical phone.

### 0. The device, and two corrections to ticket 03

| Reading | Raw | In dp |
|---|---|---|
| Model | CPH2471 (Oppo A17K) | - |
| Physical size | 720 x 1612 | **360 x 806** |
| Density | 320 | 2.0x |
| Display cutout | `Rect(322,0 - 398,64)` | **38 x 32, centred** |
| `navigation_mode` | 0 | **three-button, 48dp** |
| `font_scale` | 1.0 | baseline for everything below |

1. **The nav bar is 48dp, not a 24dp gesture bar.** Ticket 03 reasoned about a gesture bar and
   flagged it untested. It was wrong by 24dp, which comes straight off the content budget.
2. **There is a notch**, and ticket 03 left the case untested. It sits exactly where the bezel's
   64dp top break is. Harmless, because the bezel sits inside the insets and the two never touch -
   but it is a coincidence, not a design, and should not be relied on.

Ticket 03's 360dp assumption itself **holds**, so its 328dp interior figure stands.

### 1. The vertical budget

| Band | dp |
|---|---|
| System status bar / cutout | 32 |
| Bezel top | 17 |
| Status line | 29 |
| **Content** | **584** |
| Alfred strip | 31 |
| Hard key row | 46 |
| Bezel bottom | 19 |
| Nav bar (3-button) | 48 |

**584dp is what a tiled root actually gets**, 72% of the screen.

### 2. The grid: two columns

| Shape | Width | Content width | Hero chars at 30sp | Verdict |
|---|---|---|---|---|
| FULL | 328dp | 310dp | 17 | hero numbers, feeds, charts |
| HALF | 159dp | 141dp | 7 | one figure and one qualifier |
| THIRD | 103dp | 85dp | 4 | **rejected** |

**Thirds are rejected on arithmetic, not taste.** At 30sp the mono advance is 18dp per character,
so a third tile fits four characters. `82.4` fits; `$2,418` does not. A tile shape that cannot hold
this app's most common content is not a tile shape.

**The rule that falls out: a half tile holds at most 7 characters of hero.** This is a hard content
constraint on every build ticket. `+4,200.00` is nine characters and cannot go in a half tile at
30sp. Either it is full-width, or the figure is abbreviated at the call site, or it steps to the
19sp secondary size (which fits 12). Pick one deliberately. **Never let a number ellipsize** -
that is `DeckRow`'s shipped never-truncate-a-value rule read onto layout.

### 3. Tap targets, and a collision

The whole panel is the target when a panel navigates. Every shape clears 48dp by construction (the
shortest, a half tile with one figure, is ~62dp), so the floor is met without padding.

**The obvious pressed state is unusable.** Brightening the panel border to full `chrome` is
*exactly* ticket 04's alarm pane border. A press would momentarily make an ordinary panel look like
an alarming one, on a screen where alarm now depends on chrome weight rather than hue.

**Pressed is a fill change instead:** `panel` #05070C to `rule` #1E2530 for the press duration. The
pill follows automatically, since it paints whatever is behind it. It reads as a cool lift where
alarm's fill is a warm one, so the two cannot be confused.

### 4. What scrolls, what stays

- **Pinned:** status line (top), Alfred strip and hard-key row (bottom). All three are shell, not
  content, and must be reachable without scrolling.
- **Scrolls:** the tiled area between them, 584dp of viewport.
- **The bezel does not scroll.** It frames the whole shell including the pinned rows, which is what
  makes it read as the device rather than as a container.
- **A root must show its hero panel plus one full row of tiles without scrolling.** Comfortably met
  at 584dp; it exists as the check that stops a build ticket stacking six panels above the fold.

The refs fit everything at once. At 584dp that is not available, and pretending otherwise produces
a screen that lies about how much it is showing.

### 5. The drilldown counterpart

| | |
|---|---|
| Columns | 1, full-width panels only, no tiling ever |
| Chart height | >= 120dp (vs 74dp for a root sparkline) |
| Hero | 40sp (vs 30sp) |
| Pane gap | 14dp (vs 9dp) |
| Rows | 48dp, readable not dense |

**The gap and the row height are what carry the mode change**, not the panel count. A drilldown
using 22dp rows and 9dp gaps would just be a root with fewer panels on it.

### 6. Floors no build ticket may undercut

| | |
|---|---|
| Body text | 11sp |
| Label / pill | 9sp |
| Feed row | 22dp |
| Tap target | 48dp |
| Tile width | 159dp |
| Pane padding | 9 / 13 / 9 / 9 |

These are floors against **layout**. Daylight legibility is ticket 10's measurement, and a failure
there raises a floor rather than being absorbed.

### 7. Landscape and large text

`MainActivity` declares `screenOrientation="unspecified"` (traced in the manifest), so landscape
happens whether or not it was designed for. At 806 x 360dp the vertical budget collapses to roughly
138dp, less than two stacked panes.

- **Landscape keeps two columns, capped at 480dp, centred.** Four columns across 806dp would produce
  190dp tiles in a layout nobody designed or will check. Capping makes landscape portrait's layout
  in a letterbox: not beautiful, never broken.
- **Above font scale 1.15, tiled roots collapse to a single column.** The 22dp row and the
  7-character hero limit are both computed at 1.0; at 1.3 a half tile cannot hold its own label.
  One rule, not a per-panel reflow.
- **The feed row's height scales with its text.** It must not be pinned in dp while its content
  grows, which is the standard way this bug ships.

### 8. Handed on

- **Ticket 11** gets 584dp and the 7-character half-tile limit as the budget HOME's inventory fits
  inside.
- **Ticket 04's alarm pane** fits a half tile: `QUARANTINED` is 12 characters at 9sp, about 86dp
  against 141dp available. Arithmetic, not rendered.
- **Ticket 10** gets the floors in section 6 as the values to measure.

### Assumptions ledger

| Claim | Tag |
|---|---|
| 360 x 806dp, density 2.0, notch 38x32 centred, 3-button nav, font_scale 1.0 | **`on-device`** - read over ADB from CPH2471, 2026-08-14 |
| `screenOrientation="unspecified"` | `traced` - read in `AndroidManifest.xml` |
| 328dp interior, 159dp half, 103dp third | `reasoned` - arithmetic on the measured width |
| Hero character counts (17 / 7 / 4) | `reasoned` - assumes 0.6em mono advance, which ticket 02 **measured** for Martian Mono |
| Shell band heights (29 / 31 / 46dp) | `reasoned` - **estimates** from the current components, not measured |
| **584dp content budget** | `reasoned` - derived from the estimates above. **The first number to re-measure once the shell is built**, since every other budget here depends on it |
| Pressed-state collision with the alarm border | `reasoned` - follows from ticket 04's treatment; not seen rendered |
| Nothing was rendered in Compose | - |

## Correction 2026-08-14 (from ticket 14): the budget is 560dp, not 584dp

This ticket named the 584dp content budget as **"the first number to re-measure once the shell is
built, since every other budget on this page is derived from it."** Ticket 14 built the shell and
measured it. The mechanism worked as intended.

**Measured: 560dp. This ticket's derived figure was 24dp high.**

Method: `uiautomator` bounds dump of the scrollable NavHost region, cross-checked against
`dumpsys window`'s `mFullConfiguration`. The bands sum to exactly 806dp, which is the
self-check.

### Where the 24dp actually went

| Band | This ticket assumed | Measured |
|---|---|---|
| System chrome (status bar + cutout + nav bar) | 80 (32 + 48) | **76** |
| Shell bands (bezel, status line, Alfred strip, hard keys) | 142 | **170** |
| **Content** | **584** | **560** |

**Note the direction carefully, because the first report of this got it backwards.** The system
chrome estimate was **4dp pessimistic**, not optimistic - the app window is `h730dp` against an
806dp screen, so the bars take 76dp combined, slightly less than the 80dp assumed here.

**The error is entirely in this ticket's own shell band estimates**, which consume 170dp measured
against the 142dp guessed. That is exactly the part this ticket's assumptions ledger tagged
`reasoned` and flagged as estimates. Nothing about the nav bar or the notch was wrong; the device
readings in section 0 all hold.

### What this changes downstream

- **The half-tile width is unaffected.** 360dp, the 32dp bezel cost and the resulting 328dp interior
  are all horizontal and were measured, not estimated. **The 7-character hero limit stands.**
- **Vertical budgets move.** Any build ticket laying out against 584dp gets 560dp instead. That is
  roughly one 22dp feed row's worth, so the practical effect is small but it is real.
- **The "hero plus one full row of tiles above the fold" check still passes** comfortably at 560dp.
- **The 20-row-per-pane ceiling** was computed against a ~640dp content area and is unaffected by
  this correction, since it was never derived from the 584dp figure.

### One more device fact worth keeping

The app is **not edge-to-edge**: `themes.xml` sets opaque `statusBarColor` and
`navigationBarColor`, there is no `enableEdgeToEdge` or `WindowCompat` call anywhere, and
`targetSdk` is 34. Android therefore reserves both system bars entirely outside the Compose tree,
which is why shell-level chrome needs no `windowInsetsPadding` of its own to avoid them. Tagged
`traced` plus `on-device`.
