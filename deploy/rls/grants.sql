-- Privileges for `legion_app`, the role every request runs as under row-level security
-- (server/household/rls.py). Idempotent: run it again after any migration that adds a table,
-- or rely on the default privileges at the bottom.
--
-- RLS filters rows; it does not grant access to a table in the first place. legion_app gets
-- ordinary DML on every table in the engine's two schemas, and the policies then narrow the
-- tenant tables to one household. It gets no DDL, owns nothing, and has no BYPASSRLS.
--
-- Run as the role that owns the tables (the one in DATABASE_URL). The test suite runs this same
-- file (server/tests/conftest.py), so what is tested is what is granted.

do $$
declare
    s text;
begin
    foreach s in array array['django', 'public'] loop
        if exists (select from pg_namespace where nspname = s) then
            execute format('grant usage on schema %I to legion_app', s);
            execute format(
                'grant select, insert, update, delete on all tables in schema %I to legion_app', s);
            execute format(
                'grant usage, select, update on all sequences in schema %I to legion_app', s);
            execute format('grant execute on all functions in schema %I to legion_app', s);
            -- Tables a later migration creates (as this same owner) get the same grants.
            execute format(
                'alter default privileges in schema %I grant select, insert, update, delete '
                'on tables to legion_app', s);
            execute format(
                'alter default privileges in schema %I grant usage, select, update '
                'on sequences to legion_app', s);
        end if;
    end loop;
    -- Trigger functions that still live in `private` run as the caller, so the caller needs to
    -- reach them. Only if the schema exists (Supabase-era databases and the test database).
    if exists (select from pg_namespace where nspname = 'private') then
        execute 'grant usage on schema private to legion_app';
        execute 'grant execute on all functions in schema private to legion_app';
    end if;
end $$;
