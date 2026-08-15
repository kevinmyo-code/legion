# Who gets the microphone, and what happens to Spotify?

Type: research
Status: resolved
Blocked by: -

## Question

Settled decision 3 says the call disguise is being chased **for the microphone**. This ticket
establishes what the microphone situation actually is, so that claim can be confirmed or killed. It
also settles the other side of the audio problem: LEGION talking over music that is already playing.

LEGION's live session is `service/GeminiLiveSession` - a WebSocket to Gemini, server VAD,
half-duplex, hosted by `service/AriaForegroundService`, which already holds `RECORD_AUDIO` and
`FOREGROUND_SERVICE_MICROPHONE`. The car also has `media/SpotifyController` (App Remote direct play)
and `media/MusicController` (generic MediaSession transport).

Establish, against primary sources (Android audio focus and `AudioManager` documentation,
`AudioRecord`/`MediaRecorder.AudioSource` reference, Bluetooth SCO / HFP documentation, Android 14+
foreground-service and background-mic restrictions, Android for Cars docs):

1. **Can a projected third-party app hold the microphone while Android Auto is running at all?**
   Does Android Auto's own Assistant hold or arbitrate mic access, and what happens to an app
   recording when Assistant is invoked - silence, an error, a `RECORD_AUDIO` denial, or nothing?
2. **Which physical mic** a foreground service gets while the phone is connected to a car over
   Bluetooth: the phone's own, or the car's over HFP. What has to be true for the **car's** mic to be
   the source - `MODE_IN_COMMUNICATION`, `startBluetoothSco()` (deprecated - what replaced it),
   `AudioManager.setCommunicationDevice()`, or an active telecom call.
3. **Does a self-managed call change the answer to (2)?** This is the crux of settled decision 3. If
   a plain foreground service can get the car mic by setting the right audio mode, the call route
   loses most of its reason to exist and ticket 07 should hear about it.
4. **Echo.** If LEGION speaks through the car speakers while recording from the car mic, what
   cancels the echo - the HFP path's own AEC, `VOICE_COMMUNICATION` as the audio source, or nothing.
   LEGION is half-duplex, which mitigates but does not eliminate this.
5. **Audio focus against Spotify.** What focus request causes music to **duck** versus **pause**
   (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` vs `AUDIOFOCUS_GAIN_TRANSIENT`), and what a telecom call
   does to other media **automatically** whether or not the app asks. Does Spotify App Remote
   playback behave differently from generic MediaSession playback here?
6. **Android 14/15 restrictions** that bite: foreground-service type requirements for microphone
   use, and whether a `ConnectionService`-hosted call needs a different service type than
   `microphone` (`phoneCall`?).

State which claims are **documented** and which are **inferred**.

Findings go to `.scratch/android-auto/research/04-microphone-and-audio-focus-while-projecting.md`.

## Answer

**The call does not buy the microphone. Settled decision 3's justification is dead.** Full findings
and citations:
[research/04-microphone-and-audio-focus-while-projecting.md](../research/04-microphone-and-audio-focus-while-projecting.md).
Resolved 2026-08-13 from a research agent's report; tags are the agent's, carried forward unchanged.
**Nothing was run on device.**

1. **Question 3 is the crux and the answer is no** (`documented`). Android defines "a voice call is
   active" by `AudioManager.getMode()` returning `MODE_IN_CALL` or `MODE_IN_COMMUNICATION` - **not by
   a Telecom `Connection` existing**. `MODE_IN_COMMUNICATION` is the VoIP mode **any** app may set,
   and `setCommunicationDevice(TYPE_BLUETOOTH_SCO)` is a public `AudioManager` call behind
   `MODIFY_AUDIO_SETTINGS`. **A plain foreground service therefore reaches the car's HFP microphone
   and gets call-grade capture priority.** Core-Telecom explicitly *forbids* calling
   `setCommunicationDevice` when using Telecom - Telecom is a managed wrapper over the same API, not
   a privilege gate - and it neither captures nor plays audio for you, so it simplifies
   `GeminiLiveSession` not at all.
2. **What the call still legitimately buys**, and what ticket 07 must be re-argued on: the in-car
   call UI itself, and an Android 17 background-audio-hardening exemption for Telecom-integrated VoIP
   apps. **Not the mic, not the routing, not the audio plumbing.**
3. **A second finding the map has to absorb.** The only *documented* way for a projected app to read
   the car's microphone through Android Auto is **`CarAudioRecord`** (Car App Library API level 5,
   Android Auto 7.9+) - which requires `androidx.car.app`, which the map **put out of scope while
   charting**. The media surface never receives audio, only a parsed `onPlayFromSearch` string. So
   either the HFP/SCO route works on the rig, **or `androidx.car.app` comes back into scope.**
4. **Three concrete defects in the repo, found while answering:**
   - **`MODIFY_AUDIO_SETTINGS` is not in the manifest.** Required for either routing path.
   - **`GeminiLiveSession.kt:888` uses `VOICE_RECOGNITION`, which is not privacy-sensitive**, so the
     Android Auto Assistant **silences LEGION's capture** - zeroes, no exception, no callback. That
     is the fifth silent-unreachable shape, in the live session itself. Fix: `VOICE_COMMUNICATION`
     and/or `setPrivacySensitive(true)`, and detect it via
     `AudioRecordingConfiguration.isClientSilenced()`.
   - **Android 14 while-in-use:** a `microphone` foreground service **cannot be started from the
     background**, and an active telecom call is **not** on the exemption list. Whether a tap on the
     Android Auto media surface qualifies as a foreground start is **undocumented**, and the agent
     names it as the likeliest place the whole design fails.
   - Off-ticket, worth carrying: the service declares `dataSync`, which is capped at 6h per 24h from
     Android 15 on.
5. **Audio focus and Spotify** are covered in the findings and feed ticket 11 unchanged.

**Consequence for the map: settled decision 3 is amended and settled decision 1 is re-opened.**
Kevin took "two surfaces, deliberately" on the premise that the call was how LEGION got the car's
microphone. That premise is false. Ticket 07 case 4 is now live and must be put to him.

Five on-device experiments named; E1/E2/E3 share one debug screen and one drive. All of them need a
UI debug surface first, since logcat is unusable on the A17k.
