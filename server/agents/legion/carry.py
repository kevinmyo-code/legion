"""The carry on the engine brain (the owner's ruling, 2026-11-21, ADR 0090 amended): a turn that
read outside text taints the NEXT turn of the conversation too, for that one turn.

- **What counts as having read outside text:** the turn's own taint (`TurnState.taint`, stored as
  `AgentTurn.taint_sources`): a third-party tool (`read_through.THIRD_PARTY_TOOLS`), a relayed
  read-through call, a late answer built on web pages (`late.CARRY`). A taint that is itself only a
  carry (the source names below) does not carry again: one turn, never a chain.
- **When:** `run_turn` reads the previous turn's sources as the turn starts (one query) and adds
  `PREVIOUS_TURN` to this turn's taint, so every write here refuses (`guards.check_call`, and the
  relay's own check), in the carried words when the carry is the only reason.
- **Cleared:** after that one turn is answered, whatever he says, whether or not he names the
  change himself. A turn that was never answered (cancelled: the phone took it, noise, a barge-in)
  neither spends the carry nor counts as the turn before, unless it read outside text itself
  (`turn._taint_before`). Per conversation.
"""

from __future__ import annotations

PREVIOUS_TURN = "previous_turn"
# This turn refused a change HIS words asked for, in its own read, and told him to ask again, so it
# does not carry (the owner's ruling, option A2; re-check R1). A carry-type name: it never carries
# by itself.
REFUSED_HERE = "write_refused"
# Every taint source that is a carry, not a read of this turn's own (`client_facts.CARRY`: the
# phone's last line read outside text; `late.AFTER_CARRY`). Kept as names here, so the guards need
# not import the late-answer runner; a test pins them to their owners' constants.
CARRY_SOURCES = frozenset({"carry", "late_after_carry", PREVIOUS_TURN, REFUSED_HERE})


def carries(previous_taint) -> bool:
    """Whether a turn with these taint sources carries into the next one."""
    taint = previous_taint or ()
    if REFUSED_HERE in taint:
        return False
    return any(source not in CARRY_SOURCES for source in taint)


def note_refused(state) -> bool:
    """A write refused in this turn's own read: `REFUSED_HERE` only when his words in the turn
    asked for a change (`write_intent.asks_for_change` over the transcript alone). True if set."""
    from assistant.write_intent import asks_for_change

    if not asks_for_change(state.transcript):
        return False
    state.add_taint(REFUSED_HERE)
    return True


def only_carried(taint) -> bool:
    """Whether this turn's taint is only a carry (so the carried words are true)."""
    return bool(taint) and all(source in CARRY_SOURCES for source in taint)
