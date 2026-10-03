import { ChevronDown } from 'lucide-react'
import { useId, useMemo, useState, type KeyboardEvent } from 'react'

import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { cn } from '@/lib/utils'

/**
 * Pick a category for one transaction: a pill showing the current one, opening
 * a short searchable list.
 *
 * The ARIA combobox pattern with the filter field as the combobox and the list
 * as its listbox (focus stays in the field; the highlighted option is named by
 * `aria-activedescendant`), so a screen reader and the keyboard get the same
 * control a mouse does: type to narrow, arrows to move, Enter to pick, Escape to
 * close. The popover itself is Radix's, which owns focus return and dismissal.
 *
 * **A row with no category says so in words** ("Choose category"), never by an
 * empty pill. `onClear` is offered only when the person has a choice of their
 * own to take back; a category the bank file or a rule supplied is replaced by
 * choosing another, not cleared.
 */

const CLEAR = '\u0000clear'

export function CategoryCombobox({
  value,
  categories,
  label,
  onPick,
  onClear,
  clearLabel = 'Undo my choice',
  className,
}: {
  value: string | null
  categories: readonly string[]
  /** What the control is for, spoken: "Category for Shell 5521". */
  label: string
  onPick: (category: string) => void
  onClear?: () => void
  clearLabel?: string
  className?: string
}) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const listId = useId()

  const options = useMemo(() => {
    const needle = query.trim().toLowerCase()
    const matches = categories.filter((name) => name.toLowerCase().includes(needle))
    return onClear && needle === '' ? [CLEAR, ...matches] : matches
  }, [categories, query, onClear])

  function reset(next: boolean) {
    setOpen(next)
    setQuery('')
    setActive(0)
  }

  function choose(option: string) {
    if (option === CLEAR) onClear?.()
    else onPick(option)
    reset(false)
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setActive((index) => Math.min(options.length - 1, index + 1))
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setActive((index) => Math.max(0, index - 1))
    } else if (event.key === 'Enter') {
      event.preventDefault()
      const option = options[active]
      if (option !== undefined) choose(option)
    }
  }

  const uncategorised = value === null

  return (
    <Popover open={open} onOpenChange={reset}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-haspopup="listbox"
          aria-label={`${label}: ${value ?? 'none'}`}
          className={cn(
            'inline-flex h-9 max-w-full items-center gap-1.5 rounded-full px-3.5 text-[0.9375rem] outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary',
            uncategorised
              ? 'bg-primary-container text-primary-container-foreground'
              : 'bg-surface-2 text-foreground hover:bg-surface-3',
            className,
          )}
        >
          <span className="truncate">{value ?? 'Choose category'}</span>
          <ChevronDown className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        </button>
      </PopoverTrigger>
      <PopoverContent>
        <input
          role="combobox"
          aria-label="Find a category"
          aria-expanded="true"
          aria-controls={listId}
          aria-activedescendant={options[active] === undefined ? undefined : `${listId}-${active}`}
          aria-autocomplete="list"
          autoComplete="off"
          value={query}
          placeholder="Find a category"
          onChange={(event) => {
            setQuery(event.target.value)
            setActive(0)
          }}
          onKeyDown={onKeyDown}
          className="mb-1 h-10 w-full rounded-full bg-surface-2 px-4 text-[0.9375rem] outline-none focus-visible:bg-surface-3"
        />
        <ul id={listId} role="listbox" aria-label="Categories" className="max-h-60 overflow-y-auto">
          {options.map((option, index) => {
            const isClear = option === CLEAR
            return (
              <li
                key={option}
                id={`${listId}-${index}`}
                role="option"
                aria-selected={!isClear && option === value}
                onMouseDown={(event) => event.preventDefault()}
                onMouseEnter={() => setActive(index)}
                onClick={() => choose(option)}
                className={cn(
                  'flex min-h-10 cursor-pointer items-center rounded-full px-4 text-[0.9375rem]',
                  index === active && 'bg-surface-3',
                  !isClear && option === value && 'font-semibold',
                  isClear && 'text-muted-foreground',
                )}
              >
                {isClear ? clearLabel : option}
                {!isClear && option === value && (
                  <span className="ml-auto text-[0.8125rem] text-muted-foreground">current</span>
                )}
              </li>
            )
          })}
          {options.length === 0 && (
            <li role="presentation" className="px-4 py-3 text-[0.875rem] text-muted-foreground">
              No category matches. New ones are added on the Categories tab.
            </li>
          )}
        </ul>
      </PopoverContent>
    </Popover>
  )
}
