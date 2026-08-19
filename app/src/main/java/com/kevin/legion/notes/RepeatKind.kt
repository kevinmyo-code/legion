package com.kevin.legion.notes

/**
 * The TEXT value stored in [com.kevin.legion.data.local.ListItem.repeatKind]. `.name` is what's
 * persisted (matching the rest of this schema's "widening a TEXT-stored enum is not a migration"
 * convention - see `CarDatabase`'s v5 note) - never reorder or rename an existing constant.
 */
enum class RepeatKind {
    DAILY, WEEKLY, MONTHLY_ON_DATE, YEARLY,
}

/** The TEXT value stored in [com.kevin.legion.data.local.ListItem.repeatEndKind]. */
enum class RepeatEndKind {
    NEVER, ON_DATE, AFTER_COUNT,
}
