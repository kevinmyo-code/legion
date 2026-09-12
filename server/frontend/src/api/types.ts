import type { components } from '@/api/schema'

/** Short aliases for the generated schema types this app's screens actually
 * touch. Nothing here narrows or reshapes a field - that would be the two-
 * implementations drift ADR 0035 forbids, just applied to a type instead of
 * a call. */
export type Event = components['schemas']['Event']
export type Checklist = components['schemas']['Checklist']
export type ChecklistItem = components['schemas']['ChecklistItem']
export type ChecklistTick = components['schemas']['ChecklistTick']

/**
 * `server/openapi.yaml`'s `required` list names `id`/`created_at`/
 * `updated_at`/`deleted_at` on `Checklist` and `ChecklistItem` even though
 * every one of them is also `readOnly: true` - drf-spectacular's own quirk,
 * not something this app should paper over with `any`. The server ignores
 * whatever a client sends for a read-only field and mints its own, so these
 * two builders exist only to satisfy the generated TYPE on the way out; the
 * placeholder values below are never what gets stored; `body: newChecklist('x')`
 * documents that at the call site instead of a bare, unexplained cast.
 */
export function newChecklist(name: string): Checklist {
  return {
    id: '',
    name,
    created_at: '',
    updated_at: '',
    deleted_at: null,
  }
}

export function newChecklistItem(checklistId: string, text: string): ChecklistItem {
  return {
    id: '',
    checklist: checklistId,
    text,
    created_at: '',
    updated_at: '',
    deleted_at: null,
  }
}
