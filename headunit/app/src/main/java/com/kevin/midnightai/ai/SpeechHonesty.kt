package com.kevin.midnightai.ai

/**
 * What Moose may claim after a tool call, and what he may not.
 *
 * Ported from the phone's file-scope clause (LEGION `app/.../ai/AriaBrain.kt`, root ADR 0031 and
 * root `CLAUDE.md` section 7: *the assistant never asserts an outcome it did not observe*). The
 * head unit had no general form of this rule: [AriaBrain]'s shared instructions carried the same
 * idea three times, each time bolted to one capability (music, the engine bridge, the garage). A
 * per-capability list is correct only until the next tool lands, and `LiveToolbox` declares 56.
 *
 * **Three properties, each deliberate, each the reason this is one clause and not a list.**
 *
 *  - **It names no tool.** The rule is conditioned on the tool RESULT, so it stays true as the
 *    toolset grows and needs no edit when a tool is added. [SpeechHonestyTest] holds this: the
 *    clause may contain no snake-case token, which is exactly the shape of every tool name.
 *  - **An unsuccessful result is the same as no tool at all.** This is the half that actually
 *    fails in the car. A `log_service` that threw, an `ask_engine` that could not reach the
 *    engine and a Spotify call with the App Remote down all come back with a result; none of
 *    them is an outcome, and "done" after any of them is a false fact about the driver's car or
 *    his lists.
 *  - **It offers the nearest thing he really has**, with the trap named in the text: the nearest
 *    thing must itself be a real tool, or the invention has only moved one sentence later.
 *
 * **This is a prompt rule, and a prompt rule is weaker than a gate.** Nothing inspects the spoken
 * audio; by the time a sentence exists Live has already streamed it to the driver. The head unit
 * has no screen glance to spare either, so a false "that's logged" is rarely caught in the moment.
 * [SpeechHonestyTest] guards the clause's presence and its properties, never its obedience.
 *
 * **What stays in [AriaBrain]'s shared instructions, and why it is not a second path.** The
 * garage's vocabulary constraint ("triggering", never "opening" or "closing") forbids two words
 * even when the call SUCCEEDED, because the relay is a single-button toggle whose outcome nobody
 * can observe; this clause does not cover that. The engine bridge's sentence carries where to sign
 * in. Both say something this clause does not. The music sentence said only what this clause now
 * says generally, so it was removed in the same change.
 */
internal val CANNOT_CLAUSE =
    "If the driver asks for something you have no tool for, say so plainly - and never describe " +
        "it as done, logged, saved, set, started, playing, sent, booked or on its way. Those " +
        "words, and any others that assert an outcome, are yours to use ONLY after a tool call in " +
        "this turn came back successful. A tool that comes back unsuccessful is the same as no " +
        "tool at all: say what did not happen, in words, and never smooth it over. Then offer the " +
        "nearest thing you can actually do - but only ever name something you genuinely have a " +
        "tool for, because inventing a helpful-sounding alternative is the same failure one " +
        "sentence later. Telling the driver you cannot do that is always better than a plausible " +
        "sentence about something that never happened. "
