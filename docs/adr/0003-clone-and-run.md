---
status: locked
decided: 2026-07-30
decided-by: Kevin
source: "CLAUDE.md §2"
tags: [adr]
---

# 3. A stranger must be able to clone, sideload, and use it

## Standing

LOCKED, and partly BLOCKED. The Drive OAuth finding below is unresolved.

## Context

The project is public. If it only works on Kevin's machine with Kevin's credentials, it is not really public, it is a screenshot.

## Decision

A stranger clones the repo, sideloads, signs in with their own account and their own key, and it works. This, not cost, is the reason Firestore is ruled out.

## Consequences

- `gradle.properties` must never hardcode `org.gradle.java.home`. Midnight AI's did, and it broke on any machine without Android Studio at that exact path.
- **Open blocker:** Drive's Android OAuth client is keyed to package plus SHA-1 signing cert, so a stranger's own build fails authorization. Unresolved since 2026-07-31.
- Every feature must be checkable against this: if it needs something only Kevin has, it is wrong.
