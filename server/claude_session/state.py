"""The read-time half of the state machine (map: "Computed at read time").

Three facts are never written down, because writing them would need a job
running on a clock, and a fact that is only true once that job has run is a
fact that is wrong in between:

- a machine with no heartbeat for 90 s is **offline**;
- a running task past 30 minutes is **failed** ("stopped after 30 minutes");
- a running task whose machine has been silent for 10 minutes is **failed**.

Every read path asks `effective_status` and `machine_state` with one `now`.
A worker that finishes a task this module already calls failed is refused
(`views.FinishView`), so a late report can never resurrect it.
"""

from __future__ import annotations

import datetime

from claude_session.models import (
    FAILED,
    IN_FLIGHT,
    MACHINE_OFFLINE,
    OFFLINE_AFTER_SECONDS,
    SILENT_FAIL_MINUTES,
    TASK_CAP_MINUTES,
    ClaudeMachine,
    ClaudeTask,
)


def is_online(machine: ClaudeMachine | None, now: datetime.datetime) -> bool:
    if machine is None or machine.last_seen_at is None:
        return False
    return now - machine.last_seen_at <= datetime.timedelta(seconds=OFFLINE_AFTER_SECONDS)


def machine_state(machine: ClaudeMachine, now: datetime.datetime) -> str:
    return machine.reported_state if is_online(machine, now) else MACHINE_OFFLINE


def failure_reason(task: ClaudeTask, now: datetime.datetime) -> str | None:
    """Why an in-flight task is failed as of `now`, in words, or None."""
    if task.status not in IN_FLIGHT:
        return None
    started = task.started_at or task.created_at
    if started is not None and now - started > datetime.timedelta(minutes=TASK_CAP_MINUTES):
        return f"Stopped: it ran past the {TASK_CAP_MINUTES} minute limit."
    machine = task.machine
    if machine is not None:
        last = machine.last_seen_at or started
        if last is not None and now - last > datetime.timedelta(minutes=SILENT_FAIL_MINUTES):
            return (
                f"Failed: the {machine.name} went silent for more than "
                f"{SILENT_FAIL_MINUTES} minutes while it was running."
            )
    return None


def effective_status(task: ClaudeTask, now: datetime.datetime) -> str:
    return FAILED if failure_reason(task, now) else task.status
