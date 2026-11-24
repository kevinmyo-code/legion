package com.kevin.midnightai.service

/**
 * The one sentence that keeps a slow tool call from sounding like a dead head unit.
 *
 * **Why this is prompt copy and not a timer.** `LiveSetup.withBlockingBehavior` stamps
 * `behavior: BLOCKING` on every declaration, so the Live model cannot speak while a tool call
 * runs. A code-timed filler would need a second audio channel racing the barge-in flush and the
 * idle hold, which is the phone-only turn-taking port ADR 0104 says not to build again on the head
 * unit. Asking the model to say the line BEFORE it calls the tool fits the architecture it already
 * has, and the five sub-agent declarations have carried this sentence since they were written.
 *
 * **What it covers.** Every tool on a timeout tier above
 * `LiveSessionController.toolTimeoutMs`'s 10 s default: the four investigating sub-agents and the
 * four blocking-network tools at 45 s, and `ask_engine` at 150 s. A 150 s silent `ask_engine` in a
 * moving car was the worst dead-air case on the unit. `SlowToolPromptTest` reads the tiers from
 * `toolTimeoutMs` itself, so tool 57 on a long tier fails the build until it carries the line too.
 *
 * **What it does not do.** Nothing makes the model obey, and nothing inspects the spoken audio.
 * This is a request, the same admission `SpeechHonesty` makes about its own clause. The test
 * guards the line's presence in every long-tier description, never that Moose said it.
 *
 * One constant rather than nine literals: the sentence is one job, and the test needs one spelling
 * to look for.
 */
object SlowToolPrompt {
    const val INSTRUCTION =
        "Tell the driver you're digging into it before calling this - it takes a little while."
}
