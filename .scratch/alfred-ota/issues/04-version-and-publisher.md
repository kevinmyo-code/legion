---
map: alfred-ota
ticket: "04"
title: "versionCode, private bucket, releases, publisher"
type: build
status: open
status-detail: "charted 2026-11-05; waits on decisions 4 and 5"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
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
