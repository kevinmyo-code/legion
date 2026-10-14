# Midnight AI

An Android **head-unit launcher with a voice companion**, built for one car: a 1998 Jeep Cherokee XJ
with a cheap double-DIN Android unit and an ELM327 OBD dongle. Personal project, solo dev, sideloaded.

- **Cruise** is the home screen, a split deck laid out for the 800x480 head unit: a left console
  column (clock, companion portrait to tap and talk, telemetry chips, phone-link chip, dock) and,
  on the right, a now-playing card beside a 2 x 5 grid of pinned Spotify playlists.
- **The companion** is Uncle Moose (renamed from Zero 2026-10-11), on Gemini Live over a WebSocket,
  using the driver's own Gemini key.
- **Third client of the LEGION engine.** It signs in to Kevin's household Django server and reaches
  lists, calendar, notes and body logs through one voice tool, `ask_engine` (personal to-dos live there; the car to-do feature was retired 2026-10-13). Car data stays local.

Package `com.kevin.midnightai`. Rules and architecture live in [`CLAUDE.md`](CLAUDE.md), current
state in [`memory/MEMORY.md`](memory/MEMORY.md).

## Status (as of `dev`, 2026-10-14)

Working, per Kevin's 2026-07-16 drive on the Cherokee: OBD and voice. Almost everything built since
is compile-and-test verified only, not run on the head unit. `CLAUDE.md` §14 tracks the gap.

| Built | Notes |
|---|---|
| Cruise home, Lights Out night HUD, screen saver | Cruise is the HOME activity. Split music deck (`ui/MusicDeck.kt`, `ui/PinnedPlaylistGrid.kt`) replaced the cassette deck 2026-10-13; the console fits 800x480 with 11sp label floors and 56dp touch targets. Dock: lights out, day/night, mute, REAR camera, apps, setup |
| Voice loop | Push-to-talk only. Tap the Cruise avatar, or long-press in Lights Out |
| OBD (ELM327, Bluetooth classic and BLE) | Live PIDs, DTC read/clear, freeze frames, 30s telemetry to Room, MPG, readiness |
| Music | Phone over Bluetooth (MediaSession/AVRCP), Media3 mixtape player, BYO Spotify (App Remote + Web API, with a Web API playback fallback; voice never opens the Spotify app) with voice parity: play by type, queue, like, shuffle, repeat, seek, browse. Now-playing listens to every media session. Up to 10 pinned playlists |
| Trusted clock | `time/TrustedClock`: own network UTC and zone behind every now/today, never the head unit's clock |
| Voice playback | Jitter-buffer feeder and backlog-aware drain watch (choppy Live audio fix); a failing voice turn no longer crashes the launcher; hardware volume keys re-applied through the app's own path |
| Themes | Night mode is a purple palette with light text; Summer coast uses near-black text. `PaletteContrastTest` guards contrast |
| Two-phone switcher | `bluetooth/`: pick which paired phone plays |
| Maintenance, logbook, saved places, Shelly garage relay | Logbook and places are reached from Settings, Your car |
| Two-pane Settings | Rail: Phones & audio, Music, Engine, Companion, Your car, Look, System |
| Reverse camera tile | Hands off to the vendor camera activity; no camera code here |
| Cross-device sync | Opt-in, the driver's own Google Drive `appDataFolder` |
| LEGION engine client | `backend/engine/`: sign-in, models generated from `openapi/legion-schema.yaml` |
| Seeded art | Bundled in `res/drawable-nodpi`, made with `art/seed/*.py`. The app generates no images |

Removed (do not look for them): in-app navigation and the Mapbox SDK (2026-10-11), the wake word and
ambient cabin listening (2026-10-11), in-app image generation (2026-09-27), the Firebase entitlement
broker (2026-07-16).

## Build

Requirements:
- Android Studio with its bundled JBR. `gradle.properties` pins `org.gradle.java.home` to
  `C:/Program Files/Android/Android Studio/jbr`; change it locally if yours lives elsewhere.
- Gradle wrapper 9.4.1, AGP 9.2.1, Kotlin 2.2.10. `compileSdk` 36, `minSdk` 24, `targetSdk` 34.

`local.properties` (gitignored). Keys read by `app/build.gradle.kts`:

| Key | Needed for |
|---|---|
| `sdk.dir` | Every build |
| `GEMINI_API_KEY` | Optional. Baked into dev builds as `BuildConfig.GEMINI_API_KEY`. Omit it, or pass `-Pnokey`, to build with no key |
| `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` | `release` builds only. Personal sideload keystore, not a Play upload key |

The Spotify client ID, Shelly auth key and engine address are entered in the app, not at build time.
`app/google-services.json` (Firebase Crashlytics) and `app/libs/spotify-app-remote-release.aar` are
committed.

Commands:

```
./gradlew assembleDebug            # debug
./gradlew assembleFast             # sideload build for the head unit
./gradlew assembleRelease          # needs the RELEASE_* keys
./gradlew assembleDebug -Pnokey    # no baked Gemini key
```

Build types:

| Type | Debuggable | Signed with | Use |
|---|---|---|---|
| `debug` | yes | debug key | Desk work |
| `fast` | no | **debug key** | The head unit. Release speed, but installs over a debug install and keeps its data, Keystore secrets and sign-ins |
| `release` | no | `release.jks` | Needs an uninstall first if a debug build is on the device |

ABI splits are on and there is no universal APK. Each build writes two:
`app-arm64-v8a-<type>.apk` and `app-armeabi-v7a-<type>.apk`. Pick the one matching the device.

Every Kotlin compile runs `openApiGenerate` first, generating the engine models into
`app/build/openapi-generated/`. To change the contract, see [`openapi/README.md`](openapi/README.md).

## Tests

```
./gradlew testDebugUnitTest            # JVM unit tests, app/src/test
./gradlew connectedDebugAndroidTest    # needs a device; includes the Room MigrationTest
```

Unit tests cover OBD parsing and a simulated OBD pipeline, maintenance scheduling, drive
reassignment, sync merge and codec, the Spotify matching and controller logic, `ask_engine`, the
engine transport (MockWebServer, Robolectric for `EngineConfig`), and the crisis detector. Read
totals from the JUnit XML under `app/build/test-results/`, not the console. No UI tests.

No-car OBD testing: [`tools/elm327_xj_sim/`](tools/elm327_xj_sim/README.md) is an ELM327 emulator with
an XJ profile, for an emulator AVD.

On the head unit ADB is blocked (vendor menu hides developer options), so Crashlytics is the only
crash and event telemetry there.

## Layout

```
app/src/main/java/com/kevin/midnightai/
  ai/              Gemini prompt assembly (AriaBrain), sub-agents, companion profile and identity, KeyVault
  service/         foreground service, Gemini Live session, LiveToolbox (voice tools), ask_engine, trivia
  vehicle/         OBD transport and parsing, telemetry, maintenance, diagnostics agents, garage relay
  media/           now-playing, MusicRouter, mixtape player, Spotify App Remote and Web API
  bluetooth/       phone link and the two-phone switcher
  location/        location, saved places, reminders, phone GPS beacon
  sync/            Drive appDataFolder sync
  backend/engine/  LEGION engine auth, HTTP, MCP client
  billing/         local unlock state and runtime mode (no network)
  data/local/      Room database, entities, DAOs, migrations
  ui/              Compose screens; ui/theme/ holds the AriaColors/AriaType/AriaDimens tokens
app/schemas/       committed Room schema JSON, one per version
openapi/           vendored LEGION engine OpenAPI contract
art/seed/          scripts and sources for the bundled art
tools/             ELM327 simulator, voice-sample generator
memory/            MEMORY.md (state) and library/ (history, mostly frozen)
archive/           superseded context files from earlier project names
```

Room schema version: read `version =` in `app/src/main/java/com/kevin/midnightai/data/local/CarDatabase.kt`.

## Branches

`dev` is the trunk; feature work branches off it. `main` moves only through a PR Kevin merges.
