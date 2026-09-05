"""User-facing copy for `docs/voice.html` AND `ui/help/VoiceGuideData.kt`. Hand-written; the tool
LIST is not.

Each entry is `tool_name: (what you'd say, what it does, where the hands path lives)`. Keep the
first short and natural - it is an example of a real sentence, not a command syntax. Keep the
second one line, plain, and honest about limits. **The third is ADR 0035's hands-path field**
(command-center ticket 09, `.scratch/command-center/issues/09-discovery-and-wiki.md`): name the
screen a capability is also reachable from, verbatim as a user would find it ("Fleet > Saved places
screen"), or say plainly `"Voice only."` when no hands path exists yet. Never leave it blank - a
missing hands note reads as "nobody checked", and the whole point of ADR 0035 is that somebody did.

**If you add a voice tool and do not add copy here, `tools/voice_guide.py` fails.** That is
deliberate: a tool nobody can discover may as well not exist, and a guide that silently omits things
teaches the wrong mental model of what the app can do.

**This same dict also generates `ui/help/VoiceGuideData.kt`**, the in-app "What can I do" screen's
one source of truth (`tools/voice_guide.py`'s `write_kotlin()`). Editing a hands note here and
running the script is the only way to change what that screen shows - the generated file carries a
do-not-hand-edit header and `--check` fails the build if it goes stale.

Do not paste the Kotlin `description` in here. Those are written to steer a language model - dense,
full of "never say X unless" caveats - and they read badly to a human.
"""

INTRO = (
    "LEGION is a voice assistant for the phone. Hold the button or say the wake word, then just "
    "talk. It looks after cars, money, food, training, notes and mail - and it will tell you when "
    "it cannot do something rather than guessing."
)

GROUP_BLURBS = {
    "Getting started": "The first things worth trying.",
    "Your day": "Calendar, lists, reminders and the round-up.",
    "The cars": "Diagnostics, maintenance and history. Most of it needs the OBD dongle plugged in.",
    "Driving": "Things that only make sense while you are actually out.",
    "Money": "Bank statements, budgets and spending. Figures come from your own imported statements.",
    "Food and shopping": "Groceries, receipts and meals.",
    "Training and sleep": "Workouts, bodyweight and rest.",
    "Goals and advice": "Longer-term things, and the per-aspect advisors.",
    "Music": "Spotify, mostly. Needs it connected in Setup.",
    "Mail and calendar": "Needs a Google account connected.",
    "Phone calls": "Needs phone permissions granted in Setup.",
    "Recordings": "Voice notes and meetings. Audio, a transcript and a summary are all kept until "
        "you delete them, and the summary is the assistant's reading of the transcript, not a "
        "verbatim record.",
    "Memory": "What it remembers about you, and why it said something.",
    "Settings and control": "Changing how it behaves, by voice.",
    "Your own trackers": "Anything you have LEGION track that is not a car, a bank statement, or "
        "a receipt - a workout log, a reading list, a habit, whatever you want. Ask for one to be "
        "built and it drafts the shape and reads it back before creating anything.",
}

COPY = {
    # --- Getting started ---
    "get_sitrep": ("Give me a sitrep", "A status report: calendar, weather, car and a newsletter summary - whichever you have switched on.", "Home screen tiles cover the same ground piece by piece - the day, the area/weather strip, alerts, and a tap-to-fetch newsletter card - but there is no single \"sitrep\" button."),
    "get_current_time": ("What time is it?", "The time and date where you are.", "Voice only - your phone's own clock and status bar are the hands equivalent."),
    "get_current_location": ("Where am I?", "Your current location. Says it does not know rather than guessing when there is no GPS fix.", "Fleet > Saved places screen shows current location at the top."),
    "area_info": ("Any severe weather nearby?", "Checks a live source for where you are now: severe weather (National Weather Service), earthquakes (USGS), wildfires (National Interagency Fire Center), or federal disaster declarations (FEMA). Always names its source, and needs a live GPS fix.", "Home screen, area card (weather/quake/wildfire/disaster alerts and air quality)."),
    "get_reported_crime_history": ("What's the crime history around here?", "Reported offenses for the nearest police or sheriff's agency, from the FBI's Crime Data Explorer - agency-wide, roughly a year old, and voluntarily reported. It will not tell you whether somewhere is \"safe\"; nothing can answer that honestly from this data.", "Voice only."),
    "show_app": ("Open the ledger", "Opens a screen in the app for you.", "Is itself navigation - every screen it can open is also reachable directly from the app's own tabs."),
    "end_conversation": ("Go to sleep", "Stops listening and goes quiet. \"That's all\", \"never mind\" and \"stand down\" do the same. It reads this as YOU going dormant, not as your bedtime - no goodnight.", "Voice only - closing the app or its notification is the nearest hands equivalent."),
    "finish_intro": ("I'm done setting up", "Finishes first-run setup.", "Onboarding screen's own final step."),

    # --- Your day ---
    "manage_item": ("Remind me to renew my registration next month", "Adds, ticks off, reschedules, or removes a REMINDER - one with a due date/time, a place trigger, or a repeat. A plain to-do with none of those is a checklist line instead.", "Calendar tab, tap a day then tap a reminder row to edit its time, repeat, place, or remove it."),
    "manage_checklist": ("Tick off squats on bio", "Creates and manages your own reusable checklists - \"bio\", \"morning routine\", plain to-dos too - each optionally daily or weekly, each line optionally a number against a target instead of a plain tick. Your grocery/shopping list and your plain to-do list are both checklists, named \"Groceries\" and \"Todo\".", "Meters screen's LISTS pane - the checklists screen."),
    "read_list": ("What have I got coming up?", "Reads back your open reminders, soonest due first, each with its date, place or repeat.", "Calendar tab, tap a day to see that day's reminders."),
    "set_reminder": ("Remind me to grab my gym bag when I get to the gym", "Sets a reminder tied to a saved place, so it comes up when you next arrive there.", "Partial - Calendar's day view shows and edits a place-triggered reminder, but there is no add-a-new-reminder dialog by hand."),
    "read_calendar": ("What's on today?", "Reads your Google Calendar. Says nothing is on when nothing is - it never invents an appointment.", "Calendar tab's month grid and day view, and Home's next-event tile."),
    "tag_place": ("Save this as work", "Saves where you are now under a name, so reminders can trigger there.", "Fleet > Saved places screen."),
    "forget_place": ("Forget the old gym", "Removes a saved place.", "Fleet > Saved places screen, delete behind a confirm."),
    "show_saved_places": ("Show my saved places", "Puts your saved places on screen.", "Is itself the Fleet > Saved places screen."),
    "open_navigation": ("Navigate to the hardware store", "Hands off to your maps app.", "Fleet > Saved places screen, Navigate button on each place."),
    "show_agenda_modal": ("Show me my agenda", "Pops up today's due items - reminders, appointments, anything dated today - without leaving where you are.", "Calendar tab, today's day view shows the same thing directly."),
    "show_generated_view": ("Show me my grocery spend by month", "Builds a one-off chart or total for a niche money question no screen already covers. It only ever picks what to look up - every number on screen comes from your own real data, never from a guess.", "Meters tab, the Ask section - pick source, aggregation, window and grouping by tapping."),

    # --- The cars ---
    "get_codes": ("Any trouble codes?", "Reads fault codes stored in the car right now.", "Fleet tab, trouble-code panel."),
    "diagnose_codes": ("What's wrong with the car?", "Digs into the stored codes and explains them. Takes a few seconds.", "Fleet tab, code diagnosis drilldown."),
    "clear_codes": ("Clear the codes", "Clears stored fault codes. Asks you to confirm first.", "Fleet tab, clear-codes action, confirm-gated."),
    "get_code_history": ("Has that code come back before?", "The history of faults this car has thrown.", "Fleet tab, code history view."),
    "triage_symptom": ("It's making a whining noise on turns", "Describe how the car is behaving and it works from that, not from a code.", "Voice only - a free-text symptom description has no form equivalent."),
    "check_readiness": ("Is it ready for emissions?", "Whether the car's self-tests have run - the emissions readiness monitors.", "Fleet tab, readiness/emissions panel."),
    "check_cold_start": ("Did it do a cold start test?", "Whether the cold-start monitor has completed.", "Fleet tab, readiness panel."),
    "get_vehicle_data": ("What's the coolant temperature?", "Live readings from the OBD dongle.", "Fleet tab, live telemetry rows."),
    "read_vehicle_sensor": ("What's the oil temp?", "Reads one specific sensor by name.", "Fleet tab, telemetry rows."),
    "get_health": ("How's the car doing?", "An overall health summary - codes, temperatures, readiness.", "Fleet tab, health summary panel."),
    "get_mpg": ("What am I getting to the gallon?", "Fuel economy from recorded drives.", "Fleet tab, fuel economy view."),
    "get_trend": ("Is the voltage getting worse?", "How a reading has moved over time.", "Fleet tab, trend drilldown."),
    "get_specs": ("What oil does it take?", "Factory specs for the car - engine, drivetrain, fluids.", "Fleet > Vehicle specs screen."),
    "lookup_vin": ("Look up this VIN", "Decodes a VIN into make, model and year.", "Fleet tab, add/register-a-car flow's VIN lookup."),
    "check_recalls": ("Any recalls on it?", "Open safety recalls, from the NHTSA database.", "Fleet tab, recalls panel."),
    "get_next_service": ("What's due next?", "The next maintenance item, by miles or by date.", "Fleet tab, next-due panel."),
    "ask_maintenance": ("When did I last do the brakes?", "Questions about your service history.", "Fleet > Service history screen."),
    "log_service": ("I changed the oil today", "Records a service you just did.", "Fleet > Service history screen - marking an item done now writes the service record and its cost."),
    "log_past_service": ("I did the plugs back in June", "Records a service from the past.", "Fleet > Service history screen, log a past service."),
    "set_maintenance_interval": ("Set the oil change to every 5000 miles", "Changes how often an item is due.", "Fleet tab, a maintenance item's interval editor."),
    "set_odometer": ("The odometer reads 148,200", "Updates the mileage, which keeps due-dates honest.", "Fleet tab, odometer field."),
    "log_build_entry": ("Log that I fitted new plugs, forty quid", "Records a build or modification, with cost.", "Fleet > Build sheet screen."),
    "list_build_history": ("What have I done to this car?", "Reads back the build history.", "Fleet > Build sheet screen."),
    "register_car": ("Add my Jeep", "Adds a car to the fleet.", "Fleet tab, add-a-car flow."),
    "register_vehicle": ("Register this vehicle", "Registers a vehicle by VIN or details.", "Fleet tab, add-a-car flow (VIN path)."),
    "manage_vehicle": ("Rename the Jeep to the XJ", "Renames, updates or removes a car.", "Fleet tab, a car row's edit/rename action."),
    "list_vehicles": ("What cars do I have?", "Lists your fleet.", "Fleet tab, car list."),
    "ask_fleet": ("Ask the fleet advisor about my tyres", "Sends a harder car question to the fleet specialist.", "Is itself a read-only dispatcher; the fleet screens above already show what it would answer."),

    # --- Driving ---
    "activate_garage": ("Open the garage", "Triggers the garage relay. Asks you to confirm, and never claims to know whether the door opened or closed.", "Voice only - no garage button in the app."),
    "control_volume": ("Turn it up", "Changes the volume.", "Media panel, volume control."),

    # --- Money ---
    "get_balance": ("What's my balance?", "Balance from your imported statements.", "Money tab, balances."),
    "get_spend": ("How much did I spend on fuel?", "Spending in a category or over a period.", "Money tab, spend view."),
    "get_monthly_spend": ("What did I spend last month?", "A month's total.", "Money tab, monthly spend view."),
    "list_recent_transactions": ("What have I spent lately?", "Recent transactions.", "Money tab, transaction list."),
    "categorize_transactions": ("Sort out my uncategorised spending", "Sorts transactions into categories.", "Money tab, categorize flow."),
    "set_category": ("That one's groceries", "Recategorises a transaction.", "Money tab, recategorise a transaction row."),
    "set_budget": ("Budget two hundred a month for fuel", "Sets a monthly budget for a category.", "Money tab, budget editor."),
    "list_budget_categories": ("What budgets do I have?", "Lists your budget categories.", "Money tab, budget list."),
    "log_pending_transaction": ("I just spent thirty on petrol", "Records a spend by voice before it hits the bank.", "Money tab, add-pending dialog."),
    "list_pending_transactions": ("What have I logged but not banked?", "Voice-logged spends the bank has not posted yet.", "Money tab, pending list."),
    "clear_pending_transaction": ("That petrol one has gone through", "Clears a pending spend once the bank catches up.", "Money tab, a pending row's clear action."),

    # --- Food and shopping ---
    "import_receipt": ("Scan this receipt", "Opens the camera to photograph a grocery receipt.", "Money > Pantry sub-route, scan flow."),
    "list_recent_groceries": ("What did I buy last shop?", "Recent grocery purchases.", "Money > Pantry sub-route."),
    "get_grocery_spend": ("How much on groceries this month?", "Grocery spending.", "Money > Pantry sub-route, spend panel."),
    "log_meal": ("I had chicken and rice", "Logs a meal. Calories and macros are estimates from the description, not measurements.", "Body tab, log-a-meal dialog."),
    "list_recent_meals": ("What have I eaten today?", "Recent meals.", "Body tab, meal history."),
    "get_meal_gap": ("When did I last eat?", "How long since your last logged meal.", "Body tab, meal-gap indicator."),
    "set_meal_target": ("Set my protein target to 180", "Sets a daily nutrition target.", "Body tab, meal-target dialog."),
    "ask_pantry": ("Ask the pantry what I'm low on", "Sends a harder food question to the pantry specialist.", "Is itself a read-only dispatcher; the Pantry sub-route already shows what it would answer."),

    # --- Training and sleep ---
    "log_workout_set": ("Squats, five at a hundred kilos", "Logs a set.", "Body tab, log-a-set dialog."),
    "list_recent_workouts": ("What did I train this week?", "Recent workouts.", "Body tab, workout history."),
    "get_workout_gap": ("When did I last train legs?", "How long since you trained.", "Body tab, workout-gap indicator."),
    "create_workout_plan": ("Make me a plan to get stronger", "Builds a training plan from a goal you describe.", "Voice only - a generated plan has no form equivalent; the closest hands step is reviewing and accepting one on the Goals panel."),
    "log_bodyweight": ("I'm eighty-two kilos", "Logs your bodyweight.", "Body tab, log-bodyweight dialog."),
    "log_sleep": ("I slept six hours, woke up twice", "Logs a night's sleep.", "Body tab, log-sleep dialog."),
    "list_recent_sleep": ("How have I been sleeping?", "Recent sleep.", "Body tab, sleep history."),
    "get_sleep_gap": ("When did I last log sleep?", "How long since your last sleep entry.", "Body tab, sleep-gap indicator."),
    "set_sleep_target": ("I want eight hours a night", "Sets your nightly sleep target.", "Body tab, sleep-target dialog."),
    "ask_body": ("Ask the coach about my recovery", "Sends a harder training or nutrition question to the coach.", "Is itself a read-only dispatcher; the Body tab already shows what it would answer."),

    # --- Goals and advice ---
    "set_goal": ("I want to save five grand by December", "Sets a goal.", "Goals panel, add-a-goal flow."),
    "list_goals": ("What am I working towards?", "Lists your current goals.", "Goals panel."),
    "close_goal": ("I hit the savings goal", "Closes a goal as achieved or abandoned.", "Goals panel, close-goal action."),
    "ask_goals": ("How am I doing on my goals?", "Sends a goals question to the specialist.", "Is itself a read-only dispatcher; the Goals panel already shows what it would answer."),
    "ask_advisor": ("How am I doing overall?", "Asks one of the five advisors - body, notes, fleet, money, or overall.", "Is itself a read-only dispatcher; each aspect's own screen shows the same data."),
    "accept_proposal": ("Yes, do that", "Accepts something an advisor proposed.", "Advisor proposals render with accept/dismiss buttons wherever that aspect surfaces them (Money tab today)."),
    "generate_goal_plan": ("Lose fat, gain muscle - build me a plan", "Turns a fitness/nutrition goal into a rough calorie, sleep, and workout plan to look over - a proposal, not something set up yet. Say a constraint once (\"I don't have gym access\") and it's remembered for next time.", "Goals panel, plan-review dialog."),
    "accept_goal_plan": ("Yes, set that up", "Sets up the workout part of a plan once you've agreed to the whole thing.", "Goals panel, plan-review dialog's accept action."),
    "undo_last_log": ("Undo that", "Undoes the last thing you logged.", "Body tab, a per-row delete on the same four logs (reaches the exact same delete functions)."),

    # --- Music ---
    "play_music": ("Play the Roadtrip playlist", "Plays something on Spotify.", "Media panel, search and play."),
    "control_music": ("Skip this", "Play, pause, skip, previous.", "Media panel or mini-bar, transport controls."),
    "get_music_queue": ("What's coming up?", "What is queued next.", "Media panel, queue view."),
    "browse_my_music": ("What albums have I saved?", "Looks through your own Spotify library.", "Media panel, browse view."),

    # --- Mail and calendar ---
    "search_mail": ("Any mail from the garage?", "Searches your Gmail. Mail is read and used, never stored.", "Voice only - no in-app mail search."),
    "read_mail": ("Read me that one", "Reads an email out.", "Voice only."),
    "ask_mail": ("What did the insurance email say?", "Digs through your mail to answer a question.", "Voice only - flagged as a decision conflict with ADR 0035, not yet resolved (command-center map, ruling 1)."),
    "track_package": ("Where's my package?", "Reads your most recent shipping email for the carrier, tracking number and delivery status. Always says which email and when it was sent, and that it's an estimate from the email text, not a live carrier lookup.", "Home screen, package tile."),
    "flight_status": ("When's my flight?", "Checks your calendar first, since airlines usually put flights there automatically and that's exact. Falls back to your travel-confirmation email for anything the calendar doesn't have, and says plainly that's an estimate from the email, not a confirmed schedule.", "Home screen, flight tile."),

    # --- Phone calls ---
    "answer_call": ("Answer it", "Picks up a ringing call. Add “and put it on speaker” and it does both. Cannot answer WhatsApp, Signal or Teams calls - Android does not allow it.", "Ringing-call notification, Answer action."),
    "decline_call": ("Decline it", "Rejects a ringing call.", "Ringing-call notification, Decline action."),
    "place_call": ("Call Mom", "Places an outbound call - by contact name or a number you say. Always reads the target back first (the name, or the digits) and waits for a yes before dialling, so a misheard digit never dials a stranger. Asks rather than guesses if a name matches nobody or several people. Refuses emergency numbers outright and tells you to dial them yourself. Cannot send texts - that was ruled out on purpose.", "Settings > Phone dial screen."),

    # --- Memory ---
    # --- Recordings ---
    "start_voice_note": ("Record this meeting", "Starts recording a voice note - a meeting or a thought you want kept. Refuses out loud, with nothing started, if something is already recording or the microphone is busy.", "Setup > Data & privacy > Recordings."),
    "stop_voice_note": ("Stop recording", "Stops and saves the recording. It is only saved at that point, not yet transcribed - it will not claim the note is ready to read back until transcription has actually finished.", "Setup > Data & privacy > Recordings."),
    "read_voice_note": ("What did that meeting say?", "Reads back a note's summary. The summary is written from the transcript by a model, not a verbatim account, and anything in it - a number, a date, a name - is reported as something that was said, never as confirmed fact.", "Setup > Data & privacy > Recordings."),
    "list_voice_notes": ("What recordings do I have?", "Lists recent voice notes: titles, when they were recorded, and whether each was solo or a meeting.", "Setup > Data & privacy > Recordings."),
    "remember": ("Remember that the XJ takes 5W-30", "Stores something for later.", "Settings > Memory screen, add dialog."),
    "recall_memory": ("What do you remember about the Jeep?", "Looks up what it has stored.", "Settings > Memory screen, browse stored facts."),
    "why_did_you_say_that": ("Why did you say that?", "Explains what triggered the last thing it said on its own - the rule and the fact behind it.", "Voice only - no audit-trail screen."),

    # --- Settings and control ---
    "set_companion_name": ("Call yourself Alfred", "Renames your companion.", "Settings > Assistant screen, name field."),
    "set_personality": ("Be drier about it", "Changes the personality.", "Settings > Assistant screen, personality picker."),
    "set_driver": ("My name is Kevin", "Tells it what to call you.", "Settings > Assistant screen, your name field."),

    # --- Your own trackers ---
    "list_aspects": ("What are you tracking for me?", "Lists every area it tracks - cars, money, food, and anything you have set up yourself.", "Voice only for now - a dedicated screen for user-authored trackers has not been built yet."),
    "describe_aspect": ("What can I log under Workouts?", "Reads back what fields one of your trackers has.", "Voice only for now, same as list_aspects."),
    "query_records": ("Show me my last few workouts", "Finds entries in one of your trackers, optionally narrowed by a value.", "Voice only for now, same as list_aspects."),
    "create_record": ("Log bench press, 185 for 5", "Writes one new entry into one of your trackers. Only writes what you actually said - never guesses a value.", "Voice only for now, same as list_aspects."),
    "update_record": ("Change that to 190 pounds", "Changes one or more fields on an entry you already logged.", "Voice only for now, same as list_aspects."),
    "delete_record": ("Delete that entry", "Removes one entry (into a 30-day recoverable trash) right away, or asks you to confirm first when it would remove several at once.", "Voice only for now, same as list_aspects."),
    "aspect_clerk": ("Log three sets of squats at 225, 5 reps each", "A helper that works out what to read or write across your trackers for a request with more than one step - finding something first, or logging several things from one sentence. Tells you plainly how much of it actually got written.", "Voice only for now, same as list_aspects."),
    "create_aspect": ("Start tracking my reading - book, pages, date finished", "Drafts a brand-new tracker from what you describe and reads the shape back to you. Creates nothing until you say yes.", "Voice only for now, same as list_aspects."),
    "update_aspect": ("Add a genre field to my reading tracker", "Drafts one change to a tracker you already have - a new field, a new category of entry, or a rename - and reads it back. Changes nothing until you say yes.", "Voice only for now, same as list_aspects."),
}

GROUPS = {
    "Getting started": ["get_sitrep", "get_current_time", "get_current_location", "area_info", "get_reported_crime_history", "show_app", "finish_intro", "end_conversation"],
    "Your day": ["manage_item", "manage_checklist", "read_list", "set_reminder", "read_calendar", "tag_place", "forget_place", "show_saved_places", "open_navigation", "show_agenda_modal", "show_generated_view"],
    "The cars": ["get_codes", "diagnose_codes", "clear_codes", "get_code_history", "triage_symptom", "check_readiness", "check_cold_start", "get_vehicle_data", "read_vehicle_sensor", "get_health", "get_mpg", "get_trend", "get_specs", "lookup_vin", "check_recalls", "get_next_service", "ask_maintenance", "log_service", "log_past_service", "set_maintenance_interval", "set_odometer", "log_build_entry", "list_build_history", "register_car", "register_vehicle", "manage_vehicle", "list_vehicles", "ask_fleet"],
    "Driving": ["activate_garage", "control_volume"],
    "Money": ["get_balance", "get_spend", "get_monthly_spend", "list_recent_transactions", "categorize_transactions", "set_category", "set_budget", "list_budget_categories", "log_pending_transaction", "list_pending_transactions", "clear_pending_transaction"],
    "Food and shopping": ["import_receipt", "list_recent_groceries", "get_grocery_spend", "log_meal", "list_recent_meals", "get_meal_gap", "set_meal_target", "ask_pantry"],
    "Training and sleep": ["log_workout_set", "list_recent_workouts", "get_workout_gap", "create_workout_plan", "log_bodyweight", "log_sleep", "list_recent_sleep", "get_sleep_gap", "set_sleep_target", "ask_body"],
    "Goals and advice": ["set_goal", "list_goals", "close_goal", "ask_goals", "ask_advisor", "accept_proposal", "generate_goal_plan", "accept_goal_plan", "undo_last_log"],
    "Music": ["play_music", "control_music", "get_music_queue", "browse_my_music"],
    "Mail and calendar": ["search_mail", "read_mail", "ask_mail", "track_package", "flight_status"],
    "Phone calls": ["answer_call", "decline_call", "place_call"],
    "Recordings": ["start_voice_note", "stop_voice_note", "read_voice_note", "list_voice_notes"],
    "Memory": ["remember", "recall_memory", "why_did_you_say_that"],
    "Settings and control": ["set_companion_name", "set_personality", "set_driver"],
    "Your own trackers": ["list_aspects", "describe_aspect", "query_records", "create_record", "update_record", "delete_record", "aspect_clerk", "create_aspect", "update_aspect"],
}
