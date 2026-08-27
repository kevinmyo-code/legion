package com.kevin.legion.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * A one-line `WHY` stamp that expands an explanation on tap, and is collapsed by default
 * (command-center ticket 13 finding 2).
 *
 * **The problem this replaces.** Several screens rendered paragraphs of doc-comment-register prose
 * permanently - "A file that doesn't state its own account (a CSV export) takes the account mapped
 * to the folder it's in..." - occupying a screenful above the data the screen exists to show. Read
 * once, scrolled past forever. The ticket's rule is explicit and worth restating here because it is
 * what stops this becoming a delete: **the words survive; they stop being furniture.** Nothing that
 * carries information is removed by this component, it is re-housed one tap away.
 *
 * **One composable, so every screen's help behaves identically.** The ticket asked for exactly this
 * rather than a `?` hand-rolled per screen - five slightly different disclosure behaviours is how a
 * user learns not to trust any of them.
 *
 * **Collapsed by default, and that default is load-bearing for tests.** A screenshot baseline
 * captures the collapsed state deterministically; an expanded-by-default row would reintroduce the
 * furniture it exists to remove. [initiallyExpanded] exists for a caller that genuinely needs the
 * text up front (a first-run consent surface, say) and should stay rare.
 *
 * **State survives rotation but not navigation** - `rememberSaveable`, deliberately, so re-opening a
 * screen returns to the compact reading rather than to whatever the last visit left open. Help that
 * silently stays expanded forever is the furniture again, one config change later.
 *
 * @param text the explanation. Keep it the sentence a person needs, not the doc comment - this
 *   component re-houses prose, it does not license writing more of it.
 * @param label the collapsed stamp. Defaults to `WHY`; pass a more specific one where the screen
 *   has several help rows and "why" alone would be ambiguous.
 */
@Composable
fun HelpRow(
    text: String,
    modifier: Modifier = Modifier,
    label: String = "WHY",
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(initiallyExpanded) }
    val sem = LocalLegionSemantics.current
    // The stamp says which way the tap goes, in a character rather than only in colour - the same
    // "in words, never a glyph alone" instinct CLAUDE.md sec 4 rule 7 applies to provenance. A
    // caret alone would leave a screen reader announcing "WHY" with no indication it is actionable,
    // which is what the contentDescription below covers.
    val marker = if (expanded) "-" else "+"
    Column(modifier.fillMaxWidth()) {
        Text(
            "$marker $label",
            style = LegionType.stamp,
            color = sem.faint,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .semantics {
                    contentDescription =
                        if (expanded) "Hide the explanation: $label" else "Show the explanation: $label"
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        AnimatedVisibility(
            visible = expanded,
            // Phone-only lifted the head-unit frame-clock motion ban (CLAUDE.md sec 2), so this is
            // an ordinary Compose transition rather than a hand-driven one.
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = sem.faint,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * [HelpRow]'s collapsed-stamp text, exposed so a test can assert the collapsed state without
 * hardcoding the same literal in two places.
 */
const val HELP_ROW_DEFAULT_LABEL = "WHY"
