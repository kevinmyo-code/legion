package com.kevin.legion.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Shows every `Modifier.testTag` under this node to uiautomator as a `resource-id` (phone-verify
 * ticket 04), so `tools/phone tap --tag vault-message` and `docs/screen-map.md` can name a control
 * by a tag that does not change when its words do. Without it Compose test tags are visible only to
 * Compose's own test APIs, and an agent on the real phone has only text and descriptions to go on.
 *
 * It changes no pixels and no behaviour: it is one semantics flag read by accessibility, so the
 * Roborazzi screenshots are unaffected. **It covers one window.** A dialog or a bottom sheet is its
 * own window, so a root that wants its tags exposed sets this again (the chat sheet does).
 */
fun Modifier.exposeTestTags(): Modifier = semantics { testTagsAsResourceId = true }
