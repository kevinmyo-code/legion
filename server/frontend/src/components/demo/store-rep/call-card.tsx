import { Check, Copy } from 'lucide-react'
import { useState } from 'react'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { type CallCard, latencyRows, OUTCOME_TONE } from '@/lib/store-rep'

/**
 * The after-call card (`server/demo/shop/call_card.py`): what a business system would receive when
 * the call ends. Every word and number is the engine's, built by code from the conversation's own
 * record; nothing here is worked out in the browser but the table's labels.
 */
export function CallCardView({ card }: { card: CallCard }) {
  const rows = latencyRows(card.turns)
  return (
    <div className="flex min-h-0 flex-col gap-4" data-testid="call-card">
      <div className="flex flex-wrap items-center gap-3">
        <span
          className={`inline-flex min-h-8 items-center rounded-full px-3 text-sm font-semibold ${OUTCOME_TONE[card.outcome]}`}
          data-outcome={card.outcome}
        >
          {card.outcome_words}
        </span>
        {card.median_first_audio_ms !== null && (
          <span className="text-sm text-muted-foreground">
            First audio, median: <strong className="text-foreground">{card.median_first_audio_ms} ms</strong>
          </span>
        )}
      </div>
      <ul className="flex list-disc flex-col gap-1 pl-5 text-[0.9375rem]">
        {card.summary.map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>
      <Tabs defaultValue="record" className="min-h-0">
        <TabsList>
          <TabsTrigger value="record">CRM record</TabsTrigger>
          <TabsTrigger value="transcript">Transcript</TabsTrigger>
          <TabsTrigger value="latency">Latency</TabsTrigger>
        </TabsList>
        <TabsContent value="record">
          <RecordView record={card.record} />
        </TabsContent>
        <TabsContent value="transcript">
          <ol className="flex max-h-[45vh] flex-col gap-2 overflow-y-auto pr-1">
            {card.transcript.map((line, index) => (
              <li key={`${index}-${line.at}`} className="flex flex-col gap-0.5">
                <span className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  {line.who === 'visitor' ? 'Caller' : 'Rep'}
                  {line.typed && line.who === 'visitor' ? ' (typed)' : ''}
                </span>
                <span className="text-[0.9375rem]">{line.text}</span>
              </li>
            ))}
          </ol>
        </TabsContent>
        <TabsContent value="latency">
          <table className="w-full text-left text-sm">
            <caption className="pb-2 text-left text-muted-foreground">
              Engine side: from the words reaching the engine to its first audio back. Upload and playback are not in it.
            </caption>
            <thead>
              <tr className="border-b border-outline-variant">
                <th className="py-1 font-semibold">Turn</th>
                <th className="py-1 font-semibold">How</th>
                <th className="py-1 text-right font-semibold">First audio</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.label} className="border-b border-outline-variant/50">
                  <td className="py-1">{row.label}</td>
                  <td className="py-1">{row.via}</td>
                  <td className="py-1 text-right tabular-nums">{row.ms === null ? 'not spoken' : `${row.ms} ms`}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </TabsContent>
      </Tabs>
    </div>
  )
}

function RecordView({ record }: { record: Record<string, unknown> }) {
  const text = JSON.stringify(record, null, 2)
  const [copied, setCopied] = useState(false)
  return (
    <div className="relative">
      <Button
        variant="outline"
        size="sm"
        className="absolute top-2 right-2"
        onClick={() => {
          void navigator.clipboard?.writeText(text).then(() => setCopied(true), () => {})
        }}
      >
        {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
        {copied ? 'Copied' : 'Copy'}
      </Button>
      <pre className="max-h-[45vh] overflow-auto rounded-md border border-outline-variant bg-muted p-3 font-mono text-xs leading-relaxed">
        {text}
      </pre>
    </div>
  )
}

export function CallCardDialog({
  card,
  open,
  onOpenChange,
}: {
  card: CallCard | null
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>After the call</DialogTitle>
          <DialogDescription>
            What a business system would receive from this call: outcome, summary, transcript and a record, built by
            code from the engine&apos;s own log.
          </DialogDescription>
        </DialogHeader>
        {card ? <CallCardView card={card} /> : <p className="text-muted-foreground">No call on this visit yet.</p>}
      </DialogContent>
    </Dialog>
  )
}
