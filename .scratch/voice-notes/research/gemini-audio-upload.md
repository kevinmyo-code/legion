---
map: voice-notes
kind: research
title: "Sending an hour of recorded audio to Gemini, and what Google does with it"
researched: 2026-09-01
tags: [research]
---
# Sending an hour of recorded audio to Gemini, and what Google does with it

Fetched from ai.google.dev on 2026-09-01 for ticket 03. Every figure below is quoted from a Google
doc unless marked reasoned.

## The number that is not a number

**A free-tier BYO key means Google uses the recording and its transcript to improve its products,
and human reviewers may read them.** From the API terms: *"Google uses the content you submit to the
Services and any generated responses to provide, improve, and develop Google products and
services"*, and *"human reviewers may read, annotate, and process your API input and output"*.
Google's own instruction on the same page: *"you will not submit sensitive, confidential, or
personal information to the Unpaid Services."*

A key is Paid *"only when accessing the API through a Cloud Project associated with an active
billing account."* On a paid key: *"Google doesn't use your prompts or responses to improve our
products"*, and logging is *"for a limited period of time, solely for detecting and preventing
violations"* - the doc states no number of days.

**There is no documented API field that tells the app which tier a key is on.** So LEGION cannot
detect it, cannot warn conditionally, and cannot be sure which regime a given user is under. That is
a disclosure problem, not an engineering one, and it is squarely in the path of clone-and-run: a
stranger who clones LEGION and pastes a free AI Studio key is recording a room into a training
corpus without being told.

## Transport

Files API, resumable, two legs. Inline base64 is capped at **20 MB total request size** (~28 minutes
of AAC), so anything meeting-shaped goes through Files.

1. `POST /upload/v1beta/files` with `X-Goog-Upload-Protocol: resumable`, `X-Goog-Upload-Command:
   start`, `X-Goog-Upload-Header-Content-Length`, `X-Goog-Upload-Header-Content-Type`. The session
   URL comes back in the **`x-goog-upload-url` response header**, not the body.
2. `POST <that url>` with `X-Goog-Upload-Offset: 0` and `X-Goog-Upload-Command: upload, finalize`,
   raw bytes as the body.
3. `GET /v1beta/files/{id}` until `state` moves `PROCESSING` -> `ACTIVE`. Audio always passes through
   `PROCESSING`; a `PROCESSING` uri fails at inference. `FAILED` carries an `error`.
4. Reference the returned uri as a file part in the generation call.

**Conflict, unresolved:** the audio page says use Files above 20 MB, the Files page says above
100 MB. Reasoned: 20 MB is operative. Not established which the server actually enforces.

## Retention on Google's side

**Files are stored for 48 hours**, then auto-deleted; `expirationTime` comes back on the File
resource. `DELETE /v1beta/files/{name}` exists. 20 GB per project, 2 GB per file. **An uploaded file
cannot be downloaded back** by the uploader.

Not established: whether the 48 hours differs by tier.

## Audio

| | |
|---|---|
| Max per request | 9.5 hours stated. **Reasoned real ceiling ~9.1 h** - 9.5 h x 32 tok/s exceeds the 1,048,576 input window |
| Tokenization | 32 tokens per second (1 minute = 1,920 tokens) |
| Preprocessing | Downsampled to 16 Kbps, multi-channel folded to mono |
| Format | `audio/m4a` and `audio/aac` are both accepted. **`audio/mp4` is NOT on the list** - send `MediaRecorder`'s output as `audio/m4a` |

One conflicting data point flagged and not resolved: a search snippet claimed 25 tokens/s for
gemini-3.7-flash where two doc pages say 32. Verify with a live `countTokens` if cost precision
matters.

## Structured output and speakers

Structured output works in the same call as an audio part - Google's own audio page demonstrates it,
though the structured-output guide never states the combination explicitly (reasoned from the
example). Caveats documented: not all JSON Schema features are supported, deeply nested schemas may
be rejected.

**Diarization is not an API feature.** No flag, no speaker field. It is a model capability you get by
asking for it and pinning the shape with a schema; Google's recipe returns `segments[]` with
`speaker`, `timestamp` (MM:SS), `content`, `language`, `emotion`. **The labels are model output, not
a signal-processing result** - un-anchored guesses, and nothing in the docs claims accuracy for
either the speaker or the timestamp.

## Cost

Recommended: **`gemini-3.7-flash`** - the model every current audio example is written against, GA,
1,048,576 in / 65,536 out, and one blended $0.75/1M input rate that covers audio (gemini-2.5-flash
still charges a separate $1.00 audio rate, making the older model the dearer one).

One hour of speech, paid tier, today's rate:

- Audio in: 115,200 tokens -> **$0.086**
- Verbatim transcript out: ~12,000 tokens (reasoned) -> **$0.045**
- **~$0.13/hour, and ~$0.26 from 2027-01-01** when both rates double.

`gemini-3.5-flash-lite` halves it to ~$0.065. **Treat $0.13 as a floor:** Gemini 3 thinking tokens
bill as output and no typical volume for a transcription task could be established.

**Output cap shapes the design:** 65,536 output tokens is roughly 49k words. A one-hour verbatim
transcript fits comfortably; **three hours plus will hit the cap.** Chunk, or return
summary-plus-segments instead of full verbatim.
