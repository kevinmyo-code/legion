import { FlagWord } from './parts'
import { dayWords, hasPart, type CarCard } from './types'

/**
 * The car at a glance: mileage on file, next service (an estimate), codes,
 * recalls. Only the parts the card's call read are drawn: a card from
 * next_service alone shows no recall row rather than an empty one.
 */
export function CarView({ card }: { card: CarCard }) {
  const service = card.next_service
  const due = service
    ? [
        service.due_date ? `due ${dayWords(service.due_date)}` : null,
        service.due_miles != null ? `at ${service.due_miles.toLocaleString('en-US')} mi` : null,
      ]
        .filter(Boolean)
        .join(', ')
    : ''
  return (
    <dl data-card="car" className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1.5">
      {hasPart(card, 'service') && (
        <>
          <dt className="text-muted-foreground">Mileage</dt>
          <dd data-card="value">
            {card.mileage != null
              ? `${card.mileage.toLocaleString('en-US')} mi${card.mileage_as_of ? `, on file ${dayWords(card.mileage_as_of)}` : ''}`
              : 'No reading on file'}
          </dd>
          <dt className="text-muted-foreground">Next service</dt>
          <dd data-card="value">
            {service ? (
              <>
                {service.label}
                {due && `, ${due}`}
                {service.estimate && <FlagWord>estimate</FlagWord>}
              </>
            ) : (
              'Nothing timed from the records on file'
            )}
          </dd>
        </>
      )}
      {hasPart(card, 'codes') && (
        <>
          <dt className="text-muted-foreground">Codes</dt>
          <dd data-card="value">
            {card.codes.length === 0 ? (
              // The note says when codes were not read.
              'None listed'
            ) : (
              <ul className="flex flex-col gap-0.5">
                {card.codes.map((code) => (
                  <li key={code.code}>
                    <span className="font-medium tabular-nums">{code.code}</span>
                    {code.meaning && <span className="text-muted-foreground"> {code.meaning}</span>}
                  </li>
                ))}
              </ul>
            )}
          </dd>
        </>
      )}
      {hasPart(card, 'recalls') && (
        <>
          <dt className="text-muted-foreground">Recalls</dt>
          <dd data-card="value">
            {card.recalls.length === 0 ? (
              // The note says when the lookup did not run; an empty list alone is not "none".
              'None listed'
            ) : (
              <ul className="flex flex-col gap-0.5">
                {card.recalls.map((recall, index) => (
                  <li key={index}>{recall.summary}</li>
                ))}
              </ul>
            )}
          </dd>
        </>
      )}
    </dl>
  )
}
