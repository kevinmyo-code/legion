---
type: build
status: open
blocked_by: []
map: backend-erp
---

# A backup that restores rows pointing at files it never kept

**Found 2026-08-26, the hard way: I destroyed Kevin's three receipt photos and then discovered the
backup had never covered them.**

## What happened

A `connectedDebugAndroidTest` run uninstalled the app (that task installs the app plus a test APK
and removes both when it finishes), taking `/data/user/0/com.kevin.legion/files/` with it. The
`DatabaseSnapshot` restore worked exactly as designed and brought the database back in full.

**`DatabaseSnapshot` copies the `.db` file and nothing else.** Its own doc comment is explicit:
`PRAGMA wal_checkpoint(TRUNCATE)` plus a plain file copy of the live `.db`, uploaded as
`legion_backup_<epochMillis>.db.gz`. No `files/` directory, ever.

So the restore returned rows whose `sourceImagePath` points at paths that no longer exist. The
database looks whole and is, in one specific sense, lying.

## Why this is not only a pantry problem

Any row that stores a path into app-private storage has the same shape. Known today:

- `pantry_receipts.sourceImagePath` - the receipt photos, now permanently gone. This is what made
  ticket 08 unfixable: re-ingesting the photos would have recovered the real tax figures.
- `data/EnginePhotoStore.kt` and `data/PantryPhotoStore.kt` are the two writers into `files/`; every
  path they hand out has this exposure.

**The restore test that ran earlier in this session passed and did not catch this**, because it
verified record COUNTS and integrity, which were genuinely correct. A count-based check cannot see a
dangling path. That is the same blind spot as section 4 rule 6, one layer out: the check was
satisfiable without the thing it was meant to protect being present.

## RULED 2026-08-26: database-only, said in words, with photos made durable elsewhere

Kevin delegated this ("go with your recommendations"). My call, on his authority:

**`DatabaseSnapshot` stays database-only and does NOT grow to cover `files/`.** Two reasons, and the
second is the stronger one:

1. The snapshot's generation guard, pre-restore aside and single-`.db.gz` naming are load-bearing
   and were verified once already on a real device. Turning it into a multi-file archive puts all of
   that back in play to solve a problem that has a better answer.
2. **Ruling 10 as amended already sends receipt photos to Supabase Storage.** Once that lands, the
   photos are durable in the household's own project - replicated, restorable, and reachable from
   the laptop surface, which the original ruling listed as the whole point. A second copy inside a
   Drive backup would be redundant storage of the same bytes on the free tier.

**But it must SAY it is database-only.** The restore screen and the backup screen both need to state
that images are not covered, and a restored row whose file is missing must render as missing IN
WORDS rather than as a broken image or a silent blank - the same posture as section 4 rule 7
condition 3. A backup that quietly restores rows pointing at nothing is a backup reporting a safety
it is not providing, which is the failure ticket 04 named.

**Sequencing:** this ticket now depends on the Supabase Storage work, and the two should be done
together. Doing the wording half alone is still worth it and can go first, because it is true today
and will stay true.

**What this does NOT fix:** the three receipt photos already destroyed. Nothing does. That loss is
recorded in ticket 08.

## Superseded: what was open

1. **Does the backup carry `files/` at all?** Photos are the bulk of what would move and the free
   tier's storage is finite. "Database only, and say so" is a legitimate answer - but then it must
   SAY so, and the restore must warn that images are not covered.
2. **If yes:** the snapshot becomes an archive rather than a single `.db.gz`, which changes the
   naming, the generation guard and the pre-restore aside logic. Not a small change; that logic is
   load-bearing and was verified once already.
3. **Either way, a restored row whose file is missing must render as missing IN WORDS** rather than
   as a broken image or a silent blank. Same rule as section 4 rule 7 condition 3.
4. **Ticket 01 ruling 10 as amended sends receipt photos to Supabase Storage.** If that lands first,
   the exposure shrinks to whatever stays device-local - but Storage is not installed in
   `SupabaseClientProvider` today and pantry's cutover deliberately left `photo_object_path` NULL.
   Sequencing these two together is probably cheaper than solving them separately.

## The operational lesson, already applied

Never run `connected*` Gradle tasks against Kevin's daily-driver phone. They uninstall. An emulator
is the place for instrumentation tests, and `installDebug` is what puts a build on the real device.

## THE WORDING HALF IS BUILT 2026-08-26 (`ccb868a`). The ticket stays OPEN for the Storage half.

Both surfaces now say the backup is database-only: `DriveSyncRows.DatabaseBackupRow` carries the
caveat the same way it already carries "only while the app is open", and `DriveBackupResolver`'s
restore and local-recovery confirmations say images are not covered BEFORE the user commits.

**Item 3's distinction turned out to be representable already, and checking it found a live bug.**
A blank `sourceImagePath` genuinely means "never had a photo on this device" - `PantryReconcile`
writes `""` for a receipt whose bytes live in Supabase Storage - while a non-blank path with no file
at it means "had one, now gone". `EngineWidgets.PhotoWidget` already distinguished them correctly.
**`GeneratedFormScreen`'s `FieldType.PHOTO` did not:** it rendered "PHOTO ON FILE" from a non-null
path string alone, never touching the disk, so after any restore or reinstall it asserted a photo
that was not there. Fixed via `ui/generated/PhotoFieldResolver.kt`, a plain injectable-check
function so the three states and their wording are unit-testable rather than buried in a composable.

That bug is the same shape as the failure this ticket is about: a check satisfiable without the
thing it protects being present. The count-based restore verification passed while every path
dangled; the form screen's null-check passed while every file was gone.

**Still owed, and why the ticket stays open:** receipt photos to Supabase Storage (ticket 01 ruling
10 as amended). Storage is not installed in `SupabaseClientProvider` today and pantry's cutover
deliberately left `photo_object_path` NULL. Until that lands, the photos have NO durable copy
anywhere - the wording is honest about the gap, it does not close it.
