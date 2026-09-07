---
map: django-engine
ticket: "13"
title: "The head unit becomes the third limb, on a generated client"
type: build
status: built
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# The head unit limb

Kevin, 2026-09-07: *"i want to add that too as a limb for the django app. it reads the django just
like ours"*, and on how: *"generate both clients from the OpenAPI schema."* Standing rule in
[[0045-the-head-unit-is-a-third-limb]]; this is the build that followed it.

**Two repos.** The engine and the phone are here. The head unit is
`github.com/kevinmyo-code/MIDNIGHT_AI`, package `com.kevin.midnightai`, checked out on the second
machine at `C:\Users\kevin\AndroidStudioProjects\AiApp`. Its work sits on `feat/django-limb`, cut
from `fix/gemini-audit-followups` (81cdcd9) because **that** branch is the repo's real tip -
`dev` and `main` are 93 commits behind it, which is worth knowing before anyone merges anything
there.

## What the schema turned out to be

Ticket 04 pointed downstream work at "the contract is `server/openapi.yaml`". The schema was being
served, but it was **half a schema**, and the half that was missing was the half the phone already
depends on. drf-spectacular silently drops any `APIView` it cannot introspect, and it had dropped
every hand-rolled one:

| | before | after |
|---|---|---|
| named component schemas | 15 | 35 |
| `/api/events`, `/api/checklists/*` | path only, untyped body | typed |
| `/api/changes` | path only, untyped body | typed |
| auth login / logout / me | path only, untyped body | typed |
| `securitySchemes` | **empty** | `deviceTokenAuth` |
| generation result | 84 errors, 61 warnings | 0 errors, 0 warnings |

A client generated before that fix would have been typed for the eight body tables and blind for
`events` and `checklists` - the only two aspects `EngineTransport` currently routes to Django. It
would have compiled.

The fix is annotation only, no behaviour change: `household/schema.py` (an
`OpenApiAuthenticationExtension` for `DeviceTokenAuthentication`), `api/errors.py`, `extend_schema`
on every hand-rolled view, explicit `operation_id`s where list and detail collided, and
`ENUM_NAME_OVERRIDES` for the `provenance` enum - which needed **two** entries, not one, because
once a field name maps to more than one choice-set every hash needs its own override or they all
fall back to numeric suffixes. Server suite after: **213 passed, 41 skipped.**

## Where the schema lives, and why it is committed

`openapi/legion-schema.yaml` in this repo, vendored as a copy into the head unit's own
`openapi/legion-schema.yaml`. Not `server/openapi.yaml` as ticket 04 wrote it; that path was never
created.

Committed rather than fetched at build time, because a build that reaches the network to learn its
own types is not clone-and-run. The cost is that it can go stale, and **a stale schema is worse
than no schema** - the models still compile, so a renamed field arrives as `null` forever, with
nothing crashing and nothing logged. `tools/schema_check.py` regenerates and compares, and fails on
drift or on any drf-spectacular warning.

Nothing enforces that the head unit's copy matches. That is deliberate - the limbs ship on separate
cadences and a head unit pinned to last month's contract is legitimate - so the checksum in each
repo's `openapi/README.md` is what makes the skew visible to a person.

## Models generated, transport hand-written

Codegen stops at the models on purpose. `EngineHttp`/`EngineFailure` sort every non-2xx into four
branches none of which a caller can mistake for a success, which is CLAUDE.md section 7's
outcome-verb rule expressed as a type. openapi-generator's `ApiClient` throws untyped exceptions, so
generating the whole client would trade that guarantee away for the code it saves.

The head unit ports that design onto the OkHttp it already had rather than importing Ktor, using
`suspendCancellableCoroutine` + `Call.enqueue` so a coroutine cancellation actually cancels the
in-flight call. Its token is encrypted at rest through the app's own existing Keystore `KeyVault`.

## Two build traps, both found by building

1. **Do not generate into `build/generated/`.** That subtree is AGP's. A source dir inside it broke
   resource merging here and surfaced as `Unresolved reference 'ic_car_gauge_coolant'` in
   `LegionMediaLibraryService.kt` - a file nowhere near the change, with the drawable present and
   tracked the whole time. Attributed by building the same worktree stashed (green) and unstashed
   (red). Both repos now generate into `build/openapi-generated/`.
2. **AGP rejects a task Provider in its SourceSet API**, so the idiomatic
   `kotlin.srcDir(tasks.named(...).map { ... })` fails outright, and Gradle 9 then refuses to infer
   the dependency from a bare path. The producer/consumer edges are declared by hand instead -
   including to KSP, which is the task that actually broke.

## Verification

- legion: `compileDebugKotlin -Pnokey` green with 35 generated models compiled in.
- head unit: `compileDebugKotlin` green; 8 transport tests pass from the JUnit XML, covering
  401/403 to `Unauthorized`, 4xx to `Refused` with the server's body verbatim, 5xx to `Refused` and
  specifically not `Unreachable`, `IOException` to `Unreachable`, an undecodable 2xx to `Malformed`,
  and a `+` in a cursor percent-encoding to `%2B` rather than collapsing to a space.
- server: 213 passed, 41 skipped.

## Owed, and not done here

**No UI.** The head unit has a typed client and nothing on screen that uses it. It also has no
`EngineTransport` equivalent, no per-aspect backends, and no decision on whether it keeps a Room
replica or ever writes rather than only reads.

**Fleet is not routed on the engine** (ticket 04 excluded it on identity/volume grounds), so the
aspect a head unit most obviously wants is not available to it yet. That work is somebody else's
lane and is in flight.

**Neither app has been run on hardware.** Everything above is compile-and-suite evidence.
