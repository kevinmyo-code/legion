# Voice contract v1: shared test vectors

Every voice client will load these files in its own unit tests (none does yet), so the phone, the head unit and the
web make the same decisions. The rules are in `docs/contracts/voice-client-v1.md`; the wire is in
`docs/contracts/voice-wire-v1.md`. All content is made up.

| File | Decides | Client code under test |
|---|---|---|
| `eot.json` | when a turn ends, from Silero probabilities and Smart Turn verdicts | the end-of-turn policy |
| `backchannel.json` | whether a backchannel plays, the clip length and spacing rules | the backchannel rules |
| `owed.json` | which write line is owed after a turn that did not play whole | the engine's owed-line ledger |

Each file states its `contract_version`, its `constants`, the meaning of every op and expect key,
and, per case, the `source` test it was derived from.

## How each client loads them

- **The shared Android module (`voice/`)**: a test reads the files from the repo by a path relative
  to the module, `../server/contracts/voice/v1/<file>.json`, with `org.json`. The module does not
  exist yet; until it does, the phone's tests (`app/`) can read
  `../server/contracts/voice/v1/<file>.json` the same way. Gradle runs unit tests with the module
  directory as the working directory.
- **The web (vitest)**: from `server/frontend/src/assistant/turn/`, import the JSON with
  `import vectors from '../../../../contracts/voice/v1/eot.json'` (Vite reads JSON imports), or read it
  with `node:fs` from `path.resolve(__dirname, '../../../../contracts/voice/v1/eot.json')`.
- **The engine (pytest)**: `server/tests/test_voice_contract_vectors.py` checks only the shape of
  every file, so a malformed vector fails CI. `owed.json` becomes a behaviour test when the
  engine's ledger is built.

## Rules for changing a vector

- A vector states what code that passes today does. A new case comes from a test that already
  passes, or lands in the same change as the code that makes it pass in every client.
  An assertion its source test does not make says so in `source`, and was checked by running the
  code it describes.
- A case with `"status": "rule_without_code"` states a contract rule no code implements yet;
  it must pass in the change that builds that code.
- A case with `clients` applies only to those clients (`phone`, `headunit`, `web`).
- `server/tests/test_voice_contract_vectors.py` also checks `eot.json`'s constants against the web's
  `eot-policy.ts`.
- A changed constant or expectation is a contract change: say so in
  `docs/contracts/voice-client-v1.md` and bump `contract_version` when old clients would disagree.
