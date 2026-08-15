# The live session can be silenced with no error

Type: task
Status: open
Blocked by: -

## Question

Ticket 04 found this while answering something else, and it is not an Android Auto problem - it is a
defect in the shipped live session that happens to be worst in the car.

`GeminiLiveSession.kt:888` opens its capture with `MediaRecorder.AudioSource.VOICE_RECOGNITION`.
That source is **not** privacy-sensitive, so when another app takes a privacy-sensitive capture -
the Android Auto Assistant being the obvious one - the platform hands LEGION **zeroes**. No
exception, no callback, no state change. LEGION carries on with an open socket, a live session, and
silence on the wire. It is the fifth silent-unreachable shape, inside the one component the whole
product depends on.

`MODIFY_AUDIO_SETTINGS` is also absent from `AndroidManifest.xml`, and **both** microphone routing
paths need it.

Do:

1. **Prove it first.** Start a live session, invoke "Hey Google" over the top, and observe what the
   session receives. `traced` from a research agent's read is not `tested`, and `memory/MEMORY.md` is
   explicit that a relayed claim is not a verification. If it does not reproduce, say so and stop.
2. Add `MODIFY_AUDIO_SETTINGS` to the manifest.
3. Switch the capture to `VOICE_COMMUNICATION` and/or call `setPrivacySensitive(true)`. Note that
   `VOICE_COMMUNICATION` also brings the platform's echo cancellation, which the car surface wants
   anyway.
4. **Make the silencing detectable regardless.** Register an
   `AudioManager.AudioRecordingCallback` and read `AudioRecordingConfiguration.isClientSilenced()`.
   Whatever source is chosen, LEGION should be able to say "I am being silenced" instead of appearing
   to listen. Surface it in the UI, not in logcat - the A17k filters app logs.
5. Off-ticket but carry it: `AriaForegroundService` declares the `dataSync` foreground service type,
   which Android 15 caps at **6 hours per 24**. Check whether that bites a long drive, and whether
   the declared type is even the right one now.

This is a phone-side defect with or without the car. If it reproduces, it should not wait for the
rest of this map.
