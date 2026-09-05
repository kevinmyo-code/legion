---
map: django-engine
ticket: "05"
title: "Media: receipt photos and voice-note audio on a volume, served only to a token"
type: build
status: open
blockers: ["04"]
blocked-by: ["[[04-domain-api-and-changes-feed]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Media

Replaces the Supabase Storage bucket (`SupabasePhotoBackend`, `uploadReceiptPhoto(objectPath,
bytes)` / `downloadReceiptPhoto(objectPath)`) and gives voice-note audio the home ADR 0041 promised
it (audio, transcript and summary kept together, deleted together).

## Shape

- `MEDIA_ROOT` is a compose volume, `/data/media`. Layout `receipts/<receipt origin_guid>.<ext>`,
  `voice_notes/<origin_guid>.<ext>`. The `objectPath` the phone already computes is kept as the
  key so the Kotlin caller changes only its transport.
- `PUT /api/media/<path>` raw body, `Content-Type` honoured, 10 MB cap, 201. Idempotent: same path
  twice overwrites, and the receipt row's `photo_path` is untouched because the path did not change.
- `GET /api/media/<path>` streams the file with the stored content type. Household token required;
  Django serves it directly, no `X-Accel` yet, since two users do not need a CDN.
- `DELETE` is not exposed. A photo goes when its row goes: `post_delete` on `Receipt` and
  `VoiceNote` removes the file, in the same request, and the deletion is logged.
- Backups: ticket 06's nightly job tars `MEDIA_ROOT` beside the dump. A dump without the photos is
  a gate whose evidence is gone (section 4 rule 8).

## Verification

- [ ] PUT then GET round-trips bytes and content type.
- [ ] GET with no token: 401. With a revoked token: 401.
- [ ] Deleting a `VoiceNote` row removes its audio file; the test asserts the path is gone.
- [ ] An 11 MB upload: 413, nothing written.
