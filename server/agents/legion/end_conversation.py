"""The `end_conversation` control both voice doors offer the model (engine-brain switch plan,
slice E0): its declaration and the instruction its call answers with.

Lifted unchanged from the web door (`assistant/cascade.py`, ADR 0102), which imports them from
here; the agent turn picks them up in slice E3. A no-data control: it reaches no engine tool. The
stop phrase that closes a turn by itself is `agents.legion.speech_rules.is_stop_utterance`.
"""

from __future__ import annotations

# The phone's `end_conversation` (spec 18): the description and the sign-off instruction, with
# "he" made "they" (the web serves every member and a visitor).
END_TOOL = "end_conversation"
END_DECLARATION = {
    "name": END_TOOL,
    "description": (
        "End the conversation when they are DONE with you: 'never mind', 'nothing', "
        "'I'm good', 'that's all', 'go away'. 'That will be all' ALWAYS means this, "
        "however politely. 'Go to sleep', 'sleep', 'go dormant', 'stand down' mean this "
        "too: about YOU going quiet, not their bedtime; no goodnight. Say one dry sign-off "
        "line in your own register in the same turn; it closes when you finish. Do NOT "
        "call this when they are answering 'no' to something you offered, declining a "
        "suggestion, or pausing."
    ),
    "parameters": {"type": "object", "properties": {}},
}

END_INSTRUCTION = (
    "Sign off now with one dry line in your own register (a <chuckle> before it is fine), "
    "about this conversation, then "
    "stop. The conversation ends when you finish speaking."
)
