package com.kevin.legion.ledger.parsers

/**
 * Port of Project Andromeda's `duo_ledger.bronze.errors`
 * (`~/PycharmProjects/Andromeda`), same hierarchy and same meaning.
 */
sealed class StatementParseException(message: String) : Exception(message)

/** Raised when a stated balance doesn't tie to the prior balance plus amount. */
class BalanceContinuityException(message: String) : StatementParseException(message)

/** Raised when a file doesn't match the parser's expected bank layout. */
class UnrecognizedLayoutException(message: String) : StatementParseException(message)

/** Raised when a statement cannot be parsed exactly as printed, for any other reason. */
class GenericStatementParseException(message: String) : StatementParseException(message)
