import type {
  BodyweightLog,
  Drive,
  GroceryStaple,
  MaintenanceSchedule,
  MealLog,
  MealTarget,
  Place,
  Receipt,
  ReceiptLineItem,
  ServiceHistory,
  SleepLog,
  SleepTarget,
  Vehicle,
  VoiceNote,
  WorkoutPlan,
  WorkoutPlanItem,
  WorkoutSetLog,
} from '../api/aspects'
import type { Row } from './engine-tables'

/**
 * A believable household for the Pantry, Body, Fleet, Places and Notes screens:
 * what the screenshots and the tests that only need "some data" talk to.
 *
 * Dates are relative to now, so every screen has a today, a this week and a
 * last night whenever it runs. The set deliberately contains the awkward rows
 * the trust rules exist for: a receipt the gate could not verify, one with an
 * unaccounted amount, a line with no macro estimate, a seeded maintenance
 * interval, a vehicle with no key on the engine, a drive with no fuel reading,
 * and a voice note that was interrupted.
 */

let counter = 0
function uuid(): string {
  counter += 1
  return `20000000-0000-4000-8000-${counter.toString(16).padStart(12, '0')}`
}

/** An instant `daysAgo` days back at `hour`:`minute` local. */
export function at(daysAgo: number, hour = 9, minute = 0): string {
  const d = new Date()
  d.setDate(d.getDate() - daysAgo)
  d.setHours(hour, minute, 0, 0)
  return d.toISOString()
}

/** A bare local date `daysAgo` days back. */
export function dayAt(daysAgo: number): string {
  const d = new Date()
  d.setDate(d.getDate() - daysAgo)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function facts(created: string) {
  return {
    id: uuid(),
    provenance: 'USER' as const,
    created_at: created,
    updated_at: created,
    deleted_at: null,
  }
}

export function seedAspects(): Record<string, Row[]> {
  // ---- Pantry -------------------------------------------------------------
  const staples: GroceryStaple[] = [
    ['Oat milk', 14],
    ['Eggs', 22],
    ['Spinach', 9],
    ['Bananas', 17],
    ['Greek yogurt', 11],
  ].map(([name, times], index) => ({
    ...facts(at(40)),
    name: String(name).toLowerCase(),
    display_name: String(name),
    times_bought: Number(times),
    last_bought_at: at(index + 1, 17),
    origin_guid: uuid(),
  }))

  const trader = {
    ...facts(at(3, 18)),
    provenance: 'LLM_RECONCILED' as const,
    ingested_file_id: null,
    store: "Trader Joe's",
    purchase_date: dayAt(3),
    currency: 'USD',
    total_cents: 8423,
    subtotal_cents: 8423,
    tax_cents: 0,
    other_charges_cents: null,
    photo_object_path: null,
    origin_guid: null,
    unaccounted_cents: null,
  } satisfies Receipt
  const costco = {
    ...facts(at(9, 12)),
    provenance: 'UNRECONCILED' as const,
    ingested_file_id: null,
    store: 'Costco Wholesale',
    purchase_date: dayAt(9),
    currency: 'USD',
    total_cents: 18764,
    subtotal_cents: null,
    tax_cents: null,
    other_charges_cents: null,
    photo_object_path: null,
    origin_guid: null,
    unaccounted_cents: 2150,
  } satisfies Receipt
  const corner = {
    ...facts(at(14, 20)),
    provenance: 'DETERMINISTIC' as const,
    ingested_file_id: null,
    store: 'Corner Market',
    purchase_date: dayAt(14),
    currency: 'USD',
    total_cents: 1237,
    subtotal_cents: 1143,
    tax_cents: 94,
    other_charges_cents: null,
    photo_object_path: null,
    origin_guid: null,
    unaccounted_cents: null,
  } satisfies Receipt
  const receipts: Receipt[] = [trader, costco, corner]

  const line = (
    receipt: Receipt,
    name: string,
    qty: string,
    unit: number | null,
    total: number,
    macros: [string, string, string, string] | null,
  ): ReceiptLineItem => ({
    id: uuid(),
    receipt_id: receipt.id,
    name,
    quantity: qty,
    unit_price_cents: unit,
    total_price_cents: total,
    estimated_calories_kcal: macros?.[0] ?? null,
    estimated_protein_g: macros?.[1] ?? null,
    estimated_carbs_g: macros?.[2] ?? null,
    estimated_fat_g: macros?.[3] ?? null,
    reversal_of: null,
    provenance: receipt.provenance,
    created_at: receipt.created_at,
    origin_guid: null,
  })
  const lines: ReceiptLineItem[] = [
    line(trader, 'Oat milk, 64 oz', '2', 399, 798, ['120', '3', '16', '5']),
    line(trader, 'Sourdough loaf', '1', 549, 549, ['1100', '40', '220', '6']),
    line(trader, 'Spinach, 10 oz', '1', 349, 349, ['70', '9', '10', '1']),
    line(trader, 'Reusable bag', '1', 99, 99, null),
    line(costco, 'Rotisserie chicken', '2', 499, 998, ['1500', '170', '2', '90']),
    line(costco, 'Paper towels, 12 pack', '1', 2299, 2299, null),
    line(corner, 'Coffee beans', '1', 1143, 1143, ['0', '0', '0', '0']),
  ]

  // ---- Body ---------------------------------------------------------------
  const weights: BodyweightLog[] = Array.from({ length: 24 }, (_, index) => ({
    ...facts(at(index, 7)),
    weight_value: Math.round((181.6 - index * 0.11 + Math.sin(index) * 0.6) * 10) / 10,
    weight_unit: 'lbs',
    logged_at: at(index, 7, 10),
    trust_tier: index % 7 === 0 ? 'PROVEN' : 'REPORTED',
    origin_guid: uuid(),
  }))

  const sleep: SleepLog[] = Array.from({ length: 14 }, (_, index) => ({
    ...facts(at(index, 8)),
    sleep_date: dayAt(index),
    duration_minutes: 380 + ((index * 37) % 110),
    quality: index % 3 === 0 ? null : 3 + (index % 3),
    notes: index === 2 ? 'Woke twice, heat' : null,
    logged_at: at(index, 8),
    trust_tier: 'REPORTED',
    origin_guid: uuid(),
  }))
  const sleepTargets: SleepTarget[] = [
    { ...facts(at(60)), target_minutes: 480, effective_from_date: dayAt(60), origin_guid: uuid() },
  ]

  const mealTargets: MealTarget[] = [
    {
      ...facts(at(60)),
      calories_kcal: 2200,
      protein_g: 150,
      carbs_g: 240,
      fat_g: 70,
      effective_from_date: dayAt(60),
      origin_guid: uuid(),
    },
  ]
  const meal = (
    description: string,
    hour: number,
    macros: [number, number, number, number] | null,
  ): MealLog => ({
    ...facts(at(0, hour)),
    description,
    calories_kcal: macros?.[0] ?? null,
    protein_g: macros?.[1] ?? null,
    carbs_g: macros?.[2] ?? null,
    fat_g: macros?.[3] ?? null,
    logged_at: at(0, hour, 15),
    source_image_path: null,
    trust_tier: 'REPORTED',
    origin_guid: uuid(),
  })
  const meals: MealLog[] = [
    meal('Greek yogurt with berries', 8, [260, 22, 31, 5]),
    meal('Chicken, rice and greens', 13, [720, 52, 78, 18]),
    meal('Coffee with a biscuit', 15, null),
    meal('Salmon, potatoes, broccoli', 19, [640, 44, 48, 28]),
  ]

  const monday = (() => {
    const d = new Date()
    d.setDate(d.getDate() - ((d.getDay() + 6) % 7))
    return d
  })()
  const mondayDay = (() => {
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${monday.getFullYear()}-${pad(monday.getMonth() + 1)}-${pad(monday.getDate())}`
  })()
  const plans: WorkoutPlan[] = [
    { ...facts(at(30)), sessions_per_week: 4, effective_from_week: mondayDay, origin_guid: uuid() },
  ]
  const planItems: WorkoutPlanItem[] = [
    ['Squat', 9, 5],
    ['Bench press', 9, 5],
    ['Barbell row', 6, 8],
  ].map(([exercise, sets, reps]) => ({
    ...facts(at(30)),
    exercise: String(exercise),
    target_sets_per_week: Number(sets),
    reps_per_set: Number(reps),
    effective_from_week: mondayDay,
    origin_guid: uuid(),
  }))
  const setLogs: WorkoutSetLog[] = [
    ['Squat', 3, 5, 225, 0],
    ['Bench press', 3, 5, 165, 0],
    ['Barbell row', 3, 8, 135, 1],
    ['Squat', 3, 5, 230, 2],
  ].map(([exercise, sets, reps, weight, daysAgo]) => ({
    ...facts(at(Number(daysAgo), 18)),
    exercise: String(exercise),
    sets: Number(sets),
    reps: Number(reps),
    weight_value: Number(weight),
    weight_unit: 'lbs',
    logged_at: at(Number(daysAgo), 18, 30),
    trust_tier: 'REPORTED',
    origin_guid: uuid(),
  }))

  // ---- Fleet --------------------------------------------------------------
  const vehicle = (
    name: string,
    year: number,
    make: string,
    model: string,
    trim: string | null,
    keyed: boolean,
    odometer: number | null,
  ): Vehicle => ({
    ...facts(at(200)),
    provenance: 'DETERMINISTIC',
    name,
    make,
    model,
    year,
    trim,
    engine: null,
    confirmed: true,
    odometer_baseline: odometer,
    odometer_baseline_at: odometer === null ? null : at(20, 10),
    origin_guid: keyed ? uuid() : null,
    archived: false,
    last_obd_mac: null,
  })
  const civic = vehicle('Daily', 2018, 'Honda', 'Civic', 'EX', true, 61240)
  const m3 = vehicle('Weekend', 2003, 'BMW', 'M3', null, true, 118300)
  const project = vehicle('Project', 1998, 'Mazda', 'Miata', null, false, null)
  const vehicles = [civic, m3, project]

  const service = (
    v: Vehicle,
    name: string,
    daysAgo: number,
    mileage: number | null,
    cents: number | null,
    kind: 'OBSERVED' | 'ASSERTED',
  ): ServiceHistory => ({
    ...facts(at(daysAgo)),
    vehicle_id: v.id,
    service_name: name,
    mileage,
    service_date: dayAt(daysAgo),
    cost_cents: cents,
    kind,
    origin_guid: uuid(),
  })
  const services = [
    service(civic, 'Oil change', 45, 58900, 6499, 'OBSERVED'),
    service(civic, 'Tire rotation', 45, 58900, null, 'ASSERTED'),
    service(civic, 'Brake fluid flush', 400, 41200, 12000, 'OBSERVED'),
    service(m3, 'Oil change', 120, 116900, 11250, 'ASSERTED'),
  ]

  const schedule = (
    v: Vehicle,
    name: string,
    miles: number | null,
    months: number | null,
    source: string,
    neverDone = false,
  ): MaintenanceSchedule => ({
    ...facts(at(100)),
    vehicle_id: v.id,
    service_name: name,
    interval_miles: miles,
    interval_months: months,
    interval_source: source,
    never_done: neverDone,
  })
  const schedules = [
    schedule(civic, 'Oil change', 5000, 6, 'CONFIRMED'),
    schedule(civic, 'Tire rotation', 7500, null, 'SEEDED'),
    schedule(civic, 'Brake fluid flush', null, 36, 'LOOKUP'),
    schedule(civic, 'Cabin air filter', 15000, 12, 'SEEDED', true),
  ]

  const drive = (v: Vehicle, daysAgo: number, miles: number, gallons: number | null, reason: string): Drive => ({
    ...facts(at(daysAgo, 8)),
    sync_id: uuid(),
    vehicle_id: v.id,
    started_at: at(daysAgo, 8, 5),
    ended_at: at(daysAgo, 8, 40),
    miles,
    gallons,
    end_reason: reason,
  })
  const drives = [
    drive(civic, 0, 14.2, 0.52, 'ENGINE_OFF'),
    drive(civic, 1, 6.8, null, 'ENGINE_OFF'),
    drive(civic, 2, 31.5, 1.04, 'LINK_LOST'),
    drive(civic, 4, 9.1, 0.31, 'ENGINE_OFF'),
    drive(m3, 6, 42.0, 2.6, 'ENGINE_OFF'),
  ]

  // ---- Places and notes ---------------------------------------------------
  const placeRow = (label: string, latitude: number, longitude: number): Place => ({
    ...facts(at(80)),
    label,
    latitude,
    longitude,
  })
  const places = [
    placeRow('Home', 29.7604, -95.3698),
    placeRow('Office', 29.7752, -95.3103),
    placeRow('Gym', 29.7431, -95.4012),
    placeRow('Maplewood fields', 29.8021, -95.4417),
  ]

  const note = (
    title: string | null,
    kind: string,
    daysAgo: number,
    minutes: number | null,
    summary: string | null,
    transcript: string | null,
    interrupted = false,
  ): VoiceNote => ({
    ...facts(at(daysAgo, 10)),
    provenance: 'LLM_DERIVED',
    started_at: at(daysAgo, 10, 0),
    ended_at: minutes === null ? null : at(daysAgo, 10, minutes),
    title,
    summary,
    transcript,
    kind,
    interrupted,
  })
  const notes = [
    note(
      'Kitchen plan with Mia',
      'MEETING',
      1,
      24,
      'Agreed to order the cabinets this month and keep the existing counters. Mia will ask the contractor about a start date; Kevin will measure the pantry wall.',
      'Okay, so the cabinets first. I think we keep the counters for now. Yes, and ask about the start date. I will measure the pantry wall on Saturday.\n\nSo the budget is what, around twelve thousand? Roughly, depending on the doors.',
    ),
    note('Idea for the garage shelves', 'SOLO', 5, 2, 'Wall-mounted rails instead of floor shelves, so the car has room.', 'Idea, garage shelves. Wall rails instead of floor units so there is room to open the door.'),
    note(null, 'SOLO', 8, 1, null, 'Remember to call about the', true),
  ]

  const rows = (list: object[]): Row[] => list as unknown as Row[]
  return {
    'pantry/grocery_staples': rows(staples),
    'pantry/receipts': rows(receipts),
    'pantry/line-items': rows(lines),
    'body/bodyweight_logs': rows(weights),
    'body/sleep_logs': rows(sleep),
    'body/sleep_targets': rows(sleepTargets),
    'body/meal_logs': rows(meals),
    'body/meal_targets': rows(mealTargets),
    'body/workout_plans': rows(plans),
    'body/workout_plan_items': rows(planItems),
    'body/workout_set_logs': rows(setLogs),
    'fleet/vehicles': rows(vehicles),
    'fleet/service_history': rows(services),
    'fleet/maintenance_schedules': rows(schedules),
    'fleet/drives': rows(drives),
    places: rows(places),
    voice_notes: rows(notes),
  }
}
