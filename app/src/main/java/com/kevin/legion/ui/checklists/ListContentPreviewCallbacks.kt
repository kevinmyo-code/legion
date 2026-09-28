package com.kevin.legion.ui.checklists

/** No-op [ListsPageCallbacks]/[ListDetailCallbacks] for a preview or a screenshot test that only
 * needs to render a state, never react to a tap - `screenshot.ListsScreenshotTest` is the reason
 * this exists (ticket 04's own stateless-content verification step). */
object ListContentPreviewCallbacks {
    fun page() = ListsPageCallbacks(
        onBack = {},
        onOpenList = {},
        onToggleArchived = {},
        onShowCreateDialog = {},
        onCreate = { _, _, _ -> },
    )

    fun detail() = ListDetailCallbacks(
        onBack = {},
        onOpenHistory = {},
        onToggleOverflowMenu = {},
        onShowRenameDialog = {},
        onRename = {},
        onShowSchedulePicker = {},
        onSetSchedule = { _, _ -> },
        onArchiveToggle = {},
        onShowDeleteConfirm = {},
        onConfirmDelete = {},
        onTick = {},
        onUntick = { _, _ -> },
        onTickMeasured = {},
        onSetInputValue = { _, _ -> },
        onDraftChange = {},
        onAddItem = {},
        onToggleShowTicked = {},
        onLongPress = {},
        onDismissLongPress = {},
        onEdit = {},
        onDismissEdit = {},
        onSaveEdit = { _, _, _, _ -> },
        onMoveUp = {},
        onMoveDown = {},
        onDeleteItem = {},
    )
}
