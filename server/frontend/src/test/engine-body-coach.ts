import type { components } from '../api/schema'
import type { Reply } from './engine'

/**
 * The fake engine's `/api/body/coach/*` (fat-loss ticket 09), following
 * `server/body_coach/views.py` and its sentences, so a screen test reads the
 * words the real engine says. The figures are canned: the web computes nothing,
 * it shows what the engine said, so the fake only has to answer in the shape.
 *
 * Synthetic person only: a 30-year-old 178 cm man.
 */

type Profile = components['schemas']['Profile']
type Energy = components['schemas']['Energy']
type Trend = components['schemas']['Trend']
type SavedMeal = components['schemas']['SavedMeal']
type WeeklyLine = components['schemas']['WeeklyLine']

export interface CoachState {
  profile: Profile
  energy: Energy
  trend: Trend
  savedMeals: SavedMeal[]
  weeklyLine: WeeklyLine
  /** Meal entries a saved-meal log wrote, newest last. */
  logged: string[]
}

export function emptyProfile(): Profile {
  return {
    exists: false,
    height_cm: null,
    sex: null,
    birth_year: null,
    activity_level: null,
    target_rate_pct: 0.75,
    protein_g_per_kg: 2.0,
    weekly_line_on: true,
    goal_weight_lbs: null,
    missing: ['height', 'sex', 'birth year', 'activity level', 'a weigh-in'],
  }
}

export function emptyEnergy(): Energy {
  return {
    estimate: true,
    basis: null,
    basis_days: null,
    basis_words: 'No maintenance estimate yet: it needs height, sex, birth year, activity level and a weigh-in.',
    missing: ['height', 'sex', 'birth year', 'activity level', 'a weigh-in'],
    weight_lbs: null,
    weight_source: null,
    bmr_kcal: null,
    activity_level: null,
    activity_factor: null,
    formula_maintenance_kcal: null,
    observed_maintenance_kcal: null,
    maintenance_kcal: null,
    target_rate_pct: 0.75,
    suggested_target_kcal: null,
    protein_g: null,
    protein_g_per_kg: 2.0,
    protein_basis: null,
    current_target: null,
    proposal: null,
    words:
      'Every figure here is an estimate, and there is no maintenance figure yet: it needs height, sex, birth year, activity level and a weigh-in.',
  }
}

export function emptyTrend(): Trend {
  return {
    unit: 'lbs',
    points: [],
    latest_trend_lbs: null,
    rate_pct_per_week: null,
    rate_lbs_per_week: null,
    rate_days: 14,
    band: { low_pct: 0.5, high_pct: 1.0 },
    in_band: null,
    early_water: false,
    milestone: null,
    waist: [],
    waist_change_in_28d: null,
    words: 'No weigh-ins yet, so there is no trend. Log a weight and the trend starts there.',
  }
}

/** The man on the formula, two weeks off band, with a pending proposal. */
export function coachingEnergy(): Energy {
  return {
    ...emptyEnergy(),
    basis: 'trend',
    basis_days: 14,
    basis_words: 'from your own trend, 14 days',
    missing: [],
    weight_lbs: 209.4,
    weight_source: 'trend',
    bmr_kcal: 1918,
    activity_level: 'moderate',
    activity_factor: 1.55,
    formula_maintenance_kcal: 2972,
    observed_maintenance_kcal: 2500,
    maintenance_kcal: 2500,
    suggested_target_kcal: 1716,
    protein_g: 190,
    protein_basis: 'current weight',
    current_target: { calories_kcal: 2200, protein_g: 180, effective_from: '2026-09-01' },
    proposal: {
      id: '00000000-0000-4000-8000-00000000c0ac',
      current_target_kcal: 2200,
      proposed_target_kcal: 2050,
      observed_maintenance_kcal: 2500,
      observed_rate_pct: 0,
      window_start: '2026-09-23',
      window_end: '2026-10-06',
      intake_days: 14,
      weigh_in_days: 14,
      words:
        'Over the last two weeks your trend says you were holding steady, outside the 0.5 to 1.0% band, on an estimated maintenance of 2500 kcal. Proposed: lower the daily target from 2200 to 2050 kcal. An estimate; nothing changes unless you accept it.',
    },
    words: 'Every figure here is an estimate. Maintenance is from your own trend, 14 days. The suggested target is for losing 0.75% of bodyweight a week.',
  }
}

export function coachingTrend(): Trend {
  const points = Array.from({ length: 30 }, (_, i) => {
    const day = new Date(Date.UTC(2026, 8, 8 + i)).toISOString().slice(0, 10)
    return { day, weight_lbs: i % 3 === 1 ? null : 212 - i * 0.1, trend_lbs: 212 - i * 0.09 }
  })
  return {
    ...emptyTrend(),
    points,
    latest_trend_lbs: 209.4,
    rate_pct_per_week: -0.3,
    rate_lbs_per_week: -0.6,
    in_band: false,
    milestone: {
      goal_id: 'g1',
      statement: 'reach a lighter weight',
      target_lbs: 190,
      start_lbs: 212,
      remaining_lbs: 19.4,
      progress_pct: 12,
      weeks_to_go: 32.3,
    },
    waist: [{ id: '00000000-0000-4000-8000-0000000000a1', measured_on: '2026-10-05', waist_in: 36.2, waist_cm: 92 }],
    words: 'Trend weight 209.4 lbs, smoothed from your weigh-ins; an estimate, not a single reading.',
  }
}

export function defaultCoach(): CoachState {
  return {
    profile: emptyProfile(),
    energy: emptyEnergy(),
    trend: emptyTrend(),
    savedMeals: [],
    weeklyLine: { say: false, facts: '', reason: 'There is no two-week rate yet, so there is nothing to state.' },
    logged: [],
  }
}

let counter = 0
function id(): string {
  counter += 1
  return `00000000-0000-4000-8000-${(0xc0de0000 + counter).toString(16).padStart(12, '0')}`
}

const BASE = '/api/body/coach/'

export function handleCoach(state: CoachState, method: string, pathname: string, body: unknown): Reply | null {
  if (!pathname.startsWith(BASE)) return null
  const rest = pathname.slice(BASE.length)
  const sent = (body ?? {}) as Record<string, unknown>

  if (rest === 'profile/' && method === 'GET') return { status: 200, body: state.profile }
  if (rest === 'profile/' && method === 'PUT') {
    if ('sex' in sent && sent.sex !== 'male' && sent.sex !== 'female') {
      return {
        status: 400,
        body: { detail: `Nothing was saved. sex must be male or female (the formula needs one of the two); got '${String(sent.sex)}'.` },
      }
    }
    const next = { ...state.profile, exists: true }
    if (typeof sent.height_cm === 'number') next.height_cm = Math.round(sent.height_cm * 10) / 10
    for (const key of ['sex', 'birth_year', 'activity_level', 'target_rate_pct', 'protein_g_per_kg', 'weekly_line_on', 'goal_weight_lbs'] as const) {
      if (key in sent) (next as Record<string, unknown>)[key] = sent[key]
    }
    next.missing = state.profile.missing.filter(
      (word) =>
        !(word === 'height' && next.height_cm !== null) &&
        !(word === 'sex' && next.sex !== null) &&
        !(word === 'birth year' && next.birth_year !== null) &&
        !(word === 'activity level' && next.activity_level !== null),
    )
    state.profile = next
    return { status: 200, body: next }
  }
  if (rest === 'energy/' && method === 'GET') return { status: 200, body: state.energy }
  if (rest === 'trend/' && method === 'GET') return { status: 200, body: state.trend }
  if (rest === 'weekly-line/' && method === 'GET') return { status: 200, body: state.weeklyLine }

  if (rest === 'waist/' && method === 'GET') return { status: 200, body: { results: state.trend.waist } }
  if (rest === 'waist/' && method === 'POST') {
    const inches = typeof sent.waist_in === 'number' ? sent.waist_in : null
    const cm = typeof sent.waist_cm === 'number' ? sent.waist_cm : null
    if ((inches === null) === (cm === null)) {
      return { status: 400, body: { detail: 'Nothing was saved. Give waist_in or waist_cm, exactly one.' } }
    }
    const mm = inches !== null ? Math.round(inches * 25.4) : Math.round((cm ?? 0) * 10)
    const row = {
      id: id(),
      measured_on: String(sent.measured_on ?? '2026-10-07'),
      waist_in: Math.round((mm / 25.4) * 10) / 10,
      waist_cm: Math.round(mm) / 10,
    }
    state.trend = { ...state.trend, waist: [row, ...state.trend.waist] }
    return { status: 201, body: row }
  }
  const waistMatch = rest.match(/^waist\/([^/]+)\/$/)
  if (waistMatch && method === 'DELETE') {
    const before = state.trend.waist.length
    state.trend = { ...state.trend, waist: state.trend.waist.filter((row) => row.id !== waistMatch[1]) }
    return state.trend.waist.length < before
      ? { status: 204 }
      : { status: 404, body: { detail: 'Nothing was deleted. There is no waist measurement with that id.' } }
  }

  if (rest === 'saved-meals/' && method === 'GET') return { status: 200, body: { results: state.savedMeals } }
  if (rest === 'saved-meals/' && method === 'POST') {
    const name = String(sent.name ?? '').trim()
    if (state.savedMeals.some((meal) => meal.name.toLowerCase() === name.toLowerCase())) {
      return {
        status: 400,
        body: { detail: `Nothing was saved. There is already a saved meal called '${name}'; delete it first or pick another name.` },
      }
    }
    const meal: SavedMeal = {
      id: id(),
      name,
      description: String(sent.description ?? ''),
      calories_kcal: Number(sent.calories_kcal),
      protein_g: Number(sent.protein_g),
      carbs_g: null,
      fat_g: null,
      estimate: true,
      updated_at: '2026-10-07T12:00:00Z',
    }
    state.savedMeals = [...state.savedMeals, meal]
    return { status: 201, body: meal }
  }
  const logMatch = rest.match(/^saved-meals\/([^/]+)\/log\/$/)
  if (logMatch && method === 'POST') {
    const meal = state.savedMeals.find((m) => m.id === logMatch[1])
    if (!meal) return { status: 404, body: { detail: 'Nothing was logged. There is no saved meal with that id.' } }
    state.logged.push(meal.id)
    return {
      status: 201,
      body: {
        meal_log: { origin_guid: id(), description: meal.description || meal.name, calories_kcal: meal.calories_kcal },
        words: `Logged '${meal.name}': ${meal.calories_kcal} kcal and ${meal.protein_g} g protein, estimates saved with the meal, not measurements.`,
      },
    }
  }
  const mealMatch = rest.match(/^saved-meals\/([^/]+)\/$/)
  if (mealMatch && method === 'DELETE') {
    const before = state.savedMeals.length
    state.savedMeals = state.savedMeals.filter((meal) => meal.id !== mealMatch[1])
    return state.savedMeals.length < before
      ? { status: 204 }
      : { status: 404, body: { detail: 'Nothing was deleted. There is no saved meal with that id.' } }
  }

  const decide = rest.match(/^proposals\/([^/]+)\/(accept|dismiss)\/$/)
  if (decide && method === 'POST') {
    const proposal = state.energy.proposal
    if (!proposal || proposal.id !== decide[1]) {
      return { status: 404, body: { detail: `Nothing was ${decide[2]}ed. There is no energy proposal with that id.` } }
    }
    if (decide[2] === 'accept') {
      state.energy = {
        ...state.energy,
        proposal: null,
        current_target: { calories_kcal: proposal.proposed_target_kcal, protein_g: 180, effective_from: '2026-10-07' },
      }
      return {
        status: 200,
        body: {
          accepted: true,
          meal_target: { calories_kcal: proposal.proposed_target_kcal, effective_from_date: '2026-10-07' },
          words: `Accepted. The daily target is ${proposal.proposed_target_kcal} kcal from 2026-10-07, protein 180 g carried from the target before. It came from an estimate; the next check is in two weeks.`,
        },
      }
    }
    state.energy = { ...state.energy, proposal: null }
    return { status: 200, body: { dismissed: true, words: 'Dismissed. The meal target is unchanged; nothing else was written.' } }
  }
  return null
}
