package com.kevin.legion.ledger.parsers

/**
 * Port of Project Andromeda's `duo_ledger.bronze.errors`
 * (`~/PycharmProjects/Andromeda`), same hierarchy and same meaning.
 */
sealed class StatementParseException(
    message: String,
    /**
     * Plain-language restatement of [message] for the quarantine row, which
     * ticket 08 resolution §6 requires be "the gate's own reason in plain
     * language". [message] itself is a DIAGNOSTIC - it names fields, prints
     * raw cents, and is what a log or a bug report wants; the first device
     * render of a quarantine row put it in front of the user verbatim
     * ("statement totals withdrawal=5000 deposit=200000 do not match...")
     * and it read as a stack trace.
     *
     * Null means no plain-language version was written for this site, and
     * [com.kevin.legion.ledger.parsers.StatementDispatcher] falls back to
     * [message]. That fallback is deliberate: a raw diagnostic in the UI is
     * bad, but silently swallowing the reason is worse.
     */
    val userMessage: String? = null,
) : Exception(message)

/** Raised when a stated balance doesn't tie to the prior balance plus amount. */
class BalanceContinuityException(
    message: String,
    userMessage: String? = null,
) : StatementParseException(message, userMessage)

/**
 * Raised when a file doesn't match the parser's expected bank layout. No
 * [StatementParseException.userMessage]: this one never reaches a user, it is
 * the signal to try the next parser and then the LLM path.
 */
class UnrecognizedLayoutException(message: String) : StatementParseException(message)

/** Raised when a statement cannot be parsed exactly as printed, for any other reason. */
class GenericStatementParseException(
    message: String,
    userMessage: String? = null,
) : StatementParseException(message, userMessage)

/**
 * Raised when a file states no account of its own (BofA's mid-cycle CSV
 * export, per [BofaCsvStatementParser]'s doc comment) AND the per-account
 * Drive subfolder it was found in has no mapping - CLAUDE.md §4's "never
 * guess" applied to account identity. Thrown only AFTER every reconciliation
 * anchor has already passed, so a file with both a numeric mismatch and no
 * mapping reports the numeric problem first (the more actionable one - the
 * mapping is a one-tap fix, a numbers mismatch may mean a corrupted export).
 */
class UnmappedAccountException(
    message: String,
    userMessage: String? = null,
) : StatementParseException(message, userMessage)
