---
map: alfred-ota
ticket: "04"
title: "versionCode, private bucket, releases, publisher"
type: build
status: built
status-detail: "server and tools built 2026-11-05 on feat/alfred-ota-engine; versionCode built 2026-11-05 on feat/alfred-ota-phone as yyDDDHHmm (yyMMddHHmm overflows). Owed: setup_bucket.sh + LEGION_APK_BUCKET + deploy (Kevin); keystore backup"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# versionCode, private bucket, releases, publisher

## What

- **versionCode.** For `fast` and `release`, `app/build.gradle.kts` derives it from the UTC build time as `yyMMddHHmm`, which fits in an Int until 2121. `debug` stays 1. versionName is `<date>-<short sha>`.
- **Signing (decision 5).** Keep the debug key for `fast`.
  - The signer is pinned in `deploy/release/signer.sha256`, which is committed; a stranger's clone pins their own key.
  - The publisher refuses any APK whose signer SHA-256 differs.
  - Kevin backs up `~/.android/debug.keystore`; it is on the owed list.
- **Bucket.** A private `gs://legion-apk-<project>` with uniform access and nothing public.
  - The Cloud Run service account gets `storage.objectViewer` on the bucket.
  - It also gets `iam.serviceAccounts.signBlob` on itself, so it can sign V4 URLs.
- **Engine.** A new app, `server/releases/`.
  - Model: `AppRelease(app: phone|headunit, version_code, version_name, sha256, size_bytes, object_name, notes, signer_sha256, published_at, published_by)`.
  - Releases belong to the engine, not to a household. One engine holds one household's builds. Owners publish; any member can read.
  - `GET /api/releases/latest?app=phone` (member): the newest release.
  - `GET /api/releases/{id}/download` (member): a V4 signed URL, valid 15 minutes.
  - `POST /api/releases/` (owner): records a release after the upload.
  - Add `google-cloud-storage` to `requirements.txt`.
- **Publisher.** `tools/release_apk.py --app phone --apk <path> --notes "fixes the double announce"`. It builds nothing. It:
  1. checks the signer cert matches the pinned one
  2. checks the versionCode is higher than the latest release
  3. computes the sha256
  4. uploads with `gcloud storage cp`
  5. POSTs the release with the owner token
  6. prints what it did

  Kevin runs it via `!`. The worker's `legion-chores` skill may run it, but only after Kevin's yes (decision 4).

## Verification

- pytest:
  - the release endpoints
  - publishing is owner-only; reading is open to members
  - the signed-URL builder, using a fake signer
  - versionCode must increase
- A publisher dry-run against a fake APK checks the signer and the sha256.
- One Gradle run checks the versionCode derivation.

## Built (2026-11-05, branch `feat/alfred-ota-engine`): server and tools only

- `server/releases/` (`AppRelease`, migration 0001, three endpoints, `signing.py`),
  `tests/test_releases.py`; `tools/release_apk.py`, `tools/tests/test_release_apk.py`;
  `deploy/release/signer.sha256` (debug key, `fd819ada...aac01`, keytool and apksigner agree);
  `deploy/release/setup_bucket.sh` (written, not run); `LEGION_APK_BUCKET` passed by
  `deploy/cloudrun/_common.py`.
- Releases are engine-wide: no `household_id`, named in `household.tenancy.ENGINE_WIDE_TABLES`.
- **Not built: the versionCode derivation.** It goes with the phone tickets. **The spec's
  `yyMMddHHmm` does not fit an Int:** 2611051200 is more than 2147483647 (and Android's own cap
  of 2100000000), and has been since 2022, not 2121. A form that fits: `yyDDDHHmm` (day of year,
  e.g. 263091200; under 2.1e9 until 2099), or minutes since a fixed epoch. The engine accepts
  1 to 2147483647.
- Owed by Kevin: `bash deploy/release/setup_bucket.sh legion-engine-260906`, set
  `LEGION_APK_BUCKET` in `deploy/cloudrun/.env`, deploy (migrates `releases` 0001), back up
  `~/.android/debug.keystore`.
- Verification: pytest for the endpoints, owner-only publish, the signer seam with a fake and
  versionCode increase: done. Publisher dry run against a real `fast` APK: done (signer matched,
  sha256 computed). The Gradle run for the versionCode: deferred with the versionCode work.

## Built (2026-11-05, branch `feat/alfred-ota-phone`): the versionCode

- `app/build.gradle.kts`: `fast` and `release` take `yyDDDHHmm` UTC (`androidComponents.onVariants`),
  `debug` keeps 1. versionName `<yyyy-MM-dd>-<short sha>`; `git` missing or failing gives `-dev`.
- **Deviation from the spec, needs Kevin's nod:** `yyDDDHHmm`, not `yyMMddHHmm`, for the overflow
  above. Still monotonic; under Android's cap until 2099.
- Verification: the Gradle run is the `assembleFast` at the end of the phone branch; the APK's
  badging is in the branch report.
