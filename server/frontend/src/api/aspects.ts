import { api } from '@/api/client'
import type { Table } from '@/api/synced'
import type { components } from '@/api/schema'

/**
 * The synced tables the Pantry, Body, Fleet, Places and Notes screens read and
 * write, one explicit typed closure per call. See `synced.ts` for why these are
 * written out instead of generated from a path string.
 *
 * Which tables carry which routes was read from `server/openapi.yaml`, not
 * assumed:
 *
 * - Everything except pantry receipts and line items has `PUT` (upsert by
 *   identity) and `DELETE` (soft tombstone). Receipts and line items are GET
 *   only: only the reconciliation gate writes them (CLAUDE.md section 4), and a
 *   row that could be edited into existence around the gate would not be a
 *   gated row.
 * - The identity is the row's `origin_guid` on body, pantry staples, vehicles
 *   and service history; the place's `label` on places; the server's own id on
 *   voice notes; and `(vehicle_id, service_name)` on maintenance schedules.
 * - Live reads ask for `active=1` so tombstones never reach a screen. The two
 *   gated tables have no tombstones and no `active` parameter.
 */

type Schemas = components['schemas']

export type GroceryStaple = Schemas['GroceryStaple']
export type Receipt = Schemas['Receipt']
export type ReceiptLineItem = Schemas['ReceiptLineItem']

export type BodyweightLog = Schemas['BodyweightLog']
export type SleepLog = Schemas['SleepLog']
export type SleepTarget = Schemas['SleepTarget']
export type MealLog = Schemas['MealLog']
export type MealTarget = Schemas['MealTarget']
export type WorkoutPlan = Schemas['WorkoutPlan']
export type WorkoutPlanItem = Schemas['WorkoutPlanItem']
export type WorkoutSetLog = Schemas['WorkoutSetLog']

export type Vehicle = Schemas['Vehicle']
export type ServiceHistory = Schemas['ServiceHistory']
export type MaintenanceSchedule = Schemas['MaintenanceSchedule']
export type Drive = Schemas['Drive']

export type Place = Schemas['Place']
export type VoiceNote = Schemas['VoiceNote']

const live = '1' as const

// ---- Pantry ---------------------------------------------------------------

export const groceryStaples: Table<GroceryStaple> = {
  name: 'pantry/grocery_staples',
  page: ({ since, after }) =>
    api.GET('/api/pantry/grocery_staples/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) =>
    api.PUT('/api/pantry/grocery_staples/{identity}/', { params: { path: { identity } }, body }),
  del: (identity) =>
    api.DELETE('/api/pantry/grocery_staples/{identity}/', { params: { path: { identity } } }),
}

export const receipts: Table<Receipt> = {
  name: 'pantry/receipts',
  page: ({ since, after }) => api.GET('/api/pantry/receipts/', { params: { query: { since, after } } }),
}

export const receiptLineItems: Table<ReceiptLineItem> = {
  name: 'pantry/line-items',
  page: ({ since, after }) =>
    api.GET('/api/pantry/line-items/', { params: { query: { since, after } } }),
}

// ---- Body -----------------------------------------------------------------

export const bodyweightLogs: Table<BodyweightLog> = {
  name: 'body/bodyweight_logs',
  page: ({ since, after }) =>
    api.GET('/api/body/bodyweight_logs/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) =>
    api.PUT('/api/body/bodyweight_logs/{identity}/', { params: { path: { identity } }, body }),
  del: (identity) =>
    api.DELETE('/api/body/bodyweight_logs/{identity}/', { params: { path: { identity } } }),
}

export const sleepLogs: Table<SleepLog> = {
  name: 'body/sleep_logs',
  page: ({ since, after }) =>
    api.GET('/api/body/sleep_logs/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) =>
    api.PUT('/api/body/sleep_logs/{identity}/', { params: { path: { identity } }, body }),
  del: (identity) => api.DELETE('/api/body/sleep_logs/{identity}/', { params: { path: { identity } } }),
}

export const sleepTargets: Table<SleepTarget> = {
  name: 'body/sleep_targets',
  page: ({ since, after }) =>
    api.GET('/api/body/sleep_targets/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) =>
    api.PUT('/api/body/sleep_targets/{identity}/', { params: { path: { identity } }, body }),
  del: (identity) => api.DELETE('/api/body/sleep_targets/{identity}/', { params: { path: { identity } } }),
}

export const mealLogs: Table<MealLog> = {
  name: 'body/meal_logs',
  page: ({ since, after }) =>
    api.GET('/api/body/meal_logs/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) =>
    api.PUT('/api/body/meal_logs/{identity}/', { params: { path: { identity } }, body }),
  del: (identity) => api.DELETE('/api/body/meal_logs/{identity}/', { params: { path: { identity } } }),
}

export const mealTargets: Table<MealTarget> = {
  name: 'body/meal_targets',
  page: ({ since, after }) =>
    api.GET('/api/body/meal_targets/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) =>
    api.PUT('/api/body/meal_targets/{identity}/', { params: { path: { identity } }, body }),
  del: (identity) => api.DELETE('/api/body/meal_targets/{identity}/', { params: { path: { identity } } }),
}

export const workoutPlans: Table<WorkoutPlan> = {
  name: 'body/workout_plans',
  page: ({ since, after }) =>
    api.GET('/api/body/workout_plans/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) =>
    api.PUT('/api/body/workout_plans/{identity}/', { params: { path: { identity } }, body }),
  del: (identity) => api.DELETE('/api/body/workout_plans/{identity}/', { params: { path: { identity } } }),
}

export const workoutPlanItems: Table<WorkoutPlanItem> = {
  name: 'body/workout_plan_items',
  page: ({ since, after }) =>
    api.GET('/api/body/workout_plan_items/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) =>
    api.PUT('/api/body/workout_plan_items/{identity}/', { params: { path: { identity } }, body }),
  del: (identity) =>
    api.DELETE('/api/body/workout_plan_items/{identity}/', { params: { path: { identity } } }),
}

export const workoutSetLogs: Table<WorkoutSetLog> = {
  name: 'body/workout_set_logs',
  page: ({ since, after }) =>
    api.GET('/api/body/workout_set_logs/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) =>
    api.PUT('/api/body/workout_set_logs/{identity}/', { params: { path: { identity } }, body }),
  del: (identity) =>
    api.DELETE('/api/body/workout_set_logs/{identity}/', { params: { path: { identity } } }),
}

// ---- Fleet ----------------------------------------------------------------

export const vehicles: Table<Vehicle> = {
  name: 'fleet/vehicles',
  page: ({ since, after }) =>
    api.GET('/api/fleet/vehicles/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) =>
    api.PUT('/api/fleet/vehicles/{identity}/', { params: { path: { identity } }, body }),
  del: (identity) => api.DELETE('/api/fleet/vehicles/{identity}/', { params: { path: { identity } } }),
}

export const serviceHistory: Table<ServiceHistory> = {
  name: 'fleet/service_history',
  page: ({ since, after }) =>
    api.GET('/api/fleet/service_history/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) =>
    api.PUT('/api/fleet/service_history/{identity}/', { params: { path: { identity } }, body }),
  del: (identity) =>
    api.DELETE('/api/fleet/service_history/{identity}/', { params: { path: { identity } } }),
}

/** Maintenance schedules are keyed by TWO values, so their `identity` here is
 * `<vehicle id>/<service name>`: a uuid never contains a slash, so everything
 * after the first one is the service name, whatever it holds. */
export function scheduleIdentity(vehicleId: string, serviceName: string): string {
  return `${vehicleId}/${serviceName}`
}

function splitScheduleIdentity(identity: string): { vehicle_id: string; service_name: string } {
  const slash = identity.indexOf('/')
  return { vehicle_id: identity.slice(0, slash), service_name: identity.slice(slash + 1) }
}

export const maintenanceSchedules: Table<MaintenanceSchedule> = {
  name: 'fleet/maintenance_schedules',
  page: ({ since, after }) =>
    api.GET('/api/fleet/maintenance_schedules/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) =>
    api.PUT('/api/fleet/maintenance_schedules/{vehicle_id}/{service_name}/', {
      params: { path: splitScheduleIdentity(identity) },
      body,
    }),
  del: (identity) =>
    api.DELETE('/api/fleet/maintenance_schedules/{vehicle_id}/{service_name}/', {
      params: { path: splitScheduleIdentity(identity) },
    }),
}

/** Drives are read only on the web (ticket 17): there is no put or del here. */
export const drives: Table<Drive> = {
  name: 'fleet/drives',
  page: ({ since, after }) =>
    api.GET('/api/fleet/drives/', { params: { query: { active: live, since, after } } }),
}

// ---- Places and notes -----------------------------------------------------

export const places: Table<Place> = {
  name: 'places',
  page: ({ since, after }) =>
    api.GET('/api/places/', { params: { query: { active: live, since, after } } }),
  put: (identity, body) => api.PUT('/api/places/{identity}/', { params: { path: { identity } }, body }),
  del: (identity) => api.DELETE('/api/places/{identity}/', { params: { path: { identity } } }),
}

/** Voice notes are read only on the web: see `screens/notes.tsx`. */
export const voiceNotes: Table<VoiceNote> = {
  name: 'voice_notes',
  page: ({ since, after }) =>
    api.GET('/api/voice_notes/', { params: { query: { active: live, since, after } } }),
}
