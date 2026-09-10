---
map: django-engine
ticket: "05"
title: "Media: receipt photos and voice-note audio on a volume, served only to a token"
type: build
status: open
status-detail: "NARROWED 2026-09-10 (Kevin): 'receipt photos are a one time use, we dont need to keep it in memory.' The RECEIPT half of this ticket is retired - a photo is an input to the section 4 gate, not the evidence it leaves behind, and rule 8's anchors (printed total, subtotal, tax) live in their own columns already. So no R2, no durable store, and Cloud Run's ephemeral MEDIA_ROOT is no longer a data-loss bug for pantry. VOICE-NOTE AUDIO IS A DIFFERENT QUESTION and is NOT retired by this: ADR 0041 makes a recording Kevin starts first-party and says the audio, transcript and summary are retained together, so audio still needs somewhere durable to live. Repointed 2026-09-05: media goes to Cloudflare R2 (S3 API), not a local MEDIA_ROOT on the box. Ticket 07."
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

## Repointed 2026-09-05

Ticket 07 decided the compute box is a household machine behind Cloudflare Tunnel, and media on a
single home disk dies with the box. Photos and audio go to **Cloudflare R2** (10 GB free, no egress
fees, S3-compatible) through `django-storages`' S3 backend; `MEDIA_ROOT` is dev-only. R2 credentials
are environment variables like every other secret, never in the tree. The §4 posture is unchanged: a
receipt photo is the evidence behind its rows and is deleted with them.
