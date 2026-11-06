---
map: body-coach-2
ticket: "13"
title: "The plan: draft with Alfred, lock it in, adjust as he goes; Alfred sets the target"
type: build
status: built
status-detail: "built 2026-11-06 on feat/body-coach-2: body_plans (migration 0005), plan/ routes, four engine tools, versions from accepted proposals, the checklist line, GoalPlanAgent writes no target; owed: engine deploy with body_coach 0004 and 0005, then a lock on the phone"
blockers: ["01"]
blocked-by: ["[[01-decisions]]"]
tags: [ticket]
---
# The plan: draft with Alfred, lock it in, adjust as he goes; Alfred sets the target

Kevin, 2026-11-06: *"basically formulating a plan, working together with alfred, then locking it in
and making adjustments as i go with alfred is the goal"* and *"i want alfred to set my calorie
target"*. Ruling in `decisions.md` the same day.

## Engine

- `body_plans`, one row per version, private to its member (`owner_user`, like the profile):
  status draft / active / superseded / discarded; kcal, protein, rate; who set the target
  (Alfred, or Kevin himself); the programme state; the meal-plan approach; nudge settings; a
  one-line reason; the estimate's inputs beside the figures it gave.
- `GET plan/`: the active plan, any draft, the history. With a complete profile and no plan at all
  it builds the first draft (the energy proposal's GET-that-proposes pattern) and asks to lock it.
- `POST plan/draft/`: a draft from the active plan (or today's programme) and choices: the
  programme's choices, a rate, "let Alfred set it". The rate is 0.5% a week by rule.
- `POST plan/<id>/lock/`: the draft becomes the active version; the meal target for today is
  written from it (unless Kevin set his own), the programme takes its state, the profile its rate.
- `POST plan/<id>/discard/`.
- Accepting an energy or programme proposal makes a new version of an active plan, with a reason.
- Engine tools: `get_body_plan`, `draft_body_plan`, `lock_body_plan`, `discard_body_plan`.
- `GET workout/` carries a pending draft as `plan_draft` for the checklist's one line.

## Phone

- The checklist line: a pending draft with Accept (lock) and Dismiss (discard).
- `GoalPlanAgent` no longer proposes or writes a meal target.

## Verification

- A complete profile and no plan: GET builds one draft, writes no meal target; lock writes one.
- 0.5% a week by rule; the clamp holds; protein unchanged.
- A target Kevin sets after a lock is his: a new draft keeps it unless "let Alfred set it".
- Accepting an energy or programme proposal makes version n+1 with a reason; history lists both.
- Tenancy: another member's plan is never read; recruiter refused.
