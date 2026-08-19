---
status: locked
decided: 2026-07-30
decided-by: Kevin
source: "CLAUDE.md §2"
tags: [adr]
---

# 1. Phone-only, and the head-unit constraints go with it

## Standing

LOCKED. Not reopenable without Kevin.

## Context

Midnight AI targeted cheap AOSP head units. That single premise had bent years of decisions: an Android 8-10 ceiling, a ban on any animation not driven by the frame clock (animator scale is 0 on those units), and no usable ADB.

## Decision

LEGION is an Android phone app. Head units may still install it; they no longer constrain design.

## Consequences

- The frame-clock-only motion ban and its ban list (the old ui/Motion.kt, deleted in the pivot) are dead. Normal Compose animation is allowed.
- The ADB blackout is lifted, so on-device verification is now possible and is therefore expected.
- This is the parent of most other pivot decisions. Reopening it reopens them.
