---
map: spotify-voice
kind: research
researched: 2026-08-19
status: resolved
tags: [research]
---
# What a sideloaded phone app can do with Spotify today

Every claim below was read off live `developer.spotify.com` / `spotify.github.io` on 2026-08-19.
`traced`. Nothing here is `tested` and nothing has run against Kevin's account.

## The three findings that change the design

1. **Discovery is dead for us.** The 2024-11-27 wave killed Recommendations, Related Artists, Audio
   Features, Audio Analysis, Featured/Category playlists and the 30-second `preview_url` for any app
   registered after that date. There is **no supported "play something like this"**. The nearest
   primary-verified substitute is App Remote's `ContentApi.getRecommendedContentItems(type)`, which
   returns Spotify's own home shelves and is **not seeded by the current track**.
2. **App Remote is the answer to the no-active-device problem, and it is the strongest finding
   here.** `SpotifyAppRemote.connect()` starts the Spotify app's own service, and once connected
   "will prevent Spotify from shutting down even if the user is not playing anything". App Remote
   does not require an active device - **it creates one**. The Web API cannot: with nothing active,
   a write has no target, and `PUT /me/player` (transfer) can only move playback to a device Spotify
   already sees online.
3. **Premium splits the Web API cleanly: every `/me/player` WRITE is Premium, no READ is.** Kevin has
   Premium (settled 2026-08-19), so this constrains nothing for him - but it is why the UX is
   deliberately Premium-only.

## Playback: Web API

Two scopes only. `user-read-playback-state` for reads, `user-modify-playback-state` for writes;
`GET /me/player/currently-playing` wants `user-read-currently-playing`, and `GET /me/player/queue`
needs **both** read scopes. All 15 `/me/player` endpoints survived the 2026-02 cull.

Caveats stated on the pages themselves:

- **"The order of execution is not guaranteed when you use this API with other Player API
  endpoints."** Compound voice commands (play, then shuffle, then seek) are racy on the Web API.
  App Remote is the better route for those.
- `device_id` is optional on every write and defaults to the active device. Device IDs "should
  periodically be cleared out and refetched" - **never persist one**.
- A device with `is_restricted: true` "will not accept any Web API commands".
- Seeking past the track length starts the next song.
- Recently-played "currently doesn't support podcast episodes", and `additional_types` (needed to
  see episodes at all) is flagged as possibly deprecated in future.
- Rate limit is a rolling 30-second window with **no published number**; `429` carries `Retry-After`.
  Since July 2026 dev-mode quota is pooled **per developer account**, and exhaustion returns `429`
  with `"reason": "QUOTA_EXCEEDED"`.

## Library and playlists: two migrations already happened

- The per-type `save`/`remove`/`contains` family (`PUT /me/tracks`, `/me/albums`, ...) **and**
  `PUT`/`DELETE /me/following` are **deprecated**, replaced by `PUT /me/library`,
  `DELETE /me/library`, `GET /me/library/contains`. These take **URIs, not IDs**, max 40 per call,
  and cover tracks, albums, shows, episodes, audiobooks, users and playlists in one endpoint.
  **Following an artist is now "save `spotify:artist:...` to library".**
- Playlist creation moved to `POST /me/playlists` (`POST /users/{id}/playlists` was removed), and
  playlist item endpoints moved `/tracks` -> `/items`, with the response field `tracks` -> `items`.
- **`GET /playlists/{id}` returns items only for playlists the user owns or collaborates on.** A
  Spotify editorial playlist cannot be read.
- Search `limit` is now capped at **10** (default 5).

## App Remote: what only it can do

`play(uri)`, `queue(uri)`, `seekToRelativePosition`, `skipToIndex(uri, index)` ("play track 7"),
`toggleShuffle`/`toggleRepeat` (the Web API has no toggle - it needs a read first to compute the new
state), `setPodcastPlaybackSpeed`, `subscribeToPlayerState` (push, no polling, no quota),
`UserApi.addToLibrary`/`removeFromLibrary`/`getLibraryState` (like the current track with **zero** Web
API calls), `UserApi.getCapabilities().canPlayOnDemand` (**the only remaining way to detect Premium** -
`user.product` was removed for dev-mode apps in Feb 2026), `ConnectApi.connectSwitchToLocalDevice()`
(pull playback off the house speaker onto the phone), and `ImagesApi` for local artwork.

What App Remote **cannot** do: search by name, read the library, read playlists, read history. So the
division is fixed: **Web API resolves a name into a URI; App Remote makes sound come out.**

Recommended sequence for any voice play intent, `reasoned` from traced primitives, not run:
`isSpotifyInstalled()` -> `connect()` -> `connectSwitchToLocalDevice()` -> `play(uri)`, with
`CouldNotFindSpotifyApp`, `NotLoggedInException`, `UserNotAuthorizedException` and
`OfflineModeException` as four distinct spoken failures.

**Documented lifecycle guidance says connect in `onStart`, disconnect in `onStop`** - "Do not keep
the connection alive when your app is in the background, otherwise Spotify will not be able to
shutdown." A foreground-service assistant wants to hold it anyway. That is a **conscious violation to
decide**, not an API error.

## Development mode, and what it does to clone-and-run

- **5 authenticated users per app**, allowlisted by hand in the Dashboard.
- **The app owner must hold active Spotify Premium for a dev-mode app to function at all** (new,
  Feb 2026).
- Client IDs per developer went 1 -> 25 in July 2026; quota is pooled per developer account.
- **Extended quota is permanently closed to LEGION**: since 2025-05-15 Spotify accepts applications
  only from organisations, requiring a registered business and **250k+ MAU**.

**This collides with CLAUDE.md's clone-and-run requirement and the collision is structural.** An app
registered before 2026-02-11 keeps the grandfathered endpoint set; a stranger cloning LEGION today
registers a **new** Client ID and lands on the **restricted** set, needs their own Premium, and must
allowlist themselves. Design LEGION's Spotify surface against the restricted set, not the
grandfathered one.

## Could NOT verify from primary docs

- `404 NO_ACTIVE_DEVICE` and the `"Player command failed"` reason strings - **absent** from all 15
  player reference pages and from the errors page. Widely cited, not primary-verifiable.
- **Whether App Remote itself requires Premium.** Not stated anywhere. The existence of
  `canPlayOnDemand` implies free accounts connect but get restricted playback. Unverified.
- Whether `PlayerApi.play()` accepts `spotify:episode:` / `spotify:show:` / `spotify:audiobook:`
  URIs. **This one gates the podcast tickets** and is a 15-minute on-device spike.
- Exact scopes and limits for `DELETE /me/library` and `GET /me/library/contains` - no reference
  pages exist, only the migration guide's prose.
- The numeric rate limit. Never published.
- Whether LEGION's own Client ID predates 2026-02-11 and is therefore grandfathered. **Check the
  Dashboard** - it decides how much of the second cull applies today.

The Android SDK still carries a **Beta** banner: "likely to change significantly without warning".
