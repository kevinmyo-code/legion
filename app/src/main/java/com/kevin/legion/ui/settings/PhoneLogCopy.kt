package com.kevin.legion.ui.settings

/** The row's copy, kept here so a test can hold it to "never name the assistant". */
internal object PhoneLogCopy {
    const val HEADER = "DEVELOPER"
    const val TITLE = "Send this phone's logs to the engine"
    const val DETAIL = "Everything the app writes to its log, crashes included, so a problem can be read " +
        "on the engine without a cable. Passwords, keys, email addresses, phone numbers and what your " +
        "mail says are removed on this phone first. At most 4 MB a day; kept 30 days on the engine."
    const val OFF_DETAIL = "Nothing is captured or sent, and what this phone was holding is deleted."
    const val SEND_NOW = "Send now"
    const val SENDING = "Sending..."
}
