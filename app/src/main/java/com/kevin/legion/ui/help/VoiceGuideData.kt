// GENERATED FILE - DO NOT HAND EDIT.
//
// Produced by `python tools/voice_guide.py` from `tools/voice_guide_copy.py` - the exact same
// source `docs/voice.html` is generated from (command-center ticket 09, ADR 0035's in-app hands
// path). Edit the copy there and rerun the script; a hand edit here is overwritten on the next run
// and, if it diverges first, `voice_guide.py --check` fails the build and names the drift - same
// posture as the tool-list check this file's generator already enforces.

package com.kevin.legion.ui.help

/**
 * Data for the in-app "What can I do" screen (`ui/help/VoiceGuideScreen.kt`).
 * See the header above: this whole file is generated, never hand edit it.
 */
object VoiceGuideData {

    /**
     * One voice capability. [hands] is ADR 0035's field: where the same
     * capability is reachable without voice, or "Voice only." when it is not yet.
     */
    data class Entry(
        val name: String,
        val say: String,
        val does: String,
        val hands: String,
    )

    data class Group(
        val title: String,
        val blurb: String?,
        val entries: List<Entry>,
    )

    val INTRO: String = "LEGION is a voice assistant for the phone. Hold the button or say the wake word, then just talk. It looks after cars, money, food, training, notes and mail - and it will tell you when it cannot do something rather than guessing."

    val GROUPS: List<Group> = listOf(
        Group(
            title = "Getting started",
            blurb = "The first things worth trying.",
            entries = listOf(
                Entry(name = "get_sitrep", say = "Give me a sitrep", does = "A status report: calendar, weather, car and a newsletter summary - whichever you have switched on.", hands = "Home screen tiles cover the same ground piece by piece - the day, the area/weather strip, alerts, and a tap-to-fetch newsletter card - but there is no single \"sitrep\" button."),
                Entry(name = "get_current_time", say = "What time is it?", does = "The time and date where you are.", hands = "Voice only - your phone's own clock and status bar are the hands equivalent."),
                Entry(name = "get_current_location", say = "Where am I?", does = "Your current location. Says it does not know rather than guessing when there is no GPS fix.", hands = "Fleet > Saved places screen shows current location at the top."),
                Entry(name = "area_info", say = "Any severe weather nearby?", does = "Checks a live source for where you are now: severe weather (National Weather Service), earthquakes (USGS), wildfires (National Interagency Fire Center), or federal disaster declarations (FEMA). Always names its source, and needs a live GPS fix.", hands = "Home screen, area card (weather/quake/wildfire/disaster alerts and air quality)."),
                Entry(name = "get_reported_crime_history", say = "What's the crime history around here?", does = "Reported offenses for the nearest police or sheriff's agency, from the FBI's Crime Data Explorer - agency-wide, roughly a year old, and voluntarily reported. It will not tell you whether somewhere is \"safe\"; nothing can answer that honestly from this data.", hands = "Voice only."),
                Entry(name = "show_app", say = "Open the ledger", does = "Opens a screen in the app for you.", hands = "Is itself navigation - every screen it can open is also reachable directly from the app's own tabs."),
                Entry(name = "finish_intro", say = "I'm done setting up", does = "Finishes first-run setup.", hands = "Onboarding screen's own final step."),
                Entry(name = "end_conversation", say = "Go to sleep", does = "Stops listening and goes quiet. \"That's all\", \"never mind\" and \"stand down\" do the same. It reads this as YOU going dormant, not as your bedtime - no goodnight.", hands = "Voice only - closing the app or its notification is the nearest hands equivalent."),
            ),
        ),
        Group(
            title = "Your day",
            blurb = "Calendar, lists, reminders and the round-up.",
            entries = listOf(
                Entry(name = "manage_item", say = "Remind me to renew my registration next month", does = "Adds, ticks off, reschedules, or removes a REMINDER - one with a due date/time, a place trigger, or a repeat. A plain to-do with none of those is a checklist line instead.", hands = "Calendar tab, tap a day then tap a reminder row to edit its time, repeat, place, or remove it."),
                Entry(name = "manage_checklist", say = "Tick off squats on bio", does = "Creates and manages your own reusable checklists - \"bio\", \"morning routine\", plain to-dos too - each optionally daily or weekly, each line optionally a number against a target instead of a plain tick. Your grocery/shopping list and your plain to-do list are both checklists, named \"Groceries\" and \"Todo\".", hands = "Meters screen's LISTS pane - the checklists screen."),
                Entry(name = "read_list", say = "What have I got coming up?", does = "Reads back your open reminders, soonest due first, each with its date, place or repeat.", hands = "Calendar tab, tap a day to see that day's reminders."),
                Entry(name = "set_reminder", say = "Remind me to grab my gym bag when I get to the gym", does = "Sets a reminder tied to a saved place, so it comes up when you next arrive there.", hands = "Partial - Calendar's day view shows and edits a place-triggered reminder, but there is no add-a-new-reminder dialog by hand."),
                Entry(name = "read_calendar", say = "What's on today?", does = "Reads your Google Calendar. Says nothing is on when nothing is - it never invents an appointment.", hands = "Calendar tab's month grid and day view, and Home's next-event tile."),
                Entry(name = "tag_place", say = "Save this as work", does = "Saves where you are now under a name, so reminders can trigger there.", hands = "Fleet > Saved places screen."),
                Entry(name = "forget_place", say = "Forget the old gym", does = "Removes a saved place.", hands = "Fleet > Saved places screen, delete behind a confirm."),
                Entry(name = "show_saved_places", say = "Show my saved places", does = "Puts your saved places on screen.", hands = "Is itself the Fleet > Saved places screen."),
                Entry(name = "open_navigation", say = "Navigate to the hardware store", does = "Hands off to your maps app.", hands = "Fleet > Saved places screen, Navigate button on each place."),
                Entry(name = "show_agenda_modal", say = "Show me my agenda", does = "Pops up today's due items - reminders, appointments, anything dated today - without leaving where you are.", hands = "Calendar tab, today's day view shows the same thing directly."),
                Entry(name = "show_generated_view", say = "Show me my grocery spend by month", does = "Builds a one-off chart or total for a niche money question no screen already covers. It only ever picks what to look up - every number on screen comes from your own real data, never from a guess.", hands = "Meters tab, the Ask section - pick source, aggregation, window and grouping by tapping."),
            ),
        ),
        Group(
            title = "The cars",
            blurb = "Diagnostics, maintenance and history. Most of it needs the OBD dongle plugged in.",
            entries = listOf(
                Entry(name = "get_codes", say = "Any trouble codes?", does = "Reads fault codes stored in the car right now.", hands = "Fleet tab, trouble-code panel."),
                Entry(name = "diagnose_codes", say = "What's wrong with the car?", does = "Digs into the stored codes and explains them. Takes a few seconds.", hands = "Fleet tab, code diagnosis drilldown."),
                Entry(name = "clear_codes", say = "Clear the codes", does = "Clears stored fault codes. Asks you to confirm first.", hands = "Fleet tab, clear-codes action, confirm-gated."),
                Entry(name = "get_code_history", say = "Has that code come back before?", does = "The history of faults this car has thrown.", hands = "Fleet tab, code history view."),
                Entry(name = "triage_symptom", say = "It's making a whining noise on turns", does = "Describe how the car is behaving and it works from that, not from a code.", hands = "Voice only - a free-text symptom description has no form equivalent."),
                Entry(name = "check_readiness", say = "Is it ready for emissions?", does = "Whether the car's self-tests have run - the emissions readiness monitors.", hands = "Fleet tab, readiness/emissions panel."),
                Entry(name = "check_cold_start", say = "Did it do a cold start test?", does = "Whether the cold-start monitor has completed.", hands = "Fleet tab, readiness panel."),
                Entry(name = "get_vehicle_data", say = "What's the coolant temperature?", does = "Live readings from the OBD dongle.", hands = "Fleet tab, live telemetry rows."),
                Entry(name = "read_vehicle_sensor", say = "What's the oil temp?", does = "Reads one specific sensor by name.", hands = "Fleet tab, telemetry rows."),
                Entry(name = "get_health", say = "How's the car doing?", does = "An overall health summary - codes, temperatures, readiness.", hands = "Fleet tab, health summary panel."),
                Entry(name = "get_mpg", say = "What am I getting to the gallon?", does = "Fuel economy from recorded drives.", hands = "Fleet tab, fuel economy view."),
                Entry(name = "get_trend", say = "Is the voltage getting worse?", does = "How a reading has moved over time.", hands = "Fleet tab, trend drilldown."),
                Entry(name = "get_specs", say = "What oil does it take?", does = "Factory specs for the car - engine, drivetrain, fluids.", hands = "Fleet > Vehicle specs screen."),
                Entry(name = "lookup_vin", say = "Look up this VIN", does = "Decodes a VIN into make, model and year.", hands = "Fleet tab, add/register-a-car flow's VIN lookup."),
                Entry(name = "check_recalls", say = "Any recalls on it?", does = "Open safety recalls, from the NHTSA database.", hands = "Fleet tab, recalls panel."),
                Entry(name = "get_next_service", say = "What's due next?", does = "The next maintenance item, by miles or by date.", hands = "Fleet tab, next-due panel."),
                Entry(name = "ask_maintenance", say = "When did I last do the brakes?", does = "Questions about your service history.", hands = "Fleet > Service history screen."),
                Entry(name = "log_service", say = "I changed the oil today", does = "Records a service you just did.", hands = "Fleet > Service history screen - marking an item done now writes the service record and its cost."),
                Entry(name = "log_past_service", say = "I did the plugs back in June", does = "Records a service from the past.", hands = "Fleet > Service history screen, log a past service."),
                Entry(name = "set_maintenance_interval", say = "Set the oil change to every 5000 miles", does = "Changes how often an item is due.", hands = "Fleet tab, a maintenance item's interval editor."),
                Entry(name = "set_odometer", say = "The odometer reads 148,200", does = "Updates the mileage, which keeps due-dates honest.", hands = "Fleet tab, odometer field."),
                Entry(name = "log_build_entry", say = "Log that I fitted new plugs, forty quid", does = "Records a build or modification, with cost.", hands = "Fleet > Build sheet screen."),
                Entry(name = "list_build_history", say = "What have I done to this car?", does = "Reads back the build history.", hands = "Fleet > Build sheet screen."),
                Entry(name = "register_car", say = "Add my Jeep", does = "Adds a car to the fleet.", hands = "Fleet tab, add-a-car flow."),
                Entry(name = "register_vehicle", say = "Register this vehicle", does = "Registers a vehicle by VIN or details.", hands = "Fleet tab, add-a-car flow (VIN path)."),
                Entry(name = "manage_vehicle", say = "Rename the Jeep to the XJ", does = "Renames, updates or removes a car.", hands = "Fleet tab, a car row's edit/rename action."),
                Entry(name = "list_vehicles", say = "What cars do I have?", does = "Lists your fleet.", hands = "Fleet tab, car list."),
                Entry(name = "ask_fleet", say = "Ask the fleet advisor about my tyres", does = "Sends a harder car question to the fleet specialist.", hands = "Is itself a read-only dispatcher; the fleet screens above already show what it would answer."),
            ),
        ),
        Group(
            title = "Driving",
            blurb = "Things that only make sense while you are actually out.",
            entries = listOf(
                Entry(name = "activate_garage", say = "Open the garage", does = "Triggers the garage relay. Asks you to confirm, and never claims to know whether the door opened or closed.", hands = "Voice only - no garage button in the app."),
                Entry(name = "control_volume", say = "Turn it up", does = "Changes the volume.", hands = "Media panel, volume control."),
            ),
        ),
        Group(
            title = "Money",
            blurb = "Bank statements, budgets and spending. Figures come from your own imported statements.",
            entries = listOf(
                Entry(name = "get_balance", say = "What's my balance?", does = "Balance from your imported statements.", hands = "Money tab, balances."),
                Entry(name = "get_spend", say = "How much did I spend on fuel?", does = "Spending in a category or over a period.", hands = "Money tab, spend view."),
                Entry(name = "get_monthly_spend", say = "What did I spend last month?", does = "A month's total.", hands = "Money tab, monthly spend view."),
                Entry(name = "list_recent_transactions", say = "What have I spent lately?", does = "Recent transactions.", hands = "Money tab, transaction list."),
                Entry(name = "categorize_transactions", say = "Sort out my uncategorised spending", does = "Sorts transactions into categories.", hands = "Money tab, categorize flow."),
                Entry(name = "set_category", say = "That one's groceries", does = "Recategorises a transaction.", hands = "Money tab, recategorise a transaction row."),
                Entry(name = "set_budget", say = "Budget two hundred a month for fuel", does = "Sets a monthly budget for a category.", hands = "Money tab, budget editor."),
                Entry(name = "list_budget_categories", say = "What budgets do I have?", does = "Lists your budget categories.", hands = "Money tab, budget list."),
                Entry(name = "log_pending_transaction", say = "I just spent thirty on petrol", does = "Records a spend by voice before it hits the bank.", hands = "Money tab, add-pending dialog."),
                Entry(name = "list_pending_transactions", say = "What have I logged but not banked?", does = "Voice-logged spends the bank has not posted yet.", hands = "Money tab, pending list."),
                Entry(name = "clear_pending_transaction", say = "That petrol one has gone through", does = "Clears a pending spend once the bank catches up.", hands = "Money tab, a pending row's clear action."),
            ),
        ),
        Group(
            title = "Food and shopping",
            blurb = "Groceries, receipts and meals.",
            entries = listOf(
                Entry(name = "import_receipt", say = "Scan this receipt", does = "Opens the camera to photograph a grocery receipt.", hands = "Money > Pantry sub-route, scan flow."),
                Entry(name = "list_recent_groceries", say = "What did I buy last shop?", does = "Recent grocery purchases.", hands = "Money > Pantry sub-route."),
                Entry(name = "get_grocery_spend", say = "How much on groceries this month?", does = "Grocery spending.", hands = "Money > Pantry sub-route, spend panel."),
                Entry(name = "log_meal", say = "I had chicken and rice", does = "Logs a meal. Calories and macros are estimates from the description, not measurements.", hands = "Body tab, log-a-meal dialog."),
                Entry(name = "list_recent_meals", say = "What have I eaten today?", does = "Recent meals.", hands = "Body tab, meal history."),
                Entry(name = "get_meal_gap", say = "When did I last eat?", does = "How long since your last logged meal.", hands = "Body tab, meal-gap indicator."),
                Entry(name = "set_meal_target", say = "Set my protein target to 180", does = "Sets a daily nutrition target.", hands = "Body tab, meal-target dialog."),
                Entry(name = "ask_pantry", say = "Ask the pantry what I'm low on", does = "Sends a harder food question to the pantry specialist.", hands = "Is itself a read-only dispatcher; the Pantry sub-route already shows what it would answer."),
            ),
        ),
        Group(
            title = "Training and sleep",
            blurb = "Workouts, bodyweight and rest.",
            entries = listOf(
                Entry(name = "log_workout_set", say = "Squats, five at a hundred kilos", does = "Logs a set.", hands = "Body tab, log-a-set dialog."),
                Entry(name = "list_recent_workouts", say = "What did I train this week?", does = "Recent workouts.", hands = "Body tab, workout history."),
                Entry(name = "get_workout_gap", say = "When did I last train legs?", does = "How long since you trained.", hands = "Body tab, workout-gap indicator."),
                Entry(name = "create_workout_plan", say = "Make me a plan to get stronger", does = "Builds a training plan from a goal you describe.", hands = "Voice only - a generated plan has no form equivalent; the closest hands step is reviewing and accepting one on the Goals panel."),
                Entry(name = "log_bodyweight", say = "I'm eighty-two kilos", does = "Logs your bodyweight.", hands = "Body tab, log-bodyweight dialog."),
                Entry(name = "log_sleep", say = "I slept six hours, woke up twice", does = "Logs a night's sleep.", hands = "Body tab, log-sleep dialog."),
                Entry(name = "list_recent_sleep", say = "How have I been sleeping?", does = "Recent sleep.", hands = "Body tab, sleep history."),
                Entry(name = "get_sleep_gap", say = "When did I last log sleep?", does = "How long since your last sleep entry.", hands = "Body tab, sleep-gap indicator."),
                Entry(name = "set_sleep_target", say = "I want eight hours a night", does = "Sets your nightly sleep target.", hands = "Body tab, sleep-target dialog."),
                Entry(name = "ask_body", say = "Ask the coach about my recovery", does = "Sends a harder training or nutrition question to the coach.", hands = "Is itself a read-only dispatcher; the Body tab already shows what it would answer."),
            ),
        ),
        Group(
            title = "Goals and advice",
            blurb = "Longer-term things, and the per-aspect advisors.",
            entries = listOf(
                Entry(name = "set_goal", say = "I want to save five grand by December", does = "Sets a goal.", hands = "Goals panel, add-a-goal flow."),
                Entry(name = "list_goals", say = "What am I working towards?", does = "Lists your current goals.", hands = "Goals panel."),
                Entry(name = "close_goal", say = "I hit the savings goal", does = "Closes a goal as achieved or abandoned.", hands = "Goals panel, close-goal action."),
                Entry(name = "ask_goals", say = "How am I doing on my goals?", does = "Sends a goals question to the specialist.", hands = "Is itself a read-only dispatcher; the Goals panel already shows what it would answer."),
                Entry(name = "ask_advisor", say = "How am I doing overall?", does = "Asks one of the five advisors - body, notes, fleet, money, or overall.", hands = "Is itself a read-only dispatcher; each aspect's own screen shows the same data."),
                Entry(name = "accept_proposal", say = "Yes, do that", does = "Accepts something an advisor proposed.", hands = "Advisor proposals render with accept/dismiss buttons wherever that aspect surfaces them (Money tab today)."),
                Entry(name = "generate_goal_plan", say = "Lose fat, gain muscle - build me a plan", does = "Turns a fitness/nutrition goal into a rough calorie, sleep, and workout plan to look over - a proposal, not something set up yet. Say a constraint once (\"I don't have gym access\") and it's remembered for next time.", hands = "Goals panel, plan-review dialog."),
                Entry(name = "accept_goal_plan", say = "Yes, set that up", does = "Sets up the workout part of a plan once you've agreed to the whole thing.", hands = "Goals panel, plan-review dialog's accept action."),
                Entry(name = "undo_last_log", say = "Undo that", does = "Undoes the last thing you logged.", hands = "Body tab, a per-row delete on the same four logs (reaches the exact same delete functions)."),
            ),
        ),
        Group(
            title = "Music",
            blurb = "Spotify, mostly. Needs it connected in Setup.",
            entries = listOf(
                Entry(name = "play_music", say = "Play the Roadtrip playlist", does = "Plays something on Spotify.", hands = "Media panel, search and play."),
                Entry(name = "control_music", say = "Skip this", does = "Play, pause, skip, previous.", hands = "Media panel or mini-bar, transport controls."),
                Entry(name = "get_music_queue", say = "What's coming up?", does = "What is queued next.", hands = "Media panel, queue view."),
                Entry(name = "browse_my_music", say = "What albums have I saved?", does = "Looks through your own Spotify library.", hands = "Media panel, browse view."),
            ),
        ),
        Group(
            title = "Mail and calendar",
            blurb = "Needs a Google account connected.",
            entries = listOf(
                Entry(name = "search_mail", say = "Any mail from the garage?", does = "Searches your Gmail. Mail is read and used, never stored.", hands = "Voice only - no in-app mail search."),
                Entry(name = "read_mail", say = "Read me that one", does = "Reads an email out.", hands = "Voice only."),
                Entry(name = "ask_mail", say = "What did the insurance email say?", does = "Digs through your mail to answer a question.", hands = "Voice only - flagged as a decision conflict with ADR 0035, not yet resolved (command-center map, ruling 1)."),
                Entry(name = "track_package", say = "Where's my package?", does = "Reads your most recent shipping email for the carrier, tracking number and delivery status. Always says which email and when it was sent, and that it's an estimate from the email text, not a live carrier lookup.", hands = "Home screen, package tile."),
                Entry(name = "flight_status", say = "When's my flight?", does = "Checks your calendar first, since airlines usually put flights there automatically and that's exact. Falls back to your travel-confirmation email for anything the calendar doesn't have, and says plainly that's an estimate from the email, not a confirmed schedule.", hands = "Home screen, flight tile."),
            ),
        ),
        Group(
            title = "Phone calls",
            blurb = "Needs phone permissions granted in Setup.",
            entries = listOf(
                Entry(name = "answer_call", say = "Answer it", does = "Picks up a ringing call. Add “and put it on speaker” and it does both. Cannot answer WhatsApp, Signal or Teams calls - Android does not allow it.", hands = "Ringing-call notification, Answer action."),
                Entry(name = "decline_call", say = "Decline it", does = "Rejects a ringing call.", hands = "Ringing-call notification, Decline action."),
                Entry(name = "place_call", say = "Call Mom", does = "Places an outbound call - by contact name or a number you say. Always reads the target back first (the name, or the digits) and waits for a yes before dialling, so a misheard digit never dials a stranger. Asks rather than guesses if a name matches nobody or several people. Refuses emergency numbers outright and tells you to dial them yourself. Cannot send texts - that was ruled out on purpose.", hands = "Settings > Phone dial screen."),
            ),
        ),
        Group(
            title = "Recordings",
            blurb = "Voice notes and meetings. Audio, a transcript and a summary are all kept until you delete them, and the summary is the assistant's reading of the transcript, not a verbatim record.",
            entries = listOf(
                Entry(name = "start_voice_note", say = "Record this meeting", does = "Starts recording a voice note - a meeting or a thought you want kept. Refuses out loud, with nothing started, if something is already recording or the microphone is busy.", hands = "Setup > Data & privacy > Recordings."),
                Entry(name = "stop_voice_note", say = "Stop recording", does = "Stops and saves the recording. It is only saved at that point, not yet transcribed - it will not claim the note is ready to read back until transcription has actually finished.", hands = "Setup > Data & privacy > Recordings."),
                Entry(name = "read_voice_note", say = "What did that meeting say?", does = "Reads back a note's summary. The summary is written from the transcript by a model, not a verbatim account, and anything in it - a number, a date, a name - is reported as something that was said, never as confirmed fact.", hands = "Setup > Data & privacy > Recordings."),
                Entry(name = "list_voice_notes", say = "What recordings do I have?", does = "Lists recent voice notes: titles, when they were recorded, and whether each was solo or a meeting.", hands = "Setup > Data & privacy > Recordings."),
            ),
        ),
        Group(
            title = "Memory",
            blurb = "What it remembers about you, and why it said something.",
            entries = listOf(
                Entry(name = "remember", say = "Remember that the XJ takes 5W-30", does = "Stores something for later.", hands = "Settings > Memory screen, add dialog."),
                Entry(name = "recall_memory", say = "What do you remember about the Jeep?", does = "Looks up what it has stored.", hands = "Settings > Memory screen, browse stored facts."),
                Entry(name = "why_did_you_say_that", say = "Why did you say that?", does = "Explains what triggered the last thing it said on its own - the rule and the fact behind it.", hands = "Voice only - no audit-trail screen."),
            ),
        ),
        Group(
            title = "Settings and control",
            blurb = "Changing how it behaves, by voice.",
            entries = listOf(
                Entry(name = "set_companion_name", say = "Call yourself Alfred", does = "Renames your companion.", hands = "Settings > Assistant screen, name field."),
                Entry(name = "set_personality", say = "Be drier about it", does = "Changes the personality.", hands = "Settings > Assistant screen, personality picker."),
                Entry(name = "set_driver", say = "My name is Kevin", does = "Tells it what to call you.", hands = "Settings > Assistant screen, your name field."),
            ),
        ),
        Group(
            title = "Your own trackers",
            blurb = "Anything you have LEGION track that is not a car, a bank statement, or a receipt - a workout log, a reading list, a habit, whatever you want. Ask for one to be built and it drafts the shape and reads it back before creating anything.",
            entries = listOf(
                Entry(name = "list_aspects", say = "What are you tracking for me?", does = "Lists every area it tracks - cars, money, food, and anything you have set up yourself.", hands = "Voice only for now - a dedicated screen for user-authored trackers has not been built yet."),
                Entry(name = "describe_aspect", say = "What can I log under Workouts?", does = "Reads back what fields one of your trackers has.", hands = "Voice only for now, same as list_aspects."),
                Entry(name = "query_records", say = "Show me my last few workouts", does = "Finds entries in one of your trackers, optionally narrowed by a value.", hands = "Voice only for now, same as list_aspects."),
                Entry(name = "create_record", say = "Log bench press, 185 for 5", does = "Writes one new entry into one of your trackers. Only writes what you actually said - never guesses a value.", hands = "Voice only for now, same as list_aspects."),
                Entry(name = "update_record", say = "Change that to 190 pounds", does = "Changes one or more fields on an entry you already logged.", hands = "Voice only for now, same as list_aspects."),
                Entry(name = "delete_record", say = "Delete that entry", does = "Removes one entry (into a 30-day recoverable trash) right away, or asks you to confirm first when it would remove several at once.", hands = "Voice only for now, same as list_aspects."),
                Entry(name = "aspect_clerk", say = "Log three sets of squats at 225, 5 reps each", does = "A helper that works out what to read or write across your trackers for a request with more than one step - finding something first, or logging several things from one sentence. Tells you plainly how much of it actually got written.", hands = "Voice only for now, same as list_aspects."),
                Entry(name = "create_aspect", say = "Start tracking my reading - book, pages, date finished", does = "Drafts a brand-new tracker from what you describe and reads the shape back to you. Creates nothing until you say yes.", hands = "Voice only for now, same as list_aspects."),
                Entry(name = "update_aspect", say = "Add a genre field to my reading tracker", does = "Drafts one change to a tracker you already have - a new field, a new category of entry, or a rename - and reads it back. Changes nothing until you say yes.", hands = "Voice only for now, same as list_aspects."),
            ),
        ),
    )

    /** Total entries across every group above - 118 as of the last regeneration. */
    val TOOL_COUNT: Int = 118
}
