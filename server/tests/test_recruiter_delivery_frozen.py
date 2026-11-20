"""The recruiter demo speaks with a FROZEN copy of Alfred's delivery text
(`assistant.companions.RECRUITER_DELIVERY_FROZEN`), so an edit to the phone's Alfred (and its engine
twin, `personas.ALFRED`) reaches members at once and the public demo only on Kevin's word. No
database: the companion is resolved from code."""

from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime

from assistant import companions, personas
from assistant.prompt import build_system_prompt_parts


def _recruiter_body() -> str:
    body, _tail = build_system_prompt_parts(
        companion=companions.recruiter_companion(),
        first_name="",
        now=datetime(2026, 11, 20, 12, 0, tzinfo=UTC),
        utc_offset_minutes=0,
        recruiter=True,
        cascade=True,
    )
    return body


def test_the_recruiter_speaks_with_the_frozen_delivery_and_members_with_alfreds():
    assert companions.recruiter_companion().delivery == companions.RECRUITER_DELIVERY_FROZEN
    member = companions.resolve(None, None, persona_key=personas.ALFRED.key)
    assert member.delivery == personas.ALFRED.delivery
    body = _recruiter_body()
    assert companions.RECRUITER_DELIVERY_FROZEN in body
    if personas.ALFRED.delivery != companions.RECRUITER_DELIVERY_FROZEN:
        assert personas.ALFRED.delivery not in body


def test_an_edit_to_alfred_does_not_reach_the_demo(monkeypatch):
    edited = replace(personas.ALFRED, delivery="An edited delivery line nobody has heard yet.")
    monkeypatch.setitem(personas.BY_KEY, personas.ALFRED.key, edited)
    assert companions.resolve(None, None, persona_key=edited.key).delivery == edited.delivery
    assert companions.recruiter_companion().delivery == companions.RECRUITER_DELIVERY_FROZEN
    assert edited.delivery not in _recruiter_body()
