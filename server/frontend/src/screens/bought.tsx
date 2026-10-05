import { Link } from '@tanstack/react-router'
import { Pencil, Plus, Search, X } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'

import { CANT_READ_LOG, usePurchases } from '@/api/purchases'
import type { Purchase, PurchaseList } from '@/api/types'
import { EntryDetails, EntryRow, PriceText, PrivateNote, SeenBy, SourceChip } from '@/components/purchases/entry'
import { PurchaseForm } from '@/components/purchases/form'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { EmptySentence, ErrorSentence, PageHeader, Panel } from '@/components/workbench/page'
import { todayEpochDay } from '@/lib/day'
import { answerSentence, dayLabel, whoWords } from '@/lib/purchases'
import { useSurface } from '@/lib/surface'

/**
 * The bought log (purchase-log 07, ticket 05's variant C: search first).
 *
 * "When did we last buy shampoo?" is the question, so the screen is a search box
 * and the answer. The answer names the EXACT entry it found, its date and who
 * ("Shampoo, bought Sep 20 by Mia"), because matching is loose and "shampoo" may
 * have matched "Head & Shoulders shampoo". Then come the other matches, newest
 * first, each with where it came from.
 *
 * Three honest states, never one: no match is "No record of buying X" (the log
 * has nothing; it was never claimed not to be bought), a read that failed says it
 * cannot be read (an unreadable log is not an empty log), and a stale read says
 * so. Every price says it was entered by hand.
 */

function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), ms)
    return () => clearTimeout(timer)
  }, [value, ms])
  return debounced
}

function ListSkeleton() {
  return (
    <div className="flex flex-col gap-2" aria-busy="true" aria-label="Loading the bought log">
      <Skeleton className="h-16 w-full" />
      <Skeleton className="h-16 w-full" />
      <Skeleton className="h-16 w-3/4" />
    </div>
  )
}

/** The pill field both surfaces search with. */
function SearchField({
  value,
  onChange,
  placeholder,
  label,
}: {
  value: string
  onChange: (next: string) => void
  placeholder: string
  label: string
}) {
  return (
    <label className="flex h-14 items-center gap-3 rounded-full bg-surface-3 pr-2 pl-5 focus-within:ring-2 focus-within:ring-primary">
      <Search className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
      <input
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        aria-label={label}
        autoComplete="off"
        className="h-full min-w-0 flex-1 bg-transparent text-base outline-none placeholder:text-muted-foreground"
      />
      {value !== '' && (
        <Button type="button" variant="ghost" size="icon-sm" aria-label="Clear search" onClick={() => onChange('')}>
          <X />
        </Button>
      )}
    </label>
  )
}

/** The answer: the latest match, said in a sentence that names it. */
function AnswerCard({ query, top, others, today }: { query: string; top: Purchase; others: number; today: number }) {
  return (
    <div role="status" className="rounded-sheet bg-primary-container p-4 text-primary-container-foreground">
      <p className="text-[0.8125rem] opacity-80">Latest match for &ldquo;{query}&rdquo;</p>
      <p className="mt-1 text-[1.375rem] leading-snug">{answerSentence(top, today)}</p>
      <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1.5 text-[0.9375rem]">
        <SourceChip source={top.source} />
        {top.store && <span>{top.store}</span>}
        {top.price_cents != null && <PriceText cents={top.price_cents} />}
        {top.visibility === 'private' && <PrivateNote className="text-primary-container-foreground" />}
      </div>
      {others > 0 && (
        <p className="mt-3 text-[0.9375rem]">
          {others} more {others === 1 ? 'entry matches' : 'entries match'}, newest first. Each one is named as it
          was logged.
        </p>
      )}
    </div>
  )
}

function NoRecord({ query, logLink }: { query: string; logLink: ReactNode }) {
  return (
    <div role="status" className="flex flex-col items-start gap-3 rounded-sheet bg-card p-4">
      <p className="text-[1.375rem] leading-snug">No record of buying {query}.</p>
      <p className="text-[0.9375rem] text-muted-foreground">
        That only means nobody logged it. It may still have been bought.
      </p>
      {logLink}
    </div>
  )
}

function TruncatedNote({ list }: { list: PurchaseList }) {
  if (!list.truncated) return null
  return (
    <p className="px-1 text-[0.8125rem] text-muted-foreground">
      More entries matched than are shown here. These are the newest.
    </p>
  )
}

/* ---------------------------------------------------------------- phone -- */

export function FamilyBought({ initialQuery = '' }: { initialQuery?: string }) {
  const [text, setText] = useState(initialQuery)
  const query = useDebounced(text.trim(), 250)
  const searching = query !== ''
  const purchases = usePurchases({ q: query, limit: searching ? undefined : 30 })
  const today = todayEpochDay()

  let body: ReactNode
  if (purchases.isPending) {
    body = <ListSkeleton />
  } else if (purchases.data === undefined) {
    body = <ErrorSentence>{CANT_READ_LOG}</ErrorSentence>
  } else {
    const list = purchases.data
    const results = list.results
    const logLink = (
      <Button asChild size="lg">
        <Link to="/bought/log" search={{ item: query }}>
          <Plus />
          Log {query}
        </Link>
      </Button>
    )
    if (searching && results.length === 0) {
      body = <NoRecord query={query} logLink={logLink} />
    } else if (searching) {
      const [top, ...others] = results
      body = (
        <>
          <AnswerCard query={query} top={top} others={others.length} today={today} />
          {others.length > 0 && (
            <ul className="flex flex-col gap-2" aria-label={`Other entries matching ${query}`}>
              {others.map((entry) => (
                <EntryRow key={entry.id} entry={entry} today={today} />
              ))}
            </ul>
          )}
          <TruncatedNote list={list} />
        </>
      )
    } else if (results.length === 0) {
      body = <EmptySentence>Nothing has been logged as bought yet. Log the first one above.</EmptySentence>
    } else {
      body = (
        <section aria-labelledby="bought-recent" className="flex flex-col gap-2">
          <h2 id="bought-recent" className="px-1 pt-1 text-[0.8125rem] font-medium text-muted-foreground">
            Recently bought
          </h2>
          <ul className="flex flex-col gap-2">
            {results.map((entry) => (
              <EntryRow key={entry.id} entry={entry} today={today} />
            ))}
          </ul>
          <TruncatedNote list={list} />
        </section>
      )
    }
  }

  const stale = purchases.isError && purchases.data !== undefined

  return (
    <div className="flex flex-col gap-3 pb-16">
      <header className="pt-1">
        <h1 className="text-[1.75rem] leading-tight tracking-tight">Bought</h1>
      </header>
      <SearchField
        value={text}
        onChange={setText}
        placeholder="When did we last buy...?"
        label="When did we last buy"
      />
      {!searching && (
        <Button asChild variant="secondary" size="lg" className="w-full">
          <Link to="/bought/log">
            <Pencil />
            Log something by hand
          </Link>
        </Button>
      )}
      {stale && (
        <p role="status" className="px-1 text-[0.8125rem] text-muted-foreground">
          Could not refresh the bought log just now. This is the last answer it gave.
        </p>
      )}
      {body}
    </div>
  )
}

/* ----------------------------------------------------------------- desk -- */

const FILTERS = [
  { key: 'all', label: 'All', source: undefined },
  { key: 'tick', label: 'Groceries ticks', source: 'GROCERIES_TICK' },
  { key: 'hand', label: 'Logged by hand', source: 'MANUAL' },
  { key: 'old', label: 'Old Groceries ticks', source: 'GROCERIES_BACKFILL' },
] as const

type FilterKey = (typeof FILTERS)[number]['key']

function EntriesTable({
  entries,
  today,
  onEdit,
}: {
  entries: Purchase[]
  today: number
  onEdit: (entry: Purchase) => void
}) {
  const [openId, setOpenId] = useState<string | null>(null)
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Bought on</TableHead>
          <TableHead>Item, as logged</TableHead>
          <TableHead>Logged by</TableHead>
          <TableHead>Source</TableHead>
          <TableHead>Store, price</TableHead>
          <TableHead>Who sees it</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {entries.map((entry) => {
          const open = openId === entry.id
          return (
            <TableRow key={entry.id} className="align-top">
              <TableCell className="whitespace-nowrap">{dayLabel(entry.bought_on, today)}</TableCell>
              <TableCell>
                <button
                  type="button"
                  aria-expanded={open}
                  aria-label={`${entry.item}, bought ${dayLabel(entry.bought_on, today)}. ${open ? 'Hide' : 'Show'} details`}
                  className="text-left font-medium underline-offset-4 outline-none hover:underline focus-visible:outline-2 focus-visible:outline-primary"
                  onClick={() => setOpenId(open ? null : entry.id)}
                >
                  {entry.item}
                </button>
                {entry.quantity_note && (
                  <div className="text-[0.8125rem] font-normal text-muted-foreground">{entry.quantity_note}</div>
                )}
                {open && (
                  <div className="max-w-md">
                    <EntryDetails entry={entry} today={today} onEdit={() => onEdit(entry)} />
                  </div>
                )}
              </TableCell>
              <TableCell>
                {entry.logged_by ? (
                  entry.logged_by
                ) : (
                  <span className="text-muted-foreground">{whoWords(entry)}</span>
                )}
              </TableCell>
              <TableCell>
                <SourceChip source={entry.source} />
              </TableCell>
              <TableCell>
                {entry.store || entry.price_cents != null ? (
                  <>
                    {entry.store}
                    {entry.store && entry.price_cents != null && ', '}
                    {entry.price_cents != null && <PriceText cents={entry.price_cents} />}
                  </>
                ) : (
                  <span className="text-muted-foreground">-</span>
                )}
              </TableCell>
              <TableCell>
                <SeenBy entry={entry} />
              </TableCell>
            </TableRow>
          )
        })}
      </TableBody>
    </Table>
  )
}

export function WorkbenchBought({ initialQuery = '' }: { initialQuery?: string }) {
  const [text, setText] = useState(initialQuery)
  const [filter, setFilter] = useState<FilterKey>('all')
  const [editing, setEditing] = useState<Purchase | null>(null)
  // A new form starts from `prefill`; bumping `formKey` clears it after a save.
  const [prefill, setPrefill] = useState('')
  const [formKey, setFormKey] = useState(0)
  const [justLogged, setJustLogged] = useState<string | null>(null)
  const query = useDebounced(text.trim(), 250)
  const searching = query !== ''
  const source = FILTERS.find((candidate) => candidate.key === filter)?.source
  const purchases = usePurchases({ q: query, source })
  const today = todayEpochDay()

  let body: ReactNode
  if (purchases.isPending) {
    body = <ListSkeleton />
  } else if (purchases.data === undefined) {
    body = <ErrorSentence>{CANT_READ_LOG}</ErrorSentence>
  } else {
    const results = purchases.data.results
    if (searching && results.length === 0) {
      body = (
        <NoRecord
          query={query}
          logLink={
            <Button
              size="lg"
              onClick={() => {
                setEditing(null)
                setPrefill(query)
                setFormKey((key) => key + 1)
              }}
            >
              <Plus />
              Log {query} in the panel
            </Button>
          }
        />
      )
    } else if (results.length === 0) {
      body = (
        <EmptySentence>
          {filter === 'all'
            ? 'Nothing has been logged as bought yet.'
            : 'No bought entries come from that source yet.'}
        </EmptySentence>
      )
    } else {
      body = (
        <>
          {searching && (
            <AnswerCard query={query} top={results[0]} others={results.length - 1} today={today} />
          )}
          <EntriesTable entries={results} today={today} onEdit={setEditing} />
          <TruncatedNote list={purchases.data} />
        </>
      )
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <PageHeader
        title="Bought"
        subtitle="When did we last buy it? Ticks on the Groceries list, and whatever either of us logged by hand."
      />
      <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_24rem]">
        <div className="flex min-w-0 flex-col gap-4">
          <div className="max-w-xl">
            <SearchField
              value={text}
              onChange={setText}
              placeholder="Search what was bought"
              label="Search what was bought"
            />
          </div>
          <div role="group" aria-label="Filter by source" className="flex flex-wrap gap-2">
            {FILTERS.map((candidate) => (
              <Button
                key={candidate.key}
                type="button"
                size="sm"
                variant={filter === candidate.key ? 'secondary' : 'outline'}
                aria-pressed={filter === candidate.key}
                onClick={() => setFilter(candidate.key)}
              >
                {candidate.label}
              </Button>
            ))}
          </div>
          {body}
          <p className="text-[0.8125rem] text-muted-foreground">
            Prices are typed by hand. They are never checked against the bank and never counted in Money.
            Private entries are not shown to anyone but whoever logged them.
          </p>
        </div>
        <Panel
          title={editing ? 'Edit this entry' : 'Log something bought'}
          className="xl:sticky xl:top-4"
        >
          {justLogged && (
            <p role="status" className="mb-3 rounded-control bg-primary-container px-4 py-3 text-[0.9375rem] text-primary-container-foreground">
              {justLogged}
            </p>
          )}
          <PurchaseForm
            key={editing?.id ?? `new-${formKey}`}
            entry={editing ?? undefined}
            defaultItem={editing ? undefined : prefill}
            onSaved={(saved) => {
              if (editing) {
                setEditing(null)
                setJustLogged(null)
              } else {
                setJustLogged(saved ? `Logged: ${saved.item}, ${dayLabel(saved.bought_on, today)}.` : 'Logged.')
                setPrefill('')
                setFormKey((key) => key + 1)
              }
            }}
            onCancel={editing ? () => setEditing(null) : undefined}
          />
        </Panel>
      </div>
    </div>
  )
}

export function BoughtScreen({ q }: { q?: string }) {
  const surface = useSurface()
  return surface === 'workbench' ? <WorkbenchBought initialQuery={q} /> : <FamilyBought initialQuery={q} />
}
