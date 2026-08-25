---
map: hardening
ticket: "06"
title: "Build debt from backend-erp Phase 1: supabase-kt held back, Compose force-pinned"
type: task
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Build debt from backend-erp Phase 1: supabase-kt held back, Compose force-pinned

## Question

Phase 1 of the backend-erp arc added supabase-kt and a Ktor engine. Both landed green, and both
left a compromise in the build files that is documented in place but should not be permanent.
Filed here so neither becomes folklore.

### 1. supabase-kt is pinned to 3.6.0, not the current 3.7.0 (`built`)

3.7.0 is the latest release (published 2026-07-20, confirmed against the GitHub releases API). It
cannot be used here yet. Its published `.kotlin_module` metadata carries **Kotlin metadata binary
version 2.4.0**; this project's Kotlin Gradle plugin is **2.1.0**, whose compiler accepts up to
2.2.0. `kaptGenerateStubsDebugKotlin` fails outright against 3.7.0 for `auth-kt`, `postgrest-kt`,
`supabase-kt` and `kotlin-reflect` at once, because `postgrest-kt-android:3.7.0` takes
`kotlin-reflect:2.4.0` as a hard dependency rather than a version Gradle merely bumped.

**This is `built`, not `reasoned`:** both 3.7.0 (fails) and 3.6.0 (compiles clean) were actually
built during Phase 1.

The fix is a project-wide Kotlin bump, which was correctly refused as a side effect of adding an
HTTP client: `org.jetbrains.kotlin.plugin.compose` is pinned to the same `kotlin` version, so the
bump moves the Compose compiler plugin and puts the entire existing Compose surface at risk. That
deserves its own ticket with its own verification, which is this one.

**Do not bump Kotlin casually.** The screenshot suite is the thing most likely to catch a
regression and the thing most likely to be dismissed as flakiness when it does.

### 2. Compose is held at 1.7.0 by `resolutionStrategy.force` (`traced` symptom, `reasoned` cause)

Adding supabase-kt/ktor drifted the `androidx.compose.ui` atomic group to 1.9.0, which broke four
pre-existing Roborazzi screenshot tests: `androidx.compose.foundation` 1.9.0 requires
`IndicationNodeFactory`, and `androidx.compose.material` 1.6.7's ripple still implements the older
`Indication` interface. `app/build.gradle.kts` now forces the group back to 1.7.0.

Two things about that fix are verified and worth keeping: 1.7.0 is **not** a conservative
downgrade guess, it is what this tree already resolved to before supabase-kt existed
(`navigation-compose:2.8.0` requests `animation:1.7.0` and atomic-group alignment carries it up),
and forcing the BOM's nominal 1.6.7 instead **fails the build** because real app code in
`ui/KeyScreen.kt` and `ui/spotify/SpotifyRows.kt` uses `autoCorrectEnabled`, which `ui-text` only
gained after 1.6.7.

**What is NOT established: which dependency actually requested 1.9.0.** `dependencyInsight` reports
conflict resolution "between versions 1.9.0, 1.7.0 and 1.6.7" while showing no explicit requester
above 1.7.0. The build file's own comment says this plainly and tags it `reasoned`, blaming AGP
cross-variant consistent resolution as the likely mechanism. **A `resolutionStrategy.force` whose
cause is unknown is a silencer, not a fix** - it works, and it will keep working right up until it
hides something that matters.

## Why this is worth a ticket rather than a comment

`resolutionStrategy.force` beats every constraint unconditionally, including a future legitimate
one. The next person to add a library that genuinely needs Compose above 1.7.0 will get a confusing
failure with no signal pointing at this block. The comment in the build file is good and should
stay; a comment cannot page anyone.

## Fix

- [ ] Trace the actual requester of `androidx.compose.ui` 1.9.0. `./gradlew app:dependencies
      --configuration debugUnitTestRuntimeClasspath` across configurations, or a build scan, which
      shows requesters the console insight elides.
- [ ] Replace the blanket `force` with the narrowest thing that works: a `constraint` with a
      documented `because`, or an exclusion on whichever dependency pulls 1.9.0. Keep `force` only
      if nothing narrower does the job, and say so.
- [ ] Bump Kotlin to a version accepting metadata 2.4.0, move the Compose compiler plugin with it,
      then return supabase-kt to 3.7.0 and drop the pin comment.
- [ ] Consider moving Compose onto a newer BOM in the same pass, so the forced version and the
      BOM's preference stop disagreeing.

## Verification

- [ ] `./gradlew compileDebugKotlin -Pnokey`, `testDebugUnitTest`, `compileDebugAndroidTestKotlin`
      and `assembleDebug -Pnokey` all green.
- [ ] The four Roborazzi screenshot tests that caught this pass without any `force` in place.
- [ ] The suite count is at least what it was when this ticket was filed (2,576) with no test
      deleted to make it pass.
- [ ] `supabaseKt` reads 3.7.0 and the deviation note in `libs.versions.toml` is removed rather
      than left describing a state that no longer exists.
