import type { BarsCard, CarCard, ChecklistCard, DiagramCard, StepsCard, TimelineCard } from '@/components/assistant/cards/types'

/** Cards in the engine's shape (`server/engine_mcp/cards.py`), for tests and screenshots. */

export const SPEND_CARD: BarsCard = {
  card: 'bars',
  title: 'Spending by category',
  period: 'October 2026',
  unit: 'usd',
  points: [
    { label: 'Housing', value: 172_500 },
    { label: 'Groceries', value: 41_236, flag: 'unverified' },
    { label: 'Dining', value: 18_840 },
    { label: 'Transport', value: 9_105 },
  ],
  more: 2,
  note: 'Total $2,516.81. Includes $12.40 unverified.',
}

export const DAY_CARD: TimelineCard = {
  card: 'timeline',
  title: "What's on",
  date: '2026-11-04',
  zone: 'UTC-06:00',
  items: [
    { start: null, end: null, all_day: true, title: 'Bin day', kind: 'event' },
    { start: '09:00', end: null, all_day: false, title: 'Farmers market', kind: 'suggestion' },
    { start: '15:30', end: '16:15', all_day: false, title: 'Dentist', kind: 'event' },
    { start: '18:00', end: null, all_day: false, title: 'Call the plumber', kind: 'todo' },
  ],
  more: 3,
  note: 'Suggestions are ideas, not plans.',
}

export const CAR_CARD: CarCard = {
  card: 'car',
  title: 'Cherokee',
  name: 'Cherokee',
  mileage: 120_400,
  mileage_as_of: '2026-10-30',
  next_service: { label: 'Oil change', due_date: '2026-12-01', due_miles: 121_000, estimate: true },
  recalls: [{ summary: 'Air bags: The passenger air bag may not deploy.' }],
  codes: [{ code: 'P0420', meaning: 'Catalyst System Efficiency Below Threshold (Bank 1)' }],
  note: "Recalls are NHTSA's list for the model, not a check of this car's VIN. Next service is an estimate from the records on file.",
}

export const STEPS_CARD: StepsCard = {
  card: 'steps',
  title: 'Money check',
  name: 'Money check',
  items: [
    { label: 'Spend', status: 'ok', ms: 42 },
    { label: 'Groceries', status: 'failed', ms: 7 },
    { label: 'Answer', status: 'skipped', ms: 0 },
  ],
  total_ms: 49,
  note: 'Stopped part way.',
}

export const LIST_CARD: ChecklistCard = {
  card: 'checklist',
  title: 'Groceries',
  period: '2026-11-04',
  list_name: 'Groceries',
  items: [
    { text: 'Milk', done: true },
    { text: 'Eggs', done: false },
  ],
  more: 0,
  note: '1 other list not shown.',
}

/** "What's on this week?": items carry their day. */
export const WEEK_CARD: TimelineCard = {
  card: 'timeline',
  title: "What's on",
  period: 'Wed Nov 4 to Wed Nov 11',
  date: '2026-11-04',
  zone: 'UTC-05:00',
  items: [
    { date: '2026-11-04', start: null, end: null, all_day: true, title: 'Library books due', kind: 'todo' },
    { date: '2026-11-04', start: '19:00', end: '21:00', all_day: false, title: 'Dinner with the Okafors', kind: 'event' },
    { date: '2026-11-05', start: '17:30', end: null, all_day: false, title: 'Pick up dry cleaning', kind: 'event' },
    { date: '2026-11-07', start: '16:00', end: '16:30', all_day: false, title: 'Parent-teacher conference', kind: 'event' },
  ],
  more: 0,
}

/** next_service alone: the service part only. */
export const SERVICE_HALF: CarCard = {
  card: 'car',
  title: 'Wagon',
  name: 'Wagon',
  mileage: 48_210,
  mileage_as_of: '2026-10-28',
  next_service: { label: 'Oil change', due_miles: 50_000, estimate: true },
  recalls: [],
  codes: [],
  note: 'Next service is an estimate from the records on file.',
  parts: { service: 'Next service is an estimate from the records on file.' },
}

/** vehicle_recalls alone: the recalls part only. */
export const RECALLS_HALF: CarCard = {
  card: 'car',
  title: 'Wagon',
  name: 'Wagon',
  mileage: null,
  mileage_as_of: null,
  next_service: null,
  recalls: [{ summary: 'Air bags: The passenger air bag may not deploy.' }],
  codes: [],
  note: "Recalls are NHTSA's list for the model, not a check of this car's VIN.",
  parts: { recalls: "Recalls are NHTSA's list for the model, not a check of this car's VIN." },
}

/** A diagram card in the engine's shape (`diagrams.catalogue.diagram_card`), a tiny SVG. */
export const DIAGRAM_CARD: DiagramCard = {
  card: 'diagram',
  title: 'Engine components, in layers',
  scope: 'LEGION',
  project: 'legion',
  view: 'components',
  caption: 'Every client enters through the API layer and narrows to its household before Postgres.',
  highlight: ['tooldoor', 'household'],
  nodes: [
    { id: 'tooldoor', label: '/api/assistant/tool' },
    { id: 'household', label: 'household.tenancy' },
    { id: 'rest', label: 'REST /api/...' },
  ],
  svg:
    '<svg id="dg-legion-components" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 100">' +
    '<title>Engine components</title><desc>Layers</desc>' +
    '<g class="node default" data-node="tooldoor"><rect width="80" height="30"/><text>/api/assistant/tool</text></g>' +
    '<g class="node default" data-node="household"><rect x="100" width="80" height="30"/><text>household.tenancy</text></g>' +
    '<g class="node default" data-node="rest" onclick="alert(1)"><rect x="200" width="80" height="30"/><text>REST</text></g>' +
    '<script>window.pwned = true</script>' +
    '</svg>',
}
