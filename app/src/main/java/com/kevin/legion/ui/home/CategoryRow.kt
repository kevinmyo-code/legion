@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kevin.legion.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.apps.CategoryPicks
import com.kevin.legion.ui.apps.CategoryTap
import com.kevin.legion.ui.apps.DockPin
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.HomeCategory
import com.kevin.legion.ui.apps.Loaded
import com.kevin.legion.ui.apps.iconKey
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

/** A dimmed button or row: the same figure the dock uses for "still here, plainly not live". */
private const val CATEGORY_DIMMED_ALPHA = 0.5f

/** One category button's resolved state: its picks, each already matched to the drawer snapshot
 * (`app == null` means that pick is no longer installed). */
data class CategoryUi(val category: HomeCategory, val slots: List<DockSlotUi>)

/** One drawer app as the chooser lists it. Public so [HomeContent] stays renderable with fakes while
 * `Loaded` stays internal. */
data class ChooserRow(val app: DrawerApp, val icon: ImageBitmap?)

/** What HOME's category row asks its owner to do. [onLaunch] gets a slot whose app may be missing or
 * paused, and the owner says why in words, the same posture as the dock's own callback. */
data class CategoryCallbacks(
    val onLaunch: (DockSlotUi) -> Unit,
    val onSave: (HomeCategory, List<DockPin>) -> Unit,
)

internal fun buildChooserRows(loaded: Loaded?): List<ChooserRow> {
    val icons = loaded?.icons.orEmpty()
    return loaded?.apps.orEmpty().map { ChooserRow(it, icons[iconKey(it.profileKey, it.packageName, it.className)]) }
}

/** What a category button says about itself, in words (never by colour or shape alone). */
data class CategoryButtonSpec(
    val label: String,
    val description: String,
    val unset: Boolean,
    val dimmed: Boolean,
)

// A loading slot is not known to be down, so it never counts toward "Unavailable".
private fun DockSlotUi.usable(): Boolean = loading || (app != null && !paused)

/** Pure, so `CategoryButtonSpecTest` can pin every state without a composition. */
internal fun categoryButtonSpec(ui: CategoryUi): CategoryButtonSpec {
    val category = ui.category
    val slots = ui.slots
    return when {
        slots.isEmpty() -> CategoryButtonSpec(
            label = category.short,
            description = "${category.title}, not set up. Tap to choose apps.",
            unset = true,
            dimmed = false,
        )
        slots.size == 1 -> {
            val slot = slots.single()
            when {
                slot.loading -> CategoryButtonSpec(
                    category.short, "${category.title}, loading your apps", false, false,
                )
                slot.app == null -> CategoryButtonSpec(
                    "Not installed", "${category.title}, the picked app is not installed", false, true,
                )
                slot.paused -> CategoryButtonSpec(
                    "Paused", "${category.title}, ${slot.label} is paused with the work profile", false, true,
                )
                else -> CategoryButtonSpec(category.short, "${category.title}, opens ${slot.label}", false, false)
            }
        }
        else -> {
            val allDown = slots.none { it.usable() }
            CategoryButtonSpec(
                label = if (allDown) "Unavailable" else category.short,
                description = "${category.title}, ${slots.size} apps, asks which to open" +
                    if (allDown) ". None of them can open right now." else "",
                unset = false,
                dimmed = allDown,
            )
        }
    }
}

/** The prototype's per-category tile and glyph colours (research/tray-canvas, option A). */
internal data class CategoryLook(@DrawableRes val icon: Int, val tile: Color, val glyph: Color)

private object Tiles {
    val bank = Color(0xFF173D2B)
    val bankGlyph = Color(0xFF7EDBA5)
    val music = Color(0xFF33265A)
    val musicGlyph = Color(0xFFCDBDFF)
    val maps = Color(0xFF0F3B40)
    val mapsGlyph = Color(0xFF77DCE5)
    val mail = Color(0xFF1F3450)
    val mailGlyph = Color(0xFFA8C8FF)
    val chat = Color(0xFF43340F)
    val chatGlyph = Color(0xFFFFD36B)
}

internal fun lookOf(category: HomeCategory): CategoryLook = when (category) {
    HomeCategory.BANK -> CategoryLook(R.drawable.ms_account_balance, Tiles.bank, Tiles.bankGlyph)
    HomeCategory.MUSIC -> CategoryLook(R.drawable.ms_music_note, Tiles.music, Tiles.musicGlyph)
    HomeCategory.MAPS -> CategoryLook(R.drawable.ms_navigation, Tiles.maps, Tiles.mapsGlyph)
    HomeCategory.MAIL -> CategoryLook(R.drawable.ms_mail, Tiles.mail, Tiles.mailGlyph)
    HomeCategory.CHAT -> CategoryLook(R.drawable.ms_chat, Tiles.chat, Tiles.chatGlyph)
}

/**
 * HOME's row of five category buttons, under the pinned dock (home-launcher ticket 07). Tap: nothing
 * picked opens the chooser, one picked launches it, several ask which ("Open with"). Long-press
 * always opens the chooser. Owns only its two sheets' open/closed state; picks and launching belong
 * to the caller.
 */
@Composable
fun CategoryRow(
    categories: List<CategoryUi>,
    chooserRows: List<ChooserRow>,
    callbacks: CategoryCallbacks,
) {
    if (categories.isEmpty()) return
    var chooserFor by remember { mutableStateOf<HomeCategory?>(null) }
    var askFor by remember { mutableStateOf<HomeCategory?>(null) }

    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        categories.forEach { ui ->
            CategoryButton(
                ui = ui,
                modifier = Modifier.weight(1f),
                onClick = {
                    when (val tap = CategoryPicks.tap(ui.slots.map { it.pin })) {
                        CategoryTap.Choose -> chooserFor = ui.category
                        is CategoryTap.Launch -> callbacks.onLaunch(ui.slots.first { it.pin == tap.pin })
                        is CategoryTap.Ask -> askFor = ui.category
                    }
                },
                onLongClick = { chooserFor = ui.category },
            )
        }
    }

    askFor?.let { category ->
        val ui = categories.firstOrNull { it.category == category }
        if (ui != null) {
            OpenWithSheet(
                ui = ui,
                onDismiss = { askFor = null },
                onLaunch = { slot -> askFor = null; callbacks.onLaunch(slot) },
                onChoose = { askFor = null; chooserFor = category },
            )
        }
    }
    chooserFor?.let { category ->
        val ui = categories.firstOrNull { it.category == category }
        if (ui != null) {
            ChooserSheet(
                ui = ui,
                rows = chooserRows,
                onDismiss = { chooserFor = null },
                onSave = { picks -> chooserFor = null; callbacks.onSave(category, picks) },
            )
        }
    }
}

@Composable
private fun CategoryButton(
    ui: CategoryUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = categoryButtonSpec(ui)
    val look = lookOf(ui.category)
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) {
                contentDescription = spec.description
                role = Role.Button
            }
            .padding(vertical = 2.dp)
            .alpha(if (spec.dimmed) CATEGORY_DIMMED_ALPHA else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            // Unset is an OUTLINED ring with a muted glyph; set is a filled tile. The words for it
            // are in the button's description, so the shape is never the only signal.
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .let {
                        if (spec.unset) it.border(1.5.dp, SoftColors.outline, CircleShape) else it.background(look.tile)
                    },
                contentAlignment = Alignment.Center,
            ) {
                MsIcon(
                    res = look.icon,
                    contentDescription = null,
                    tint = if (spec.unset) SoftColors.text3 else look.glyph,
                    size = 20.dp,
                )
            }
        }
        Text(
            spec.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (spec.unset) SoftColors.text3 else SoftColors.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

