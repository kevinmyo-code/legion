import { useState } from 'react'

import {
  ConversationsError,
  DEFAULT_PER_DEVICE,
  MAX_PER_DEVICE,
  useConversations,
} from '@/api/conversations'
import type { ConversationBatch, ConversationDevice } from '@/api/conversations'
import { SettingsPage } from '@/components/settings/settings-page'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptySentence, ErrorSentence, Panel } from '@/components/workbench/page'

function stamp(iso: string): string {
  const date = new Date(iso)
  return `${date.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })}, ${date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}`
}

/**
 * `/settings/conversations`: the conversations your own devices and this
 * browser have uploaded, as the engine cuts them: the member's own lines, the
 * name of each tool called with whether it worked and how long it took, and
 * the session and turn times. Never the assistant's replies or a tool's
 * arguments or results. Newest first, one panel per device. Both surfaces (#160).
 *
 * Own conversations only, by the engine's design: another member's words and
 * tool results are private to them (ADR 0052), so the household's page is each
 * member's own. The engine sends only the member's own lines, tool names and
 * times: never the assistant's replies or a tool's arguments or results
 * (CLAUDE.md section 7), so the page never relies on the device having
 * redacted anything.
 *
 * Each stored batch is one block. A conversation the device split across two
 * uploads shows as two blocks: the page does not stitch them, and says so.
 * Per-turn saves (#450) add rows, which appear here as more, smaller blocks;
 * the engine already sends each batch's `run_id`, so joining them later needs
 * no new shape.
 *
 * **The engine being down is its own sentence.** An unreachable engine and an
 * empty one are different things to say (CLAUDE.md section 1), so a failed
 * read never renders as "no conversations".
 */
/** 401 and 403 are the engine answering "no", which is not the engine being down. */
function refused(error: unknown): boolean {
  return error instanceof ConversationsError && (error.status === 401 || error.status === 403)
}

export function ConversationsScreen() {
  const [limit, setLimit] = useState(DEFAULT_PER_DEVICE)
  const conversations = useConversations(limit)
  const devices = conversations.data
  const moreToShow =
    limit < MAX_PER_DEVICE && (devices ?? []).some((device) => device.total > device.batches.length)

  return (
    <SettingsPage
      title="Conversations"
      subtitle="What you said to the assistant and which tools it used, as your phone and this browser uploaded it. Newest first. Only yours: nobody else in the household sees these."
    >
      {conversations.isError && (
        <ErrorSentence>
          {refused(conversations.error)
            ? 'The engine refused this request (you may need to sign in again), so this is not the real list of conversations. Nothing was changed.'
            : 'Could not reach the engine, so this is not the real list of conversations. Nothing was changed.'}
        </ErrorSentence>
      )}
      {conversations.isPending && <Skeleton className="h-32 rounded-card" />}
      {devices?.length === 0 && (
        <EmptySentence>The engine holds no conversations from your devices.</EmptySentence>
      )}
      {devices?.map((device) => <DevicePanel key={device.device_label} device={device} />)}
      {devices && devices.length > 0 && (
        <p className="text-[0.8125rem] text-muted-foreground">
          The assistant's replies and tool details are not shown here. A conversation a device uploaded in
          two parts shows as two blocks.
        </p>
      )}
      {moreToShow && (
        <Button variant="outline" className="self-start" onClick={() => setLimit(MAX_PER_DEVICE)}>
          Show up to {MAX_PER_DEVICE} per device
        </Button>
      )}
    </SettingsPage>
  )
}

function DevicePanel({ device }: { device: ConversationDevice }) {
  const shown = device.batches.length
  return (
    <Panel
      title={device.device_label}
      description={
        shown < device.total
          ? `Showing the newest ${shown} of ${device.total} uploads.`
          : `${device.total} ${device.total === 1 ? 'upload' : 'uploads'}.`
      }
    >
      <ul className="flex flex-col gap-3">
        {device.batches.map((batch, index) => (
          <BatchBlock key={batch.id} batch={batch} initiallyOpen={index === 0} />
        ))}
      </ul>
    </Panel>
  )
}

function BatchBlock({ batch, initiallyOpen }: { batch: ConversationBatch; initiallyOpen: boolean }) {
  return (
    <li className="rounded-card bg-surface-1 px-4 py-3">
      <details open={initiallyOpen}>
        <summary className="min-h-11 cursor-pointer py-2 text-[0.9375rem] font-medium">
          {stamp(batch.received_at)}
          {!batch.readable && (
            <span className="font-normal text-muted-foreground"> (could not be read)</span>
          )}
        </summary>
        {batch.readable ? (
          <>
            <p className="text-[0.8125rem] break-words text-muted-foreground">{batch.header}</p>
            <pre className="mt-2 overflow-x-auto text-[0.8125rem] leading-relaxed whitespace-pre-wrap">
              {batch.lines.join('\n')}
            </pre>
          </>
        ) : (
          <ErrorSentence role="status">{batch.problem}</ErrorSentence>
        )}
      </details>
    </li>
  )
}
