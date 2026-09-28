package com.kevin.legion.ui.checklists

import com.kevin.legion.data.local.ChecklistItem

/** Every callback the open-list (Keep-style) screen needs, home-launcher ticket 04's own
 * `ListDetailContent(state, callbacks)` shape. Its own file (split out of `ListDetailContent.kt`)
 * so the file name matches its single top-level declaration (detekt's `MatchingDeclarationName`). */
data class ListDetailCallbacks(
    val onBack: () -> Unit,
    val onOpenHistory: () -> Unit,
    val onToggleOverflowMenu: (Boolean) -> Unit,
    val onShowRenameDialog: (Boolean) -> Unit,
    val onRename: (String) -> Unit,
    val onShowSchedulePicker: (Boolean) -> Unit,
    val onSetSchedule: (String?, String?) -> Unit,
    val onArchiveToggle: () -> Unit,
    val onShowDeleteConfirm: (Boolean) -> Unit,
    val onConfirmDelete: () -> Unit,
    val onTick: (Long) -> Unit,
    val onUntick: (itemId: Long, tickDay: Int) -> Unit,
    val onTickMeasured: (Long) -> Unit,
    val onSetInputValue: (itemId: Long, value: String) -> Unit,
    val onDraftChange: (String) -> Unit,
    val onAddItem: () -> Unit,
    val onToggleShowTicked: () -> Unit,
    val onLongPress: (ChecklistItem) -> Unit,
    val onDismissLongPress: () -> Unit,
    val onEdit: (ChecklistItem) -> Unit,
    val onDismissEdit: () -> Unit,
    val onSaveEdit: (text: String, unit: String?, target: Double?, direction: String?) -> Unit,
    val onMoveUp: (Long) -> Unit,
    val onMoveDown: (Long) -> Unit,
    val onDeleteItem: (Long) -> Unit,
)
