---
map: django-engine
ticket: "09"
title: "Android: HTTP implementations of the twelve backend interfaces, behind the Hilt binding"
type: build
status: open
blockers: ["04"]
blocked-by: ["[[04-domain-api-and-changes-feed]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Android: HTTP backends

**Owned by the Android terminal, not this one.** Sequenced after `.scratch/architecture/issues/
03-bind-the-backend-interfaces.md`, because once the twelve `*Backend` interfaces are Hilt-bound the
swap is one module, not 42 call sites (`grep -rl SupabaseClientProvider app/src/main` today).

The contract is `server/openapi.yaml` from ticket 04. Not this ticket's prose.

## Files

| New | Replaces | Notes |
|---|---|---|
| `backend/ServerConfig.kt` | `SupabaseConfig` | base URL only. Refuses `http://`. `KeyScreen` loses the anon-key field |
| `backend/ServerClient.kt` | `SupabaseClientProvider` | one Ktor `HttpClient` with `ContentNegotiation(json)`, the `Authorization: Token` header, a 15 s timeout, `X-Legion-Api` major check |
| `backend/ServerAuth.kt` | `SupabaseAuth` | `POST /api/auth/login` with `device_name = Build.MODEL`. Keeps the sealed `SignInResult` / `MembershipResult` / `UserIdReadiness` shapes so callers do not change |
| `backend/ServerSession.kt` | `SupabaseSession` | the token in `KeyVault`, fail closed, same as today |
| `backend/HttpEventsBackend.kt` and eleven siblings | `Supabase*Backend` | each method is one call. DTOs unchanged where the wire shape is unchanged, which ticket 04 made the rule |
| `backend/ChangePoller.kt` | `*Realtime.kt`, nine files | on foreground: pull every domain; every 60 s while foreground; after every outbox drain. Same `*Sync.pull` entry points |

`supabase-kt` and its four modules leave `libs.versions.toml` in the same change; Ktor and
kotlinx-serialization stay, they were transitive and are now direct.

## What does not change

- `*Sync.kt`, `*Outbox.kt`, `*Reconcile.kt`, the cursors, Room. The push and pull machinery was
  written against the interfaces, not the client.
- `LiveToolbox` and every controller.
- `EPISODIC_EXCLUDED_TOOLS`, the honesty clause, the gate DTOs.

## Tests

The 27 existing `backend/` tests keep passing against fakes. Add `HttpEventsBackendTest` with Ktor's
`MockEngine` for one domain, asserting the exact path, method and body for each interface method;
the other eleven follow the same file. Then one instrumented run against a local `docker compose up`.

## Verification

- [ ] `compileDebugKotlin -Pnokey` and the suite green with supabase-kt gone from the classpath.
- [ ] On the phone, signed in against the local server: one event by voice appears in the web app;
      one tick on the web app appears on the phone within 60 s.
- [ ] Airplane mode: a voice-added event lands in the outbox and drains on reconnect, once.
- [ ] `grep -r "io.github.jan" app/src` empty.
