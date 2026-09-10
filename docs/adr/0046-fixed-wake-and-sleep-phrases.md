---
status: accepted
decided: 2026-09-10
decided-by: Kevin
source: "[[decisions#2026-09-10 - \"Excelsior\" wakes and \"that will be all\" sleeps]]"
tags: [adr]
---

# 46. A fixed wake and sleep phrase pair, implemented in two different places

## Standing

**"Excelsior" wakes the assistant and "that will be all" ends the conversation.** The wake phrase is
a Vosk grammar entry; the sleep phrase is a match on the Live input transcript. They are not
symmetrical implementations and they cannot be made symmetrical.

## Context

The wake word was `"hey <companion name>"`, built at runtime from `CompanionProfile.name`. Kevin
asked for a fixed summon/dismiss pair out of pop culture instead of a name, and picked Stan Lee's
sign-off with the canonical butler's dismissal.

The obvious reading of "a wake phrase and a sleep phrase" is two entries in one grammar. That is
impossible here. `MicArbiter` grants the microphone to exactly one claimant and `LIVE_TURN` outranks
`WAKE_WORD`, so `GeminiLiveSession` preempts `WakeWordEngine` the instant a conversation starts.
**During the only window in which a sleep phrase means anything, Vosk is not listening.** A sleep
entry in the grammar would compile, ship, and never fire.

## Decision

Wake stays where it belongs: a phrase in the Vosk grammar, matched with no model involved, owned by
`WakePhrases.grammar`. Sleep runs against the accumulated `inputAudioTranscription` text instead -
the same signal `CrisisDetector` already reads - and runs through two paths rather than one. The
model's `end_conversation` tool is primary, because it is what lets the companion answer "Very good,
sir" before the socket closes. `WakePhrases.isSleepPhrase` is a deterministic backstop behind it,
because a dismissal that works only when the model chooses to obey is not a dismissal.

Both paths set the same `dismissAfterTurn` flag, consumed at `TurnComplete`. They **arm rather than
fire**, so the sign-off is never cut off mid-word, and because the flag is one idempotent boolean,
the two paths cannot fight when both trigger on the same turn.

## Consequences

- **The single-word rule is exempted, not relaxed.** Custom-wake-word ticket 07's field data
  (2026-07-19) is why the phrase was two words: a bare word false-triggers on conversation, radio
  and podcasts. That finding was about *common short names*; "excelsior" is a four-syllable Latinate
  word that essentially never occurs in speech, so the rule's purpose is met by the word itself
  rather than by a "hey" prefix.
- **"hey \<name\>" is retained behind the new phrase, and is meant to be temporary.**
  `vosk-model-small-en-us-0.15` compiles its lexicon into binary FSTs with no readable word list, so
  there is no way to confirm off the device that "excelsior" is recognisable at all. A grammar
  containing only an unrecognisable word listens forever for something nobody can say, which is the
  silent failure this engine's history keeps rediscovering ("hey moose", the empty-grammar refusal).
  It is one line to delete once the phone confirms the new phrase fires.
- **The deterministic backstop anchors at the END of the utterance and therefore misses politeness.**
  "That will be all, thanks" does not match it. This is deliberate: the model is told those are
  dismissals, and a missed backstop costs one extra turn while a false one hangs up on someone
  mid-sentence. `WakePhrasesTest` pins both sides so the behaviour cannot drift silently.
- **A blank companion name is no longer a reason to refuse to listen.** The fixed phrase does not
  depend on a name, so `WakeWordEngine.start`'s empty-grammar refusal is now unreachable in
  practice. It is kept anyway.
