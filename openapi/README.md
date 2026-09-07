# The engine contract

`legion-schema.yaml` is the LEGION Django engine's own OpenAPI schema. It is the **contract every
limb generates its models from** - this phone, the head unit (the `MIDNIGHT_AI` repo), and the PWA
when it lands. ADR 0044 made Django the engine and every other thing a client of it; this file is
what "a client of it" means concretely.

## Where it comes from

```
cd server
python manage.py spectacular --file ../openapi/legion-schema.yaml
```

Or, preferred, because it also checks:

```
python tools/schema_check.py           # fails if the vendored copy has drifted
python tools/schema_check.py --write   # regenerate it
```

## Why it is committed rather than fetched

A build that reaches the network to learn its own types is not clone-and-run, and clone-and-run is
a hard requirement (CLAUDE.md section 2). Committing it also means the contract is diffable: a
reviewer sees a field change as a line in a pull request rather than as behaviour that shifted
under a limb at runtime.

The cost of committing it is that it can go stale, and **a stale schema is worse than no schema**:
the generated models still compile, so a field the server renamed simply arrives as `null` forever.
Nothing crashes and nothing is logged. `tools/schema_check.py` exists to turn that into a hard
error, the same posture `tools/docs_check.py` and `tools/voice_guide.py` already take.

## What is generated from it, and what is not

**Generated: models only.** 35 `@Serializable` data classes, into `build/generated/openapi/`, never
committed - the committed artifact is the schema, so a reviewer diffs the contract and not 35 files
of generated Kotlin.

**Not generated: the transport.** `backend/engine/EngineHttp.kt` classifies every non-2xx into four
`EngineFailure` branches, none of which a caller can mistake for a success. That is CLAUDE.md
section 7's outcome-verb rule expressed as a type rather than as a convention someone must
remember. openapi-generator's own `ApiClient` throws untyped exceptions and would lose exactly that
guarantee, so codegen stops at the models.

## The generator configuration, and the one non-obvious setting

See `app/build.gradle.kts`'s `openApiGenerate` block. The setting worth knowing about is:

```
typeMappings = mapOf("java.util.UUID" to "kotlin.String")
```

Without it the generator emits `java.util.UUID` behind kotlinx's `@Contextual`, which requires a
serializers module registered at every call site and throws **at runtime** where one is missing.
Verified by round-tripping a real payload, not by reading the generator's documentation.

## Keeping the head unit in step

The `MIDNIGHT_AI` repo vendors its own copy of this file at `openapi/legion-schema.yaml`. There is
no shared module between the two repos and deliberately so - they are separate apps on separate
release cadences. **When this file changes, the head unit's copy must be re-copied and its codegen
re-run**, or the two limbs are talking to two different contracts. The checksum in that repo's
`openapi/README.md` is what makes the skew visible to a human.

Current: `info.version` 0.1.0.
