# Comms: place a call, send a text

Type: grilling
Status: open
Blocked by: -

## Question

The hands-busy case (driving, wrenching) wants "call Mom" and "text Sam I'm ten minutes out".
Android gives both without any cloud: `Intent.ACTION_CALL` (or dialer intent), `SmsManager` for
SMS. Known limit to state up front: third-party apps cannot send RCS; texts LEGION sends are SMS,
green-bubble, and a thread Kevin continues by hand lives in his SMS app. Decide:

1. **Outbound only?** Sending and calling is one scope; reading threads is another (and overlaps
   [the notification listener](04-notification-listener.md) for incoming). Does comms stay
   write-only, with incoming handled as notifications?
2. **The confirm turn.** Standing preference says outward-facing actions confirm first. For a
   text: Alfred reads back recipient + body verbatim, waits for yes. For a call: is confirmation
   the dialer screen itself (ACTION_DIAL, Kevin presses call) or does LEGION place it directly
   (ACTION_CALL, needs CALL_PHONE permission)? Direct placement is the hands-free point - but is
   it always, or only in driving Phase?
3. **Contact resolution.** "Sam" matching two contacts: disambiguate by voice. Does LEGION read
   contacts on demand (pull tool over `ContactsContract`) and what reaches Gemini - full contact
   list never; candidate names for the utterance only?
4. **Message content and the model.** The body Kevin dictates obviously goes through Gemini (it is
   the transcript). Read-through applies to anything else: no thread history, no stored messages,
   nothing in CompanionMemory. Confirm.
5. **Tool budget.** `send_text` + `place_call`, or one `comms` tool? Write the descriptions.
