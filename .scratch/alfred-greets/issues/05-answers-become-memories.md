---
map: alfred-greets
ticket: "05"
title: "His answers become memories"
type: build
status: open
status-detail: ""
blocked-by: []
tags: [ticket]
---

# Answers become memories

"The interview went well" after "How did the interview go?" is saved by the existing consolidation
(ADR 0075): visible in Memory, forgettable. Check that greeting turns are not dropped by mistake: a
turn that called a read-through tool is dropped whole, so the travel-tip turn (04) is not
remembered; his answer in the NEXT turn is. Tests for both.
