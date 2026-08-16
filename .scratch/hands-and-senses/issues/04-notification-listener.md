# Notification listener: the phone as a sense

Type: grilling
Status: open
Blocked by: -

## Question

`NotificationListenerService` gives LEGION ambient awareness of the phone itself: who texted, what
app is yelling, what arrived while driving. Kevin called it gold. It is also the widest privacy
aperture on this map - every app's notifications, including 2FA codes, medical apps, other
people's messages on the lockscreen. Decide:

1. **Allowlist or denylist.** Which apps' notifications does LEGION see at all? An explicit
   allowlist Kevin curates (messaging, calendar, maybe banking) is the §4-shaped answer; a
   denylist leaks by default. Where does the list live and how is it edited?
2. **What reaches the model.** The mail read-through rule (google-account ticket 07) is the
   precedent: read, used, dropped, never stored, excluded from `EpisodicTurn`/`CompanionMemory`.
   Does it apply verbatim? Notification content includes message bodies from people who never
   consented to an LLM reading them - is content ever sent to Gemini, or only app + sender + count
   until Kevin asks for the body?
3. **Pull or ambient.** Pull-based tools is the standing rule: "anything new?" as a tool call. Is
   there ANY ambient injection (a count in the live-session preamble), and does that survive the
   token-budget argument?
4. **Driving.** "Read that to me" hands-free is the killer use. Does the fleet Phase (driving
   detected) change what is offered, or is that a proactive raise the compulsion line forbids?
5. **2FA and secrets.** OTP codes appear in notifications. Blanket rule: never spoken, never sent
   to Gemini, never logged - or is reading an OTP aloud on request exactly the hands-free value?
   Decide deliberately.
6. **Tool budget.** One tool? Write the description in the answer.
