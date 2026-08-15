# What is "the inbox that matters", and how does Kevin ask for mail?

Type: grilling
Status: resolved
Blocked by: -

## Question

Gmail is a pull tool: Alfred answers when asked, never raises. That leaves two things to decide.

**Facts from [ticket 03](03-gmail-scope-floor.md), resolved 2026-08-13.** The scope is
`gmail.readonly`, so `q` search is available and snippets are readable. Google's own `CATEGORY_*`
`labelIds` come back free on every message and do a lot of the selection work below - use them
before inventing a rule. Quota is a non-constraint (~405 units per briefing against 6,000/min), so
argue the cap on what Alfred can usefully speak, not on cost. One open spike: whether `snippet` is
populated under `format=METADATA` is undocumented - moot while `gmail.readonly` stands.

**Briefing.** "What's in my inbox" has to return something short enough to speak aloud.
- What is the selection rule? Unread only, last N, a Gmail `q` query, starred/important, a
  sender list Kevin curates?
- Does the app decide what matters, or does it hand the model a list and let Gemini decide? The
  second is a token cost on every call and a §4-adjacent trust question - a model choosing what to
  omit is a model deciding what Kevin does not hear about.
- How many messages, and is the cap a hard one?
- Sender + subject + date only, or snippets? (Ticket 03 may make this a scope question, not a
  taste one.)

**Search.** "Find the email from the workshop about the timing belt."
- Does Alfred pass natural language straight through to Gmail's `q`, or does the app build the
  query? Gmail's search syntax is good; the model translating badly into it is a silent-wrong-answer
  path.
- What comes back - a count, the top hit, a spoken list? What does "nothing found" say?
- Can Kevin then ask for the body of one result, and does that cross a scope line?

**Tool budget.** LEGION runs 69 tools and every one is prompt tokens on every live session, on
Kevin's own key. The notes domain landed a whole domain while the tool count went **down**. Decide
how many tools this is - ideally one or two - and write the tool descriptions here, because a
description is the only thing the model ever reads.

## Answer

**Two tools. The app owns the briefing query; the model owns the search query and Alfred always says
which query he ran.**

Resolved 2026-08-13 on the orchestrator's recommendation, delegated by Kevin.

### Briefing

- **The app decides, not the model.** A fixed Gmail query, not a pile of messages handed to Gemini
  to filter. A model choosing what to omit is a model deciding what Kevin never hears about, and it
  costs tokens on every call to do a worse job than Gmail's own index.
- **The query:** `is:unread in:inbox category:primary newer_than:2d`. Google's `CATEGORY_*` labels
  already separate promotions and social from mail a person actually sent, at no cost - ticket 03
  flagged this and it does most of the work here for free. Two days, not one, so Monday morning
  still covers the weekend.
- **Cap 10, hard.** Not a cost limit - ticket 03 found quota is a non-constraint at ~405 units a
  briefing against 6,000/min. It is a *spoken* limit: past ten, Alfred is reading a list nobody is
  holding in their head. Over the cap he says the total and reads the first ten.
- **Returned per message:** sender name, subject, relative date ("this morning", "yesterday"), and
  the `snippet`. No body. Ticket 07 governs what that means for Gemini.
- **Empty is a real answer**, said plainly: "Nothing unread in the last two days."

### Search

- **Natural language goes to Gmail's `q` as the model writes it.** Gmail's parser treats bare words
  as full-text and understands `from:`, `subject:`, `after:` and the rest; the model is good at that
  syntax and the app second-guessing it would be a worse parser wrapped around a better one.
- **The guardrail is disclosure, not restriction: Alfred always says the query he ran.** This is the
  notes domain's own rule ("Alfred always says which list he used",
  `notes-lists-calendar` ticket 05) pointed at search. A bad translation becomes visible immediately
  instead of returning a confident wrong answer, which is the failure this repo keeps hitting.
- **Returns** the same per-message shape as the briefing, capped at 5 - a search is a lookup, not a
  survey. Zero hits says so and repeats the query.
- **Reading one message in full is the second tool**, called with an id from a previous result.

### The two tools

| Tool | Description the model reads |
|---|---|
| `search_mail(query, limit)` | *"Search Kevin's Gmail. `query` uses Gmail search syntax; plain words search full text. Returns sender, subject, date and a one-line snippet - never the full message. Call with no query for a briefing of unread mail from the last two days. Read-only; you cannot send, reply to, or delete mail."* |
| `read_mail(id)` | *"Fetch the full text of ONE message by the id returned from `search_mail`. Only call this when Kevin asks about a specific message; say that you are opening it."* |

Briefing is `search_mail` with the default query, so it costs no third tool. **Net +2 against a
budget of 69**, and the notes domain's precedent (a whole domain landed while the count went *down*)
is not matched here - accepted, because there is no existing tool to retire.

### Not adopted

`gmail.metadata`-only briefing, and with it the undocumented `snippet`-under-`METADATA` spike ticket
03 raised. Moot: `gmail.readonly` is the scope, so snippets are available and the spike is dead.
