import { places, type Place } from '@/api/aspects'
import { wire } from '@/api/synced'
import { CrudPanel } from '@/components/workbench/crud-panel'
import { PageHeader } from '@/components/workbench/page'
import type { Values } from '@/components/workbench/record-form'

/**
 * `/places`: the tagged places the household's reminders can name.
 *
 * **A place's name is its key.** The engine identifies a place by its `label`
 * (the table has no other identity column), and a reminder names a place by that
 * same string, so renaming one would orphan everything that points at it. The
 * edit form therefore shows the name but does not let it change; to use another
 * name, add a new place. That is the engine's shape, said in words on the form
 * rather than hidden by a disabled field with no reason.
 *
 * The ticket and the spec both mention fixing a radius. The engine's place has a
 * name and a latitude and longitude and no radius, so there is nothing to edit:
 * no field is invented for it.
 */

export function PlacesScreen() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Places"
        subtitle="The tagged places reminders can name. Coordinates are decimal degrees: north and east are positive."
      />
      <CrudPanel
        table={places}
        title="Tagged places"
        addLabel="Add a place"
        what="places"
        empty="No places yet. Tag one from the phone, or add one here with its coordinates."
        sort={(a, b) => a.label.localeCompare(b.label)}
        columns={[
          { header: 'Name', cell: (row) => <span className="font-medium">{row.label}</span> },
          { header: 'Latitude', align: 'right', cell: (row) => row.latitude.toFixed(5) },
          { header: 'Longitude', align: 'right', cell: (row) => row.longitude.toFixed(5) },
        ]}
        fields={(row) => [
          {
            name: 'label',
            label: 'Name',
            kind: 'text',
            required: true,
            readOnly: row !== null,
            placeholder: 'Home',
            hint: row
              ? "A place's name is its key and cannot be changed here. Add a new place to use another name."
              : undefined,
          },
          { name: 'latitude', label: 'Latitude', kind: 'number', required: true, step: 'any', min: '-90', max: '90', placeholder: '29.76040' },
          { name: 'longitude', label: 'Longitude', kind: 'number', required: true, step: 'any', min: '-180', max: '180', placeholder: '-95.36980' },
        ]}
        identity={(row) => row.label}
        newIdentity={(values) => values.label.trim()}
        initial={(row): Values => ({
          label: row?.label ?? '',
          latitude: row ? String(row.latitude) : '',
          longitude: row ? String(row.longitude) : '',
        })}
        toBody={(values, row) =>
          wire<Place>({
            label: row?.label ?? values.label.trim(),
            latitude: Number(values.latitude),
            longitude: Number(values.longitude),
          })
        }
        rowLabel={(row) => `"${row.label}"`}
        deleteConsequence="This place is removed. A reminder that names it by label loses its place."
      />
    </div>
  )
}
