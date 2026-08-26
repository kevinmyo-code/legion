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

## What to decide and build

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
