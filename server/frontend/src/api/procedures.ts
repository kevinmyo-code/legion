import { useQuery } from '@tanstack/react-query'

import { api } from '@/api/client'
import { sentenceFromBody } from '@/api/refusal'
import type { components } from '@/api/schema'

/**
 * The run inspector and the branch report (procedures ticket 14), read-only.
 *
 * Every read is scoped by the engine to the signed-in household, and a recruiter's
 * visit to its own runs (`server/procedures/views.py`). Nothing here writes.
 */

export type RunSummary = components['schemas']['RunSummary']
export type RunList = components['schemas']['RunList']
export type RunDetail = components['schemas']['RunDetail']
export type RunStep = components['schemas']['RunStep']
export type ProcedureVersion = components['schemas']['ProcedureVersion']
export type BranchReport = components['schemas']['BranchReport']
export type ReportStep = components['schemas']['ReportStep']
export type ReportBranch = components['schemas']['ReportBranch']

export const PROCEDURES_KEY = ['procedures'] as const

export const CANT_READ_RUNS =
  "Can't reach the engine, so the runs can't be listed. Nothing is known about them from here."
export const CANT_READ_PROCEDURES =
  "Can't reach the engine, so the procedures can't be listed for a branch report."

/** A read the engine answered with a sentence (a 404 for another household's run). */
export class ReadRefused extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ReadRefused'
    this.status = status
  }
}

function refused(path: string, response: Response, body: unknown): ReadRefused {
  return new ReadRefused(sentenceFromBody(body) ?? `${path} answered ${response.status}.`, response.status)
}

export function useRuns() {
  return useQuery({
    queryKey: [...PROCEDURES_KEY, 'runs'],
    queryFn: async (): Promise<RunList> => {
      const { data, error, response } = await api.GET('/api/procedures/runs')
      if (!response.ok || !data) throw refused('GET /api/procedures/runs', response, error)
      return data
    },
    retry: false,
  })
}

export function useRun(id: string) {
  return useQuery({
    queryKey: [...PROCEDURES_KEY, 'run', id],
    queryFn: async (): Promise<RunDetail> => {
      const { data, error, response } = await api.GET('/api/procedures/runs/{run_id}', {
        params: { path: { run_id: id } },
      })
      if (!response.ok || !data) throw refused('GET /api/procedures/runs/<id>', response, error)
      return data
    },
    retry: false,
  })
}

export function useProcedures() {
  return useQuery({
    queryKey: [...PROCEDURES_KEY, 'list'],
    queryFn: async (): Promise<ProcedureVersion[]> => {
      const { data, error, response } = await api.GET('/api/procedures/')
      if (!response.ok || !data) throw refused('GET /api/procedures/', response, error)
      return data
    },
    retry: false,
  })
}

export function useBranchReport(name: string, version?: number) {
  return useQuery({
    queryKey: [...PROCEDURES_KEY, 'report', name, version ?? null],
    queryFn: async (): Promise<BranchReport> => {
      const { data, error, response } = await api.GET('/api/procedures/report', {
        params: { query: version === undefined ? { name } : { name, version } },
      })
      if (!response.ok || !data) throw refused('GET /api/procedures/report', response, error)
      return data
    },
    retry: false,
  })
}
