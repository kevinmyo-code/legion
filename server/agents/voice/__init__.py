"""The engine's speech stage, for every voice door (Kevin, 2026-11-19: the engine is the one voice
brain, speech-to-text and text-to-speech on the engine for every client).

- `speech.py`: Gemini speech-to-text (its prompt and its no-speech guards) and text-to-speech.
- `speaker.py`: a reply's sentences to speech, batched, in order, as `audio` events.
- `filler_audio.py`: filler lines kept in memory per voice.
- `stage.py`: the per-turn speech record (timings, spend on the member's cap).

Moved from the web door (`assistant/cascade.py`, ADR 0102), which calls them as it did.
"""
