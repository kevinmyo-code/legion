import { Link } from '@tanstack/react-router'
import {
  ArrowLeft,
  Ban,
  Check,
  CircleDashed,
  Clock,
  MessageCircleQuestion,
  Sparkles,
  TriangleAlert,
  XCircle,
  type LucideIcon,
} from 'lucide-react'
import type { ReactNode } from 'react'

import {
  CANT_READ_PROCEDURES,
  CANT_READ_RUNS,
  useBranchReport,
  useProcedures,
  useRun,
  useRuns,
  type ReportBranch,
  type ReportStep,
  type RunDetail,
  type RunStep,
  type RunSummary,
} from '@/api/procedures'
import { BriefingSection, Card, Quiet } from '@/components/briefing/parts'
import { useIsRecruiter } from '@/lib/recruiter'
import { cn } from '@/lib/utils'

/**
 * The run inspector and the branch report (procedures ticket 14, ADR 0070).
 *
 * Three pages under `/runs`, in the Morning briefing's reading column: the runs,
 * newest first; one run as a step timeline with what did NOT run said in words;
 * and one procedure's branch report, every way out of every step with how often
 * it was taken here, the never-taken ones marked with a word. Read-only.
 *
 * **A status is an icon AND a word** (ADR 0053), and a step a model steered says
 * "A model chose" (ruling 02). A recruiter's visit sees its own runs only; the
 * engine enforces that, this page only says so (ruling 07).
 */

export const RUNS_COPY = {
  intro: 'How LEGION answered: every procedure run in this household, newest first.',
  introVisit: 'How LEGION answered on this visit: every procedure run you asked for, newest first.',
  empty:
    'No procedure has run here in the last 60 days. A run appears here when the phone, the head unit, the web assistant or a visit asks for one.',
  emptyVisit: 'No procedure has run on this visit yet. Ask the assistant something like "how is the car?" and the run appears here.',
  noProcedures: 'No procedure is available here yet, so there is nothing to report on.',
  neverTaken: 'Never taken',
  endsRun: 'ends the run',
}

// =============================================================================
// Shared parts
// =============================================================================

const RUN_STATUS: Record<RunSummary['status'], { icon: LucideIcon; tone: string }> = {
  ok: { icon: Check, tone: 'bg-done-container text-done' },
  partial: { icon: TriangleAlert, tone: 'bg-unverified-bg text-unverified-fg' },
  failed: { icon: XCircle, tone: 'bg-destructive-container text-destructive-container-foreground' },
  waiting: { icon: Clock, tone: 'bg-primary-container text-primary-container-foreground' },
  cancelled: { icon: Ban, tone: 'bg-surface-3 text-muted-foreground' },
}

const STEP_OUTCOME: Record<RunStep['outcome'], { icon: LucideIcon; tone: string }> = {
  ok: { icon: Check, tone: 'bg-done-container text-done' },
  refused: { icon: Ban, tone: 'bg-unverified-bg text-unverified-fg' },
  failed: { icon: XCircle, tone: 'bg-destructive-container text-destructive-container-foreground' },
  asked: { icon: MessageCircleQuestion, tone: 'bg-primary-container text-primary-container-foreground' },
}

function Chip({ icon: Icon, tone, children }: { icon: LucideIcon; tone: string; children: ReactNode }) {
  return (
    <span
      className={cn(
        'inline-flex h-6 shrink-0 items-center gap-1 rounded-full px-2.5 text-[0.75rem] leading-none font-semibold',
        tone,
      )}
    >
      <Icon aria-hidden="true" className="size-3.5" strokeWidth={2.5} />
      {children}
    </span>
  )
}

export function RunStatusChip({ run }: { run: Pick<RunSummary, 'status' | 'status_label'> }) {
  const { icon, tone } = RUN_STATUS[run.status]
  return (
    <Chip icon={icon} tone={tone}>
      {run.status_label}
    </Chip>
  )
}

function NeverTakenChip() {
  return (
    <Chip icon={CircleDashed} tone="border border-dashed border-current text-estimate-fg">
      {RUNS_COPY.neverTaken}
    </Chip>
  )
}

export function when(iso: string | null): string {
  if (!iso) return 'never'
  return new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
}

export function duration(ms: number): string {
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

function Page({ children }: { children: ReactNode }) {
  return <div className="@container mx-auto flex w-full max-w-[54rem] flex-col gap-7 pb-16">{children}</div>
}

function Header({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <header>
      <h1 className="text-[1.875rem] leading-tight font-medium tracking-tight break-words @2xl:text-[2.5rem]">{title}</h1>
      {children}
    </header>
  )
}

function Lead({ children }: { children: ReactNode }) {
  return <p className="mt-2.5 max-w-[40rem] text-base leading-relaxed text-muted-foreground @2xl:text-lg">{children}</p>
}

function BackLink() {
  return (
    <Link to="/runs" className="inline-flex min-h-11 items-center gap-1.5 text-[0.875rem] font-medium text-primary">
      <ArrowLeft aria-hidden="true" className="size-4" />
      All runs
    </Link>
  )
}

function Unreadable({ lead, error }: { lead: string; error: Error | null }) {
  return (
    <Quiet>
      {lead} {error?.message}
    </Quiet>
  )
}

// =============================================================================
// /runs
// =============================================================================

export function RunsScreen() {
  const recruiter = useIsRecruiter()
  const runs = useRuns()
  const procedures = useProcedures()

  return (
    <Page>
      <Header title="Runs">
        <Lead>{recruiter ? RUNS_COPY.introVisit : RUNS_COPY.intro}</Lead>
      </Header>

      <BriefingSection n={1} id="runs-list" title="Runs">
        {runs.isPending ? (
          <Quiet>Reading the runs…</Quiet>
        ) : runs.isError ? (
          <Unreadable lead={CANT_READ_RUNS} error={runs.error} />
        ) : runs.data.results.length === 0 ? (
          <Card>
            <Quiet>{recruiter ? RUNS_COPY.emptyVisit : RUNS_COPY.empty}</Quiet>
          </Card>
        ) : (
          <Card className="px-0 py-1.5 @2xl:px-0">
            <ul className="divide-y divide-border">
              {runs.data.results.map((run) => (
                <li key={run.id}>
                  <RunRow run={run} />
                </li>
              ))}
            </ul>
            {runs.data.more ? (
              <Quiet className="px-4 pt-2 pb-1 @2xl:px-5">
                Showing the newest {runs.data.results.length}. Older runs are kept for {runs.data.retention_days} days.
              </Quiet>
            ) : null}
          </Card>
        )}
      </BriefingSection>

      <BriefingSection n={2} id="runs-reports" title="Branch reports">
        {procedures.isPending ? (
          <Quiet>Reading the procedures…</Quiet>
        ) : procedures.isError ? (
          <Unreadable lead={CANT_READ_PROCEDURES} error={procedures.error} />
        ) : procedures.data.length === 0 ? (
          <Card>
            <Quiet>{RUNS_COPY.noProcedures}</Quiet>
          </Card>
        ) : (
          <Card className="px-0 py-1.5 @2xl:px-0">
            <ul className="divide-y divide-border">
              {procedures.data.map((p) => (
                <li key={`${p.procedure}-${p.version}`}>
                  <Link
                    to="/runs/report/$name"
                    params={{ name: p.procedure }}
                    search={{ version: p.version }}
                    className="flex min-h-14 flex-col gap-0.5 px-4 py-2.5 hover:bg-surface-2 @2xl:px-5"
                  >
                    <span className="text-[0.9375rem] font-semibold">
                      {p.procedure} v{p.version}
                      {p.is_active ? '' : ' (not active)'}
                    </span>
                    <span className="text-[0.8125rem] text-muted-foreground">
                      {p.description} {p.writes ? 'Writes, asks first.' : 'Read-only.'}{' '}
                      {p.runs === 0 ? 'Not run here yet.' : `Ran ${p.runs} time${p.runs === 1 ? '' : 's'}, last ${when(p.last_run_at)}.`}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          </Card>
        )}
      </BriefingSection>
    </Page>
  )
}

function RunRow({ run }: { run: RunSummary }) {
  return (
    <Link
      to="/runs/$id"
      params={{ id: run.id }}
      aria-label={`${run.procedure} v${run.version}, ${run.status_label}, ${when(run.started_at)}`}
      className="flex min-h-14 flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5 hover:bg-surface-2 @2xl:px-5"
    >
      <span className="min-w-0 flex-1 basis-48">
        <span className="block text-[0.9375rem] font-semibold">
          {run.procedure} v{run.version}
        </span>
        <span className="block text-[0.8125rem] text-muted-foreground">
          {run.caller} · {run.who} · {when(run.started_at)}
        </span>
      </span>
      <span className="flex items-center gap-3">
        <span className="text-[0.8125rem] text-muted-foreground tabular-nums">
          {run.step_count} step{run.step_count === 1 ? '' : 's'} · {duration(run.ms)}
        </span>
        <RunStatusChip run={run} />
      </span>
    </Link>
  )
}

// =============================================================================
// /runs/<id>
// =============================================================================

export function RunDetailScreen({ id }: { id: string }) {
  const run = useRun(id)
  if (run.isPending) {
    return (
      <Page>
        <BackLink />
        <Quiet>Reading the run…</Quiet>
      </Page>
    )
  }
  if (run.isError) {
    return (
      <Page>
        <BackLink />
        <Unreadable lead="This run could not be read." error={run.error} />
      </Page>
    )
  }
  return <RunDetailView run={run.data} />
}

function RunDetailView({ run }: { run: RunDetail }) {
  return (
    <Page>
      <div>
        <BackLink />
        <Header title={`${run.procedure} v${run.version}`}>
          <div className="mt-2.5 flex flex-wrap items-center gap-2 text-[0.9375rem] text-muted-foreground">
            <RunStatusChip run={run} />
            <span>
              {run.caller} · {run.who} · {when(run.started_at)} · {duration(run.ms)}
            </span>
          </div>
          <Lead>{run.writes ? 'This procedure can write, and asks before it does.' : 'This procedure only reads.'}</Lead>
        </Header>
      </div>

      <BriefingSection n={1} id="run-answer" title="What it answered">
        <Card>
          <p className="text-[0.9375rem] leading-relaxed whitespace-pre-wrap">{run.result || 'Nothing was returned.'}</p>
          {run.inputs ? <Mono label="Inputs">{run.inputs}</Mono> : null}
        </Card>
      </BriefingSection>

      <BriefingSection n={2} id="run-steps" title="Steps, in order">
        {run.steps.length === 0 ? (
          <Card>
            <Quiet>No step was recorded for this run.</Quiet>
          </Card>
        ) : (
          <ol aria-label="Steps" className="flex flex-col">
            {run.steps.map((step, index) => (
              <StepItem key={step.seq} step={step} last={index === run.steps.length - 1} />
            ))}
          </ol>
        )}
      </BriefingSection>

      <BriefingSection n={3} id="run-not-run" title="Did not run">
        <Card>
          <p className="text-[0.9375rem]">{run.not_run_sentence}</p>
        </Card>
      </BriefingSection>
    </Page>
  )
}

function Mono({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="mt-2">
      <span className="text-[0.75rem] font-semibold text-muted-foreground">{label}</span>
      <pre className="mt-0.5 overflow-x-auto rounded-lg bg-surface-2 px-2.5 py-1.5 font-mono text-[0.8125rem] whitespace-pre-wrap break-all">
        {children}
      </pre>
    </div>
  )
}

function StepItem({ step, last }: { step: RunStep; last: boolean }) {
  const { icon, tone } = STEP_OUTCOME[step.outcome]
  const model = step.chosen_by === 'model'
  return (
    <li className="relative flex gap-3 pb-3">
      <span aria-hidden="true" className="flex flex-col items-center">
        <span className="grid size-[26px] shrink-0 place-items-center rounded-full bg-surface-3 text-[0.75rem] font-semibold">
          {step.seq + 1}
        </span>
        {last ? null : <span className="mt-1 w-px flex-1 bg-border" />}
      </span>
      <Card className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <h3 className="text-[0.9375rem] font-semibold break-all">{step.step_id}</h3>
          <span className="text-[0.8125rem] text-muted-foreground">
            {step.kind}
            {step.tool ? ` · ${step.tool}` : ''} · {duration(step.ms)}
          </span>
          <span className="ml-auto">
            <Chip icon={icon} tone={tone}>
              {step.outcome_label}
            </Chip>
          </span>
        </div>
        <p className="mt-1.5 text-[0.875rem]">
          {step.branch ? (
            <>
              {model ? (
                <span className="inline-flex items-center gap-1 font-semibold text-estimate-fg">
                  <Sparkles aria-hidden="true" className="size-3.5" />
                  A model chose
                </span>
              ) : (
                <span className="font-semibold">{step.chosen_by_label}</span>
              )}{' '}
              <span className="font-mono">{step.branch}</span>
            </>
          ) : (
            <span className="text-muted-foreground">The run stopped here, or this step ends it.</span>
          )}
        </p>
        {step.args ? <Mono label="Arguments">{step.args}</Mono> : null}
        {step.result ? <Mono label="Result (trimmed)">{step.result}</Mono> : null}
        {step.model ? (
          <p className="mt-2 text-[0.8125rem] text-muted-foreground">
            {step.model}
            {step.prompt_tokens !== null ? ` · ${step.prompt_tokens} tokens in, ${step.output_tokens ?? 0} out` : ''}
            {step.cost_estimate_micro_usd !== null
              ? ` · Estimate: $${(step.cost_estimate_micro_usd / 1_000_000).toFixed(6)}, not a billed figure`
              : ''}
          </p>
        ) : null}
      </Card>
    </li>
  )
}

// =============================================================================
// /runs/report/<name>
// =============================================================================

export function BranchReportScreen({ name, version }: { name: string; version?: number }) {
  const report = useBranchReport(name, version)
  if (report.isPending) {
    return (
      <Page>
        <BackLink />
        <Quiet>Reading the branch report…</Quiet>
      </Page>
    )
  }
  if (report.isError) {
    return (
      <Page>
        <BackLink />
        <Unreadable lead="This branch report could not be read." error={report.error} />
      </Page>
    )
  }
  const r = report.data
  return (
    <Page>
      <div>
        <BackLink />
        <Header title={`Branch report: ${r.procedure} v${r.version}`}>
          <Lead>{r.summary}</Lead>
          <p className="mt-2 text-[0.8125rem] text-muted-foreground">
            Counts are this household&apos;s runs over the last {r.window_days} days. Last run {when(r.last_run_at)}.
          </p>
        </Header>
      </div>

      <BriefingSection n={1} id="report-steps" title="Every step and every way out of it">
        <ol aria-label="Branch report" className="flex flex-col gap-3">
          {r.steps.map((step) => (
            <ReportStepCard key={step.step_id} step={step} />
          ))}
        </ol>
      </BriefingSection>
    </Page>
  )
}

function ReportStepCard({ step }: { step: ReportStep }) {
  const trouble = step.failed + step.refused
  return (
    <li>
      <Card>
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <h3 className="text-[0.9375rem] font-semibold break-all">{step.step_id}</h3>
          <span className="text-[0.8125rem] text-muted-foreground">
            {step.kind}
            {step.tool ? ` · ${step.tool}` : ''}
          </span>
        </div>
        <p className="mt-1 text-[0.875rem] text-muted-foreground">
          {step.reached === 0 ? 'Never reached.' : `Ran in ${step.reached} run${step.reached === 1 ? '' : 's'}, last ${when(step.last_seen_at)}.`}
          {trouble > 0 ? (
            <span className="ml-1 inline-flex items-center gap-1 font-semibold text-destructive">
              <XCircle aria-hidden="true" className="size-3.5" />
              Failed {step.failed}, refused {step.refused}, last {when(step.last_failed_at)}.
            </span>
          ) : null}
        </p>
        {step.branches.length === 0 ? (
          <p className="mt-2 text-[0.875rem]">Ends the run with its answer.</p>
        ) : (
          <table className="mt-2 w-full text-[0.875rem]">
            <thead className="sr-only">
              <tr>
                <th>Branch</th>
                <th>Leads to</th>
                <th>Runs that took it</th>
              </tr>
            </thead>
            <tbody>
              {step.branches.map((b) => (
                <BranchRow key={b.branch} branch={b} />
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </li>
  )
}

function BranchRow({ branch }: { branch: ReportBranch }) {
  return (
    <tr className={cn('border-t border-border', branch.never_taken && 'bg-surface-2')}>
      <td className="py-2 pr-2 align-top font-mono">{branch.branch}</td>
      <td className="py-2 pr-2 align-top text-muted-foreground">→ {branch.target ?? RUNS_COPY.endsRun}</td>
      <td className="py-2 text-right align-top">
        {branch.never_taken ? (
          <NeverTakenChip />
        ) : (
          <span className="tabular-nums">
            {branch.taken} run{branch.taken === 1 ? '' : 's'}
            {branch.by_model > 0 ? `, a model chose ${branch.by_model}` : ''}
            <span className="block text-[0.75rem] text-muted-foreground">last {when(branch.last_taken_at)}</span>
          </span>
        )}
      </td>
    </tr>
  )
}
