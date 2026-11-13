---
map: daily-loops
ticket: "03"
title: "Canvas grades on the phone: screen and voice"
type: build
status: built
status-detail: "built 2026-11-13: HOME > More > Grades (ui/grades/, DjangoGradesBackend); voice rides ask_engine. 180 tests green incl. 14 new, detekt 290. Owed: engine deploy with grades, then on the phone: list, a course, a hidden-total course, what-i-need with B, a bad target, and offline; large font scale"
blocked-by: []
tags: [ticket]
---

# Canvas grades on the phone

A Grades view (per course, per assignment) and voice through the engine tools from ticket 02
("what's my grade in X", "what do I need on the final for a B"). Both call the same engine routes
(ADR 0035). Server down: the last read is shown with its date, said in words.
