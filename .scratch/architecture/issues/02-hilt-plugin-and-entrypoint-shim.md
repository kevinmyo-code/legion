---
map: architecture
ticket: "02"
title: "Hilt plugin, @HiltAndroidApp, and the @EntryPoint shim"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Hilt plugin, @HiltAndroidApp, and the @EntryPoint shim

Hilt Gradle plugin on KSP, `MidnightApplication` becomes `@HiltAndroidApp`, `MainActivity`
`@AndroidEntryPoint`. A `@Singleton` module provides `CarDatabase`, the Supabase client, a `Clock`.

**The shim is the whole trick.** 33 `object` controllers are called statically from 583 files. An
`@EntryPoint` interface (`LegionEntryPoints`) exposes the graph to code that is not yet injected, so
an `object` can be converted to a class and its old static call sites replaced with
`EntryPoints.get(context, LegionEntryPoints::class.java).fooController()` in one mechanical pass,
then removed call site by call site as screens gain ViewModels. Nothing has to convert all at once,
and nothing stays half-converted invisibly: `grep EntryPoints.get` IS the remaining-work list.

Verify: build green, suite green, app installs and reaches the home screen on the A25 with no
behaviour change. Record the `EntryPoints.get` count as the baseline for ticket 06.
