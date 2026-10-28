package com.kevin.legion.ui.settings

/** The row's copy, kept here so a test can hold it to "never name the assistant". */
internal object ConversationLogCopy {
    const val TITLE = "Conversation log"
    const val SUBTITLE = "Records what you and the assistant said, each tool call and how long replies " +
        "took. What your mail says is left out. Sent to your household's engine, where it can be read " +
        "with manage.py device_logs. Applies from the next connection."
    const val KEEP = "Keep on this phone and send to the engine"
    const val KEEP_DETAIL = "Kept here until the engine has it; sent at the end of each conversation " +
        "and every 3 minutes during one."
    const val MIRROR = "Also write to the developer log (logcat)"
    const val MIRROR_DETAIL = "Only a computer connected over ADB can read it."
    const val SEND_NOW = "Send now"
    const val SENDING = "Sending..."
}
