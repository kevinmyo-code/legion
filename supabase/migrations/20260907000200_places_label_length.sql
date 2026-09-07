-- LEGION: a place label is a name, not a sentence - the 30-character cap gets a CHECK.
-- Depends on: 20260825000500_aspect_places_fleet.sql (which created public.places)
-- **UNAPPLIED.** Written by django-engine ticket 14 and deliberately not run: the ticket asked for
-- the belt to be written and left for Kevin, not applied under him. Nothing in the API depends on
-- it - `api/places.PlaceSerializer.validate_label` refuses the same write in words before the
-- round trip, and that is what ships working today. This file is the second line of defence for
-- the day a writer that is not this API reaches the table.
--
-- ## What rule this is, and where it used to live
--
-- `location/PlaceController.normalizeLabel` capped a label at 30 characters:
--
--     if (s.isBlank() || s.length > 30) return null
--
-- and that was the ONLY place the rule existed. `public.places` has `check (length(trim(label)) >
-- 0)` and no length bound at all, so a 200-character label was accepted by Postgres, by
-- PostgREST, and (until 2026-09-07) by the Django API. ADR 0044 makes the head unit a SEPARATE
-- Android app; a rule living in one client's Kotlin is a rule the other client does not have.
--
-- The cap is not a storage limit - `label` is `text`. A place label is minted by voice ("call this
-- the fancy walmart") and a misheard sentence arrives looking exactly like a label. Thirty
-- characters is the line between a name and a sentence. It is also this table's IDENTITY: `label`
-- is the URL segment on `PUT /api/places/<label>/`, `events.trigger_place_label` names a place by
-- this string, and the OS geofence layer uses it as the requestId. A key wants to be short.
--
-- ## Before applying this, check for rows it would refuse
--
-- The constraint is validated against existing rows on ADD, so a single overlong label already in
-- the table will make this statement fail (loudly, and with nothing half-applied - that is the
-- desired behaviour, not a hazard). Run this first:
--
--     select label, length(label) from public.places where length(label) > 30 order by 2 desc;
--
-- Expected to be empty: every row in this table was written through `PlaceController.tagPlace`,
-- which has enforced the cap since the function existed. If it is NOT empty, the rows came from
-- somewhere else and that is worth knowing before they are renamed.
--
-- ## Measured on the stored value, not a trimmed copy
--
-- `length(label)`, not `length(trim(label))`. Nothing in the write path strips, so the stored
-- string is what a reader gets and what the URL carries; a check measuring something other than
-- what it stores is not the same check. The existing blank guard uses `trim` because ITS question
-- is different - "is there anything here at all" - and the two coexist.

alter table public.places
    add constraint places_label_length check (length(label) <= 30);

comment on constraint places_label_length on public.places is
    'A place label is a name, not a sentence: 30 characters, mirrored by '
    'api/places.PlaceSerializer.LABEL_MAX_LENGTH. django-engine ticket 14.';
