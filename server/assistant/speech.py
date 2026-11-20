"""Moved to `agents/voice/speech.py` (the engine's one speech stage, for every voice door).

This name stays the SAME module object, not a copy: importing `assistant.speech` hands back
`agents.voice.speech`, so a caller or test that reads or patches `assistant.speech.X` reads or
patches the one module the speech stage runs from. New code imports `agents.voice.speech`.
"""

import sys

from agents.voice import speech as _speech

sys.modules[__name__] = _speech
