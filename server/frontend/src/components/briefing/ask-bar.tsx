import { Sparkles } from 'lucide-react'
import { useState, type FormEvent } from 'react'

import { useAssistant } from '@/assistant/assistant-context'
import { Button } from '@/components/ui/button'

/** Questions the household's own data can answer; nothing here is a nudge. */
export const ASK_SUGGESTIONS = [
  'What did we spend on groceries?',
  'When is the next car service due?',
  'What is on this week?',
] as const

/**
 * The ask bar at the top of the briefing (prototype A's "Ask Alfred").
 *
 * It is not a second assistant: a question typed here goes into the ONE
 * conversation `AssistantProvider` holds (`client.send`) and opens the members'
 * own chat (the docked panel on the desk, the sheet on a phone), where the
 * answer arrives. The name is the companion's own, read from the engine, never
 * written into copy (CLAUDE.md section 1).
 */
export function AskBar() {
  const { client, name, ui } = useAssistant()
  const [text, setText] = useState('')

  const ask = (question: string) => {
    const sent = client.send(question)
    ui.setOpen(true)
    if (sent) setText('')
  }

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (text.trim() === '') {
      ui.focusBox()
      return
    }
    ask(text)
  }

  return (
    <div>
      <form
        role="search"
        aria-label={`Ask ${name}`}
        onSubmit={onSubmit}
        className="flex items-center gap-2.5 rounded-full bg-card py-1.5 pr-1.5 pl-2 shadow-sm focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-primary"
      >
        <span aria-hidden="true" className="grid size-10 shrink-0 place-items-center rounded-full bg-primary-container text-primary-container-foreground">
          <Sparkles className="size-[18px]" />
        </span>
        <input
          value={text}
          onChange={(event) => setText(event.target.value)}
          aria-label={`Ask ${name} a question`}
          placeholder={`Ask ${name}…`}
          className="min-h-11 min-w-0 flex-1 bg-transparent text-[0.9375rem] outline-none placeholder:text-muted-foreground"
        />
        <Button type="submit" className="h-10 rounded-full px-5">
          Ask
        </Button>
      </form>
      <ul aria-label="Questions to ask" className="mt-2.5 flex gap-2 overflow-x-auto pl-1 [scrollbar-width:none] md:flex-wrap">
        {ASK_SUGGESTIONS.map((question) => (
          <li key={question} className="shrink-0">
            <button
              type="button"
              onClick={() => ask(question)}
              className="min-h-11 rounded-full bg-surface-2 px-3.5 text-[0.8125rem] transition-colors outline-none hover:bg-surface-3 focus-visible:outline-2 focus-visible:outline-primary"
            >
              {question}
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
