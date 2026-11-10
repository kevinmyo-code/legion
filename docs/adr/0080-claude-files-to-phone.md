---
status: accepted
decided: 2026-11-09
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-11-09 - Claude files to phone (Kevin)]]"
tags: [adr]
---

# 80. Files Kevin names come from his PC to his phone, open there, and leave the engine at once

## Standing

**When Kevin asks the Claude session on the Alienware for files by name ("find the scanned form on
my PC and send it to my phone"), naming them is the yes: the files come to his phone and open
there, and the engine's copy is deleted the moment the phone has them, 24 hours after upload at the
latest.** He shares one on with Android's own share sheet (WhatsApp or anything else) by his own
tap. LEGION never sends a delivered file to anyone. It is `deliver: files` on a Claude session task
(ADR 0067), beside `email_self` and the page delivery, never instead of them.

## Context

Kevin, 2026-11-09, paraphrased because the repo is public: send me the photos from an event, or
find a scanned document on my PC, by asking Claude; *"The file lands on the phone and Alfred opens
the file on the phone. I then send via WhatsApp if I want."* ADR 0067 already let a helper list found files and, on a second yes ("want
it on your phone?"), upload one into Postgres for 30 days and save it into the phone's Downloads.
That was two yeses for a request that already named the file, kept a scan of an ID on the engine for
a month, and left it in a folder every app with media access can read.

## Decision

- **Naming is the yes, within caps.** The phone sends `deliver: files` only when Kevin asked for
  files on his phone (`claude_session` `files: true`, or "Files to my phone" on the Claude session
  screen). At a `done` finish the engine requests every found file at once. More than 20 files or
  100 MB, or a find that is not clearly what he named, and the task asks first
  (`needs_confirmation`, one question with the count and size); the worker enforces the caps, the
  engine checks them again. His yes is a `confirms` follow-up, which inherits `files` and lifts the
  caps for that task. The ask list is unchanged: delivering to Kevin's own phone is not "send".
- **Found by name, not read.** The helper finds files by name, folder, date, type and size. It
  opens a document's contents only when it must to tell two candidates apart, and then says so in
  the summary. ADR 0076 still governs ID numbers: a scan Kevin asks for is his file, found and
  carried, never transcribed.
- **Engine storage is private and short.** The bytes wait in a private bucket
  (`LEGION_CLAUDE_FILES_BUCKET`, `deploy/claude_files/setup_bucket.sh`: uniform access, public
  access prevention, a one-day lifecycle delete, `objectAdmin` for the engine's account on that
  bucket only), keyed by household, task and file id plus a random part, never the file's name.
  Without a bucket set they wait in the task file row, deleted on the same triggers. No signed URL
  is minted: the phone downloads through the engine with its device token.
- **Only the asking member.** Download and `received/` answer only the member who sent the task;
  anyone else, in the household or not, gets a 404 as if the file did not exist. Every worker and
  phone still holds an ordinary household token (ADR 0067's accepted v1 gap).
- **Deleted on receipt, and at 24 hours.** The phone calls `POST tasks/{id}/files/{file_id}/received/`
  once a file is on disk, and the engine deletes its copy. From 24 hours after upload the download
  is refused at read time, and the hourly `claude_files_prune` deletes what is left. The row (name,
  size) stays for the task's 30 days so the result still reads right.
- **On the phone: app-private, never Downloads.** Files land in `filesDir/claude_files/`, excluded
  from cloud backup and device transfer. The 2026-11-01 ruling "keep auto-save to Downloads" stays
  for the older `send_file` path; a delivered file may be an ID scan, so it is not copied into
  public storage. Kevin saves or sends one with Share. Kept 30 days, then deleted.
- **Opened.** If a LEGION screen is in front the viewer opens at once: images shown in it, a PDF or
  document handed to its reader through ACTION_VIEW and LEGION's FileProvider, Share on every
  file. Otherwise a notification (the count, never a file name) opens it on a tap. The spoken result
  names the files and sizes and says whether they are open; it asks nothing. With announcements off
  the files still come and still open or notify; only the spoken line is silenced.
- **WhatsApp.** The 2026-11-01 "WhatsApp dropped" ruling stands except for this: the phone offers
  Android's normal share. No WhatsApp API, no unofficial client, no recipient chosen by LEGION.
- **The work laptop never takes it** (ruling 5): refused on create and assign, never claimed by it.
- **Nothing logs a file name, path or content**, on the engine, the worker or the phone: ids, counts
  and sizes only.

## Consequences

- Kevin creates the bucket once (`bash deploy/claude_files/setup_bucket.sh <project>`), sets
  `LEGION_CLAUDE_FILES_BUCKET` in `deploy/cloudrun/.env`, redeploys, and runs
  `deploy/cloudrun/install_schedule.py` so the hourly prune is scheduled. Until then files wait in
  Postgres, still deleted on receipt and at 24 hours.
- One file is capped at 25 MB (Cloud Run's request limit), as every task file already was.
- The phone fetches only while LEGION is open or the assistant service runs (the existing poll); a
  file that lands while neither is running waits on the engine, up to its 24 hours.
- Amends ADR 0067's "Files" bullet for this path only; its offered-file flow is unchanged.
