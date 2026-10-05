package com.kevin.legion.ui.checklists

/** Every callback the Lists page needs - home-launcher ticket 04's own `ListsContent(state,
 * callbacks)` shape, stateless so [com.kevin.legion.screenshot.ListsScreenshotTest] can drive it
 * directly. Its own file (split out of `ListsPageContent.kt`) so the file name matches its single
 * top-level declaration (detekt's `MatchingDeclarationName`). */
data class ListsPageCallbacks(
    val onBack: () -> Unit,
    val onOpenList: (Long) -> Unit,
    val onToggleArchived: () -> Unit,
    val onShowCreateDialog: (Boolean) -> Unit,
    val onCreate: (name: String, scheduleKind: String?, scheduleDaysOfWeek: String?) -> Unit,
    /** Opens the household bought log (purchase-log ticket 08). Null hides the button, which is how
     * every screenshot test that predates it keeps rendering the same page. */
    val onOpenBought: (() -> Unit)? = null,
)
