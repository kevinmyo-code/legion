import { ExternalLink } from 'lucide-react'
import type { ReactNode } from 'react'

import { CANT_READ_CLAUDE, ClaudeReadRefused, useClaudeTasks, type ClaudeArtifact, type ClaudeTask } from '@/api/claude'
import { Card, Quiet } from '@/components/briefing/parts'

/**
 * The Claude session on the web desk (claude-session ticket 09, Kevin 2026-11-03: "delivered
 * files also show on the web desk under the task"). The tasks the assistant sent to Claude Code on the
 * laptops, newest first, each with its status in words, its result, and every page it delivered
 * with an Open link. Read-only: sending, answering and the files stay on the phone's Claude session
 * screen and in voice.
 *
 * A page opens in a new tab from the engine's own signed link. The engine serves it with
 * `Content-Security-Policy: sandbox allow-scripts`, so its scripts run in an opaque origin and
 * cannot reach this app's session.
 */

export const CLAUDE_COPY = {
  title: 'Claude session',
  intro: 'What the assistant asked Claude to do on your laptops, newest first, with any page it made for you.',
  empty: 'No task has been sent to the Claude session yet. Ask the assistant to use Claude on your Alienware.',
  pageWaiting: 'The page has not arrived on the engine.',
  open: 'Open page',
}

const STATUS_WORDS: Record<string, string> = {
  queued: 'Queued, nothing has run yet',
  needs_machine: 'Waiting for you to pick a machine',
  running: 'Running',
  cancel_requested: 'Stopping',
  done: 'Done',
  failed: 'Did not finish',
  needs_confirmation: 'Waiting for your yes',
  cancelled: 'Cancelled',
}

export function statusWords(status: string): string {
  return STATUS_WORDS[status] ?? status
}

function Page({ children }: { children: ReactNode }) {
  return <div className="@container mx-auto flex w-full max-w-[54rem] flex-col gap-5 pb-16">{children}</div>
}

function ArtifactLink({ artifact }: { artifact: ClaudeArtifact }) {
  return (
    <a
      href={artifact.url}
      target="_blank"
      rel="noopener noreferrer"
      className="inline-flex min-h-11 items-center gap-1.5 text-[0.9375rem] font-medium text-primary"
    >
      <ExternalLink aria-hidden="true" className="size-4" />
      {CLAUDE_COPY.open}: {artifact.title}
    </a>
  )
}

function pageWaiting(task: ClaudeTask): boolean {
  return (
    task.deliver === 'phone' &&
    task.status === 'done' &&
    task.artifacts.length === 0 &&
    task.files.some((f) => /\.html?$/i.test(f.name) && !f.uploaded)
  )
}

function TaskCard({ task }: { task: ClaudeTask }) {
  const where = task.machine ? ` on the ${task.machine}` : ''
  return (
    <Card>
      <p className="text-base leading-snug font-medium break-words">{task.request}</p>
      <Quiet className="mt-1">
        Task {task.id}
        {where}: {statusWords(task.status)}
      </Quiet>
      {task.summary ? <p className="mt-2 text-[0.9375rem] leading-relaxed break-words">{task.summary}</p> : null}
      {task.question ? <p className="mt-2 text-[0.9375rem] break-words">Question: {task.question}</p> : null}
      {task.artifacts.length > 0 ? (
        <ul className="mt-2 flex flex-col">
          {task.artifacts.map((a) => (
            <li key={a.id}>
              <ArtifactLink artifact={a} />
            </li>
          ))}
        </ul>
      ) : null}
      {pageWaiting(task) ? <Quiet className="mt-2">{CLAUDE_COPY.pageWaiting}</Quiet> : null}
    </Card>
  )
}

export function ClaudeScreen() {
  const tasks = useClaudeTasks()
  return (
    <Page>
      <header>
        <h1 className="text-[1.875rem] leading-tight font-medium tracking-tight @2xl:text-[2.5rem]">{CLAUDE_COPY.title}</h1>
        <p className="mt-2.5 max-w-[40rem] text-base leading-relaxed text-muted-foreground">{CLAUDE_COPY.intro}</p>
      </header>
      {tasks.isPending ? <Quiet>Reading the tasks...</Quiet> : null}
      {tasks.isError ? (
        <Quiet>{tasks.error instanceof ClaudeReadRefused ? tasks.error.message : CANT_READ_CLAUDE}</Quiet>
      ) : null}
      {tasks.data && tasks.data.length === 0 ? <Quiet>{CLAUDE_COPY.empty}</Quiet> : null}
      {tasks.data?.map((t) => <TaskCard key={t.id} task={t} />)}
    </Page>
  )
}
