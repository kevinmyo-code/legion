import { useQuery } from '@tanstack/react-query'
import { BookOpen, HeartPulse, MapPin, Mic, ShoppingBasket, type LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'

import { mealLogs, places, receiptLineItems, receipts, sleepLogs, voiceNotes } from '@/api/aspects'
import { listPositions } from '@/api/books'
import { useRows } from '@/api/synced'
import { BriefingSection, RowIcon, TrustChip } from '@/components/briefing/parts'
import { money } from '@/lib/briefing'
import { formatMinutes, formatNumber, localDay, parseDay, plural, todayDay } from '@/lib/figures'

/**
 * Section 5: one line per corner of the household, each from its own table and
 * each saying so. A corner that could not be read says that in its own words;
 * one with nothing in it says that instead. Body is members only, and Books
 * live on a member's laptop, so neither is read on a demo visit (ticket 13).
 */

type Tone = 'primary' | 'done' | 'shared' | 'unverified'

function HouseRow({
  icon,
  tone = 'primary',
  label,
  title,
  detail,
  trailing,
}: {
  icon: LucideIcon
  tone?: Tone
  label: string
  title: ReactNode
  detail?: ReactNode
  trailing?: ReactNode
}) {
  return (
    <li aria-label={label} className="flex min-h-[3.25rem] items-center gap-3 py-2 not-first:border-t not-first:border-outline-variant">
      <RowIcon icon={icon} tone={tone} />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[0.9375rem]">{title}</div>
        {detail && <div className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-[0.8125rem] text-muted-foreground">{detail}</div>}
      </div>
      {trailing}
    </li>
  )
}

function daysBefore(day: string, days: number): string {
  const date = parseDay(day)
  date.setDate(date.getDate() - days)
  return localDay(date)
}

function sum(values: (string | null)[]): number | null {
  const numbers = values.map((value) => (value === null ? NaN : Number(value))).filter(Number.isFinite)
  return numbers.length === 0 ? null : numbers.reduce((a, b) => a + b, 0)
}

function Groceries() {
  const rows = useRows(receipts)
  const lines = useRows(receiptLineItems)
  const label = 'Groceries'
  if (rows.isPending || lines.isPending) return <HouseRow icon={ShoppingBasket} tone="done" label={label} title="Groceries: still reading the receipts." />
  if (!rows.data || !lines.data) {
    return <HouseRow icon={ShoppingBasket} tone="done" label={label} title="Could not reach the engine, so this week's groceries are not known." />
  }
  const since = daysBefore(todayDay(), 6)
  const week = rows.data.filter((receipt) => receipt.purchase_date >= since)
  if (week.length === 0) {
    return <HouseRow icon={ShoppingBasket} tone="done" label={label} title="No grocery receipt in the last 7 days." />
  }
  const total = week.reduce((acc, receipt) => acc + receipt.total_cents, 0)
  const unverified = week.some((receipt) => receipt.provenance === 'UNRECONCILED')
  const ids = new Set(week.map((receipt) => receipt.id))
  const weekLines = lines.data.filter((line) => ids.has(line.receipt_id))
  const kcal = sum(weekLines.map((line) => line.estimated_calories_kcal))
  const protein = sum(weekLines.map((line) => line.estimated_protein_g))
  return (
    <HouseRow
      icon={ShoppingBasket}
      tone="done"
      label={label}
      title={
        <>
          <span>
            Groceries this week: {money(total, week[0].currency)} from {plural(week.length, 'receipt')}
          </span>
          <TrustChip word={unverified ? 'Unverified' : 'Verified'} />
        </>
      }
      detail={
        kcal === null ? (
          <span>No macro estimate on these receipts.</span>
        ) : (
          <>
            <span>
              About {formatNumber(kcal, 0)} kcal{protein !== null ? ` and ${formatNumber(protein, 0)} g protein` : ''} in
              what was bought, worked out from the receipt items
            </span>
            <TrustChip word="Estimate" />
          </>
        )
      }
    />
  )
}

function seconds(start: string, end: string | null | undefined): string | null {
  if (!end) return null
  const total = Math.max(0, Math.round((new Date(end).getTime() - new Date(start).getTime()) / 1000))
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

function VoiceNotes() {
  const notes = useRows(voiceNotes)
  const label = 'Voice notes'
  if (notes.isPending) return <HouseRow icon={Mic} tone="shared" label={label} title="Voice notes: still reading." />
  if (!notes.data) return <HouseRow icon={Mic} tone="shared" label={label} title="Could not reach the engine, so the voice notes are not known." />
  const since = daysBefore(todayDay(), 6)
  const week = notes.data
    .filter((note) => localDay(note.started_at) >= since)
    .sort((a, b) => b.started_at.localeCompare(a.started_at))
  if (week.length === 0) return <HouseRow icon={Mic} tone="shared" label={label} title="No voice notes in the last 7 days." />
  return (
    <HouseRow
      icon={Mic}
      tone="shared"
      label={label}
      title={`${plural(week.length, 'voice note')} in the last 7 days`}
      detail={
        <span>
          {week
            .slice(0, 2)
            .map((note) => {
              const length = seconds(note.started_at, note.ended_at)
              return `"${note.title?.trim() || 'Untitled'}"${length ? ` ${length}` : ''}`
            })
            .join(', ')}
        </span>
      }
    />
  )
}

function Places() {
  const rows = useRows(places)
  const label = 'Places'
  if (rows.isPending) return <HouseRow icon={MapPin} label={label} title="Places: still reading." />
  if (!rows.data) return <HouseRow icon={MapPin} label={label} title="Could not reach the engine, so the saved places are not known." />
  if (rows.data.length === 0) return <HouseRow icon={MapPin} label={label} title="No places saved yet." />
  const newest = [...rows.data].sort((a, b) => b.created_at.localeCompare(a.created_at))[0]
  const added = new Date(newest.created_at).toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' })
  return (
    <HouseRow
      icon={MapPin}
      label={label}
      title={`${newest.label} is the newest place saved`}
      detail={<span>Added {added}, {plural(rows.data.length, 'place')} in all</span>}
    />
  )
}

function Books() {
  const positions = useQuery({ queryKey: ['books', 'positions', 'home'], queryFn: () => listPositions(), retry: false })
  const label = 'Books'
  if (positions.isPending) return <HouseRow icon={BookOpen} tone="unverified" label={label} title="Books: still reading." />
  if (!positions.data) return <HouseRow icon={BookOpen} tone="unverified" label={label} title="Could not reach the server, so reading progress is not known." />
  if (positions.data.length === 0) return <HouseRow icon={BookOpen} tone="unverified" label={label} title="No book has been opened in the reader yet." />
  const newest = [...positions.data].sort((a, b) => b.recorded_at.localeCompare(a.recorded_at))[0]
  const percent = Math.round(Math.min(1, Math.max(0, newest.total_progression)) * 100)
  return (
    <HouseRow
      icon={BookOpen}
      tone="unverified"
      label={label}
      title={`The book open last is ${percent} percent read`}
      detail={newest.chapter_label ? <span>At "{newest.chapter_label}"</span> : undefined}
      trailing={
        <span
          role="meter"
          aria-label="Progress through the book"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={percent}
          aria-valuetext={`${percent} percent`}
          className="hidden h-1.5 w-20 overflow-hidden rounded-full bg-surface-3 sm:block"
        >
          <span className="block h-full rounded-full bg-primary" style={{ width: `${percent}%` }} />
        </span>
      }
    />
  )
}

function Body() {
  const sleep = useRows(sleepLogs)
  const meals = useRows(mealLogs)
  const label = 'Body'
  if (sleep.isPending || meals.isPending) return <HouseRow icon={HeartPulse} tone="shared" label={label} title="Body: still reading." />
  if (!sleep.data || !meals.data) return <HouseRow icon={HeartPulse} tone="shared" label={label} title="Could not reach the engine, so sleep and meals are not known." />
  const today = todayDay()
  const yesterday = daysBefore(today, 1)
  const night = sleep.data.filter((row) => row.sleep_date === today || row.sleep_date === yesterday).sort((a, b) => b.sleep_date.localeCompare(a.sleep_date))[0]
  const eaten = meals.data.filter((row) => localDay(row.logged_at) === yesterday).length
  const mealWords = eaten === 0 ? 'No meals logged yesterday.' : `${plural(eaten, 'meal')} logged yesterday.`
  return (
    <HouseRow
      icon={HeartPulse}
      tone="shared"
      label={label}
      title={
        night ? (
          <>
            <span>Slept {formatMinutes(night.duration_minutes)} last night</span>
            {night.trust_tier === 'PROVEN' ? <TrustChip word="Verified" /> : <span className="text-[0.8125rem] text-muted-foreground">as logged</span>}
          </>
        ) : (
          'No sleep logged for last night.'
        )
      }
      detail={<span>{mealWords}</span>}
    />
  )
}

export function AroundHouseSection({ recruiter }: { recruiter: boolean }) {
  return (
    <BriefingSection n={5} id="briefing-house" title="Around the house">
      <div className="rounded-sheet bg-card px-4 py-1.5 md:px-5">
        <ul aria-label="Around the house">
          <Groceries />
          <VoiceNotes />
          <Places />
          {!recruiter && <Books />}
          {!recruiter && <Body />}
        </ul>
      </div>
    </BriefingSection>
  )
}

