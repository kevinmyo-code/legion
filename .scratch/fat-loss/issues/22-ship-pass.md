---
map: fat-loss
ticket: "22"
title: "Ship pass: deploy, enter the profile, run it on the phone"
type: test
status: open
status-detail: ""
blockers: ["02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13", "14", "15", "16", "17", "21"]
blocked-by: ["[[02-seventh-aspect-in-the-docs]]", "[[03-body-profile]]", "[[04-energy-calculator]]", "[[05-trend-and-waist]]", "[[06-adaptation-proposals]]", "[[07-saved-meals]]", "[[08-engine-tools]]", "[[09-web-body]]", "[[10-phone-coach-reads]]", "[[11-plan-generator-uses-the-estimate]]", "[[12-digest-energy-and-protein]]", "[[13-monday-weekly-line]]", "[[14-weight-goal-tracked]]", "[[15-meal-target-partial-and-honest-accept]]", "[[16-routing-and-voice-guide]]", "[[17-coach-tone-and-appetite]]", "[[21-body-screen-build]]"]
tags: [ticket]
---
# Ship pass: deploy, enter the profile, run it on the phone

Owed by Kevin or the orchestrator after the engine deploy:

1. Apply the `body_coach` migration on the engine.
2. Kevin enters his profile (web `/body` or the phone), or the orchestrator writes it through
   `PUT /api/body/coach/profile/`. His numbers never enter the repo.
3. Set the weight goal (`metric_key = bodyweight_lbs`) with his first milestone.
4. Weigh in for two weeks; check the trend, the Energy card's basis turning to "from your trend",
   and a proposal when off band.
5. A Monday at 8 am: the weekly line, then "stop the weekly weight line".
6. "Log my usual breakfast" after saving one.
