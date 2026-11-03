import type { BranchReport, ProcedureVersion, RunDetail, RunSummary } from '../api/procedures'
import type { Reply } from './engine'

/**
 * The fake engine's `/api/procedures/*` (procedures ticket 14): the run inspector's
 * reads, answered from rows a test seeds, in the shapes `server/procedures/views.py`
 * sends. Scoping is the real engine's; here the seeded rows ARE the caller's.
 */

export interface ProceduresState {
  runs: RunDetail[]
  procedures: ProcedureVersion[]
  /** Reports by procedure name. A name with no report answers 404 in words. */
  reports: Record<string, BranchReport>
}

export function defaultProcedures(): ProceduresState {
  return { runs: [], procedures: [], reports: {} }
}

const NO_RUN =
  'There is no such run in this household. Nothing was read. It may have been pruned after 60 days, or it belongs to another household.'

function summary(run: RunDetail): RunSummary {
  const { id, procedure, version, caller_kind, caller, who, status, status_label, started_at, ms, step_count, conversation_id } = run
  return { id, procedure, version, caller_kind, caller, who, status, status_label, started_at, ms, step_count, conversation_id }
}

export function handleProcedures(
  host: { procedures: ProceduresState },
  method: string,
  pathname: string,
  search: URLSearchParams,
): Reply | undefined {
  if (method !== 'GET' || !pathname.startsWith('/api/procedures/')) return undefined
  const state = host.procedures
  if (pathname === '/api/procedures/') return { status: 200, body: state.procedures }
  if (pathname === '/api/procedures/runs') {
    const runs = [...state.runs].sort((a, b) => b.started_at.localeCompare(a.started_at))
    return { status: 200, body: { results: runs.map(summary), more: false, retention_days: 60 } }
  }
  const one = /^\/api\/procedures\/runs\/([^/]+)$/.exec(pathname)
  if (one) {
    const run = state.runs.find((r) => r.id === one[1])
    return run ? { status: 200, body: run } : { status: 404, body: { detail: NO_RUN } }
  }
  if (pathname === '/api/procedures/report') {
    const name = search.get('name') ?? ''
    const report = state.reports[name]
    if (!report) {
      return {
        status: 404,
        body: { detail: `There is no procedure '${name}' this household can see. Nothing was read.` },
      }
    }
    return { status: 200, body: report }
  }
  return undefined
}

// =============================================================================
// Seeds: a car check that ran three times, one of them partial, one branch never taken.
// =============================================================================

export const RUN_OK = '7a1e0000-0000-4000-8000-000000000001'
export const RUN_PARTIAL = '7a1e0000-0000-4000-8000-000000000002'
export const RUN_MODEL = '7a1e0000-0000-4000-8000-000000000003'

const at = (minutesAgo: number) => new Date(Date.parse('2026-11-03T14:00:00Z') - minutesAgo * 60_000).toISOString()

function step(partial: Partial<RunDetail['steps'][number]> & { seq: number; step_id: string; kind: string }) {
  return {
    tool: '',
    args: null,
    result: '',
    outcome: 'ok' as const,
    outcome_label: 'OK',
    branch: 'next',
    chosen_by: 'code' as const,
    chosen_by_label: 'Code chose',
    started_at: at(5),
    ms: 40,
    model: '',
    prompt_tokens: null,
    output_tokens: null,
    cost_estimate_micro_usd: null,
    ...partial,
  }
}

export function makeRun(partial: Partial<RunDetail> = {}): RunDetail {
  return {
    id: RUN_OK,
    procedure: 'car_check',
    version: 1,
    caller_kind: 'phone',
    caller: 'Phone',
    who: 'You',
    status: 'ok',
    status_label: 'OK',
    started_at: at(5),
    ms: 412,
    step_count: 4,
    conversation_id: null,
    writes: false,
    inputs: null,
    result: 'The Civic is due an oil change in 400 miles. No codes are stored.',
    steps: [
      step({ seq: 0, step_id: 'vehicle', kind: 'tool', tool: 'read_records', args: '{"table": "vehicles"}', result: '1 vehicle: 2019 Civic.', ms: 120 }),
      step({ seq: 1, step_id: 'service', kind: 'tool', tool: 'read_records', args: '{"table": "service_history"}', result: 'Last oil change 4,600 miles ago.', ms: 90 }),
      step({ seq: 2, step_id: 'has_codes', kind: 'check', branch: 'fail', result: 'No codes are stored.', ms: 3 }),
      step({ seq: 3, step_id: 'answer', kind: 'say', branch: '', result: 'The Civic is due an oil change in 400 miles.', ms: 1 }),
    ],
    not_run: ['codes'],
    not_run_sentence: "Not on this run's path, so they did not run: codes.",
    ...partial,
  }
}

export function seedProcedures(): ProceduresState {
  return {
    runs: [
      makeRun(),
      makeRun({
        id: RUN_PARTIAL,
        status: 'partial',
        status_label: 'Partial',
        caller_kind: 'headunit',
        caller: 'Head unit',
        who: 'Mia',
        started_at: at(90),
        ms: 2310,
        step_count: 2,
        result: 'Read the vehicle. The service history could not be read, so nothing is known about the next service.',
        steps: [
          step({ seq: 0, step_id: 'vehicle', kind: 'tool', tool: 'read_records', result: '1 vehicle: 2019 Civic.', started_at: at(90) }),
          step({
            seq: 1,
            step_id: 'service',
            kind: 'tool',
            tool: 'read_records',
            outcome: 'failed',
            outcome_label: 'Failed',
            branch: '',
            result: 'The engine could not read service_history, so nothing is known from it.',
            started_at: at(90),
            ms: 2100,
          }),
        ],
        not_run: ['has_codes', 'codes', 'answer'],
        not_run_sentence: 'Did not run, because the run stopped first: has_codes, codes, answer.',
      }),
      makeRun({
        id: RUN_MODEL,
        procedure: 'lists_check',
        caller_kind: 'web',
        caller: 'Web assistant',
        started_at: at(300),
        step_count: 2,
        result: 'Short.',
        steps: [
          step({
            seq: 0,
            step_id: 'size',
            kind: 'decide',
            branch: 'short',
            chosen_by: 'model',
            chosen_by_label: 'A model chose',
            result: 'a model chose short',
            model: 'gemini-3.5-flash-lite',
            prompt_tokens: 210,
            output_tokens: 4,
            cost_estimate_micro_usd: 22,
            started_at: at(300),
            ms: 610,
          }),
          step({ seq: 1, step_id: 'short', kind: 'say', branch: '', result: 'Short.', started_at: at(300), ms: 1 }),
        ],
        not_run: ['long'],
        not_run_sentence: "Not on this run's path, so they did not run: long.",
      }),
    ],
    procedures: [
      { procedure: 'car_check', version: 1, description: 'How the car is.', writes: false, is_active: true, source: 'builtin', runs: 2, last_run_at: at(5) },
      { procedure: 'lists_check', version: 1, description: 'The checklists, short or long.', writes: false, is_active: true, source: 'hand', runs: 1, last_run_at: at(300) },
      { procedure: 'money_check', version: 1, description: 'How the month is going.', writes: false, is_active: true, source: 'builtin', runs: 0, last_run_at: null },
    ],
    reports: { car_check: carReport() },
  }
}

export function carReport(partial: Partial<BranchReport> = {}): BranchReport {
  const branch = (b: string, target: string | null, taken: number, last: string | null = taken ? at(5) : null) => ({
    branch: b,
    target,
    taken,
    by_model: 0,
    last_taken_at: last,
    never_taken: taken === 0,
  })
  return {
    procedure: 'car_check',
    version: 1,
    writes: false,
    window_days: 60,
    runs: 2,
    last_run_at: at(5),
    steps: [
      { step_id: 'vehicle', kind: 'tool', tool: 'read_records', reached: 2, failed: 0, refused: 0, last_seen_at: at(5), last_failed_at: null, branches: [branch('next', 'service', 2)] },
      { step_id: 'service', kind: 'tool', tool: 'read_records', reached: 2, failed: 1, refused: 0, last_seen_at: at(5), last_failed_at: at(90), branches: [branch('next', 'has_codes', 1)] },
      {
        step_id: 'has_codes',
        kind: 'check',
        tool: '',
        reached: 1,
        failed: 0,
        refused: 0,
        last_seen_at: at(5),
        last_failed_at: null,
        branches: [branch('pass', 'codes', 0), branch('fail', null, 1)],
      },
      { step_id: 'codes', kind: 'tool', tool: 'read_records', reached: 0, failed: 0, refused: 0, last_seen_at: null, last_failed_at: null, branches: [branch('next', 'answer', 0)] },
      { step_id: 'answer', kind: 'say', tool: '', reached: 1, failed: 0, refused: 0, last_seen_at: at(5), last_failed_at: null, branches: [] },
    ],
    never_taken: ['has_codes:pass', 'codes:next'],
    summary: 'car_check v1 ran 2 times here; 2 branches never taken: has_codes:pass, codes:next.',
    ...partial,
  }
}
