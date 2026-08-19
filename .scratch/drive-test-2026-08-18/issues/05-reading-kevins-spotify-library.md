---
map: drive-test-2026-08-18
ticket: 05
title: "Reading Kevin's own Spotify library"
type: grilling
status: resolved
status-detail: "decided and built 2026-08-18 (a19ab4d, 18e881c); on-device QA and the re-auth still owed"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Reading Kevin's own Spotify library

## Question

Kevin, on a real drive, 2026-08-18: *"music > can we look up our favorite or recent albums?"*

Today the answer is no, and the obstacle is not the code.

### What exists, traced 2026-08-18

| Piece | State |
|---|---|
| Music tools | Three: `control_music` (`service/LiveToolbox.kt:405`), `control_volume` (`:416`), `play_music` (`:429`) |
| Auth | A PKCE **user** token, with the refresh token stored encrypted (`ai/CompanionProfile.kt:443`, `KEY_SPOTIFY_REFRESH_TOKEN_ENC`) |
| Scopes granted | **Exactly one:** `user-read-private` (`media/SpotifyWebApi.kt:80`) |
| Data endpoints called | **One:** `/v1/search`, with `type` hardcoded to `track` (`media/SpotifyWebApi.kt:354`) |

What Kevin asked for needs three scopes that are not held:

| Want | Scope required |
|---|---|
| Saved albums | `user-library-read` |
| Recently played | `user-read-recently-played` |
| Top artists and tracks | `user-top-read` |

### The obstacle, and the actual thing to grill

`isAuthorized` compares the **granted** scope string against the **current** `SCOPES` constant:

```
fun isAuthorized(context: Context): Boolean =
    CompanionProfile.spotifyRefreshToken(context).isNotBlank() &&
        authPrefs(context).getString(KEY_GRANTED_SCOPE, null) == SCOPES
```

`media/SpotifyWebApi.kt:112-114`, with the grant recorded at `:181`. That equality check is
**deliberate and correct** - its own doc comment explains it exists so a stale grant reads as
unauthorized instead of minting tokens the API will refuse.

The consequence is the problem. **Adding any scope invalidates Kevin's existing grant**, and
`play_music` hard-fails until he completes a browser OAuth redirect again. Which is precisely the one
thing that cannot be done from the driver's seat.

So the feature is cheap and the migration is not. Shipping this without planning the re-auth means
the music tool breaks, in the car, with no warning, on whatever morning the update lands.

## Grill

1. **When does the re-auth land, so it is never the morning of a drive?** Options to argue between:
   ship the scope change and let it break until Kevin notices; prompt for re-auth at a moment that is
   provably not in the car; or make the failure itself honest and self-explaining ("your Spotify
   connection needs re-approving, do it on the Setup screen"). The third is the minimum; is it
   enough? Note that `isAuthorized` failing is already surfaced by an AUTHORIZE button on the Setup
   screen, so the machinery exists - the question is whether the driver ever sees it before the tool
   fails at speed.
2. **Should LEGION keep its own local history of what it played, or only read what Spotify already
   knows?** Spotify's recently-played is authoritative for everything Kevin plays anywhere, including
   on his phone outside the car. A local table only knows what LEGION played, and would be a **new
   table** - the mixtape tables and the music-taste ledger were retired in the pivot and the current
   schema confirms they are gone (`data/local/CarDatabase.kt:10`). Argue whether "what we listened to
   on drives" is a genuinely different and useful question from "what I listened to", or whether it
   is the retired music-taste ledger returning under a new name.
3. **Does `play_music` search gain album and playlist types?** Related but separable from the read
   feature: see the honesty bug below.

## The honesty bug found in passing

`play_music`'s description (`service/LiveToolbox.kt:430`) advertises:

> "Play something specific by name - **a song, artist, album, or playlist**"

The search behind it is **tracks-only** (`type=track`, `media/SpotifyWebApi.kt:354`). So asking for
an album gets one song off it, and the model has been told it can do something it cannot.

**Either the description is wrong or the search is.** Both are one-line fixes and they are opposite
fixes, so this is a decision, not a task. Note that this is the same class of failure as
[ticket 03](03-no-navigation-capability.md) - the model asserting a capability that is not there -
except here the model was told the lie by the tool description rather than inventing it, which is
worse. It belongs in [ticket 04](04-what-the-assistant-says-when-it-cannot.md)'s evidence.

## Verification

- [ ] Confirm the current grant genuinely holds only `user-read-private` on the real device, rather
      than inferring it from the constant. `on-device`.
- [ ] After any scope change: confirm `play_music` fails **visibly and explicably**, not silently,
      before re-auth is completed. This is the whole risk of the ticket. `on-device`.
- [ ] Confirm the re-auth browser hop completes and `play_music` recovers, on the phone, before the
      change is considered shipped. `on-device`.
- [ ] Whichever way the `play_music` description bug is resolved: ask for an album by name and
      confirm what happens matches what the description promises.

## RESOLVED 2026-08-18 - Kevin answered all three, and it is built

Asked directly, answered in one line: *"spotify > do it now. legion keeps its own listening
history."*

| Question | Answer |
|---|---|
| When does the re-auth land? | **Now.** He is at a desk, not driving, which is the only condition that mattered. |
| Spotify's history, or LEGION's own? | **Both.** `recently_played` and the top rankings are Spotify's; `legion_history` is LEGION's own. |
| Does `play_music` gain album/playlist? | **Yes** - widen the search, not narrow the description. He asked for albums. |

Built in `a19ab4d` (the history table) and `18e881c` (the library reads and the tool).

**One tool, not five.** `browse_my_music(source, limit)` with `source` in `saved_albums`,
`recently_played`, `top_artists`, `top_tracks`, `legion_history`. There are already 89 tools and a
bloated surface makes the live model worse at choosing; five near-identical declarations would have
made that worse for no gain.

**Whose numbers they are is written into the tool description**, which is the reason this map
exists. Spotify's rankings are named as Spotify's and as covering every device he uses Spotify on.
`legion_history` is named as LEGION's own observation of this device only, and any "favourite"
drawn from it as LEGION's inference from what it happened to see. A failure states its cause and is
never allowed to read as "you have nothing".

**The re-auth trap was handled, not just noted.** `isAuthorized` compares the granted scope string
against `SCOPES`, so widening it invalidates the existing grant by design. The Setup screen would
otherwise have shown a button identical to the one he already pressed; it now reads **"Needs
re-approving"** with copy saying nothing was lost, and both music tools fail in words naming the
same cause.

### Still owed

- **On-device QA. Nothing here has touched a real Spotify account.** The four endpoints, album and
  playlist search against real results, and the re-auth flow are all `reasoned`, never `tested`.
- `MusicPlayHistoryEntry.spotifyUri` is always null - MediaSession metadata carries no URI and App
  Remote's player state is not wired in. Documented on the entity.
- `LIBRARY_LIMIT = 10` is inherited by inference. `SEARCH_LIMIT` was probed value-by-value on
  2026-08-12; this ceiling was not, and says so.
- The v25 to v26 migration test compiles but has never run - `connectedAndroidTest` would uninstall
  the app and take Kevin's real data.
- A `memory/library/decisions.md` entry for the three calls above. Not filed here because that file
  is mid-edit from an earlier session.

