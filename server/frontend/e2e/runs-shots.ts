import type { Shot } from './shots.spec'
import { createEngine, seedHousehold } from '../src/test/engine'
import { RUN_MODEL, RUN_PARTIAL, seedProcedures } from '../src/test/engine-procedures'

/**
 * Procedures ticket 14: the run inspector and the branch report, label `procedures-14`.
 *
 *     SHOTS_LABEL=procedures-14 npm run shots
 */

const LABELS = ['procedures-14']

const withRuns = () => createEngine({ ...seedHousehold(), householdName: 'The Test House', procedures: seedProcedures() })

export const runsShots: Shot[] = [
  { name: 'runs', url: '/runs', ready: 'Branch reports', engine: withRuns, labels: LABELS },
  {
    name: 'runs-empty',
    url: '/runs',
    ready: /No procedure has run here/,
    engine: () => createEngine({ ...seedHousehold(), householdName: 'The Test House' }),
    labels: LABELS,
  },
  { name: 'run-partial', url: `/runs/${RUN_PARTIAL}`, ready: 'Steps, in order', engine: withRuns, labels: LABELS },
  { name: 'run-model', url: `/runs/${RUN_MODEL}`, ready: 'A model chose', engine: withRuns, labels: LABELS },
  { name: 'runs-report', url: '/runs/report/car_check?version=1', ready: 'Branch report: car_check v1', engine: withRuns, labels: LABELS },
]
