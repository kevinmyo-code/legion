import { useState } from 'react'

import { voiceNotes, type VoiceNote } from '@/api/aspects'
import { useRows } from '@/api/synced'
import { Loaded } from '@/components/workbench/loaded'
import { PageHeader, Panel } from '@/components/workbench/page'
import { formatInstant, formatMinutes } from '@/lib/figures'

/**
 * `/notes`: voice notes, as text.
 *
 * The engine holds a voice note's transcript and summary and never its audio
 * (ADR 0041, and the voice-notes schema has no audio column at all), so the web
 * can read a meeting back and cannot play it. Each note says so in a sentence at
 * the top of its detail, not behind a tooltip: **"Audio is not available on the
 * web."**
 *
 * Both the transcript and the summary were produced by a model (the row's
 * provenance is `LLM_DERIVED`, the one value that is true of a voice note: none
 * of the reconciliation tags is, because a transcript states no total to check).
 * The summary is anchored by the transcript beside it and the transcript by the
 * audio on the phone, so the screen says the summary is machine-written and puts
 * the transcript under it.
 *
 * Read only. Deleting a note would remove its text here while the audio stays on
 * the phone, which breaks the anchor chain ADR 0041 relies on (summary,
 * transcript and audio go together), and the web cannot reach the audio to
 * delete it. That is a decision for Kevin and is raised as a question, not built.
 */

function length(note: VoiceNote): string {
  if (!note.ended_at) return 'No end time recorded'
  const minutes = Math.max(0, Math.round((Date.parse(note.ended_at) - Date.parse(note.started_at)) / 60_000))
  return minutes < 1 ? 'Under a minute' : formatMinutes(minutes)
}

function noteTitle(note: VoiceNote): string {
  return note.title && note.title.trim() !== '' ? note.title : 'Untitled recording'
}

function kindWords(kind: string): string {
  if (kind === 'MEETING') return 'Meeting'
  if (kind === 'SOLO') return 'Solo note'
  return kind
}

function NoteDetail({ note }: { note: VoiceNote }) {
  return (
    <article className="flex flex-col gap-4" aria-label={noteTitle(note)}>
      <header>
        <h3 className="text-xl">{noteTitle(note)}</h3>
        <p className="mt-0.5 text-[0.9375rem] text-muted-foreground">
          {kindWords(note.kind)}, {formatInstant(note.started_at)}, {length(note)}
        </p>
        <p className="mt-2 rounded-control bg-surface-2 px-4 py-2.5 text-[0.9375rem]">
          Audio is not available on the web.
        </p>
        {note.interrupted && (
          <p className="mt-2 rounded-control bg-surface-3 px-4 py-2.5 text-[0.9375rem]">
            This recording was interrupted, so the transcript and summary may be incomplete.
          </p>
        )}
      </header>
      <section aria-label="Summary">
        <h4 className="text-[1rem] font-medium">Summary</h4>
        <p className="mb-1 text-[0.8125rem] text-muted-foreground">
          Written by a model from the transcript, so it can be wrong. The transcript below is the record.
        </p>
        <p className="whitespace-pre-wrap">{note.summary?.trim() ? note.summary : 'No summary was written for this note.'}</p>
      </section>
      <section aria-label="Transcript">
        <h4 className="text-[1rem] font-medium">Transcript</h4>
        <p className="mb-1 text-[0.8125rem] text-muted-foreground">
          Produced by a model from the audio, so names and numbers in it are not checked.
        </p>
        {note.transcript?.trim() ? (
          <div className="max-h-[28rem] overflow-y-auto rounded-card bg-surface-2 p-4">
            <p className="whitespace-pre-wrap">{note.transcript}</p>
          </div>
        ) : (
          <p className="text-muted-foreground">No transcript was stored for this note.</p>
        )}
      </section>
    </article>
  )
}

export function NotesScreen() {
  const query = useRows(voiceNotes)
  const [chosen, setChosen] = useState<string | null>(null)

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Notes" subtitle="Voice notes recorded on the phone, read back as text." />
      <Panel title="Voice notes">
        <Loaded
          query={query}
          what="voice notes"
          quiet
          empty="No voice notes yet. A note recorded on the phone appears here as text once it has synced."
          render={(rows) => {
            const ordered = [...rows].sort((a, b) => b.started_at.localeCompare(a.started_at))
            const note = ordered.find((row) => row.id === chosen) ?? ordered[0]
            return (
              <div className="grid gap-6 lg:grid-cols-[20rem_1fr]">
                <ul aria-label="Voice notes" className="flex max-h-[40rem] flex-col gap-1 overflow-y-auto">
                  {ordered.map((row) => (
                    <li key={row.id}>
                      <button
                        type="button"
                        aria-current={row.id === note.id ? 'true' : undefined}
                        onClick={() => setChosen(row.id)}
                        className={`flex w-full flex-col items-start rounded-control px-4 py-3 text-left transition-colors ${
                          row.id === note.id ? 'bg-primary-container text-primary-container-foreground' : 'hover:bg-surface-2'
                        }`}
                      >
                        <span className="font-medium">{noteTitle(row)}</span>
                        <span className="text-[0.8125rem] text-muted-foreground">
                          {kindWords(row.kind)}, {formatInstant(row.started_at)}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
                <NoteDetail key={note.id} note={note} />
              </div>
            )
          }}
        />
      </Panel>
    </div>
  )
}
