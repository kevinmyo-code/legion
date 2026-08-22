"""User-facing copy for `docs/voice.html`. Hand-written; the tool LIST is not.

Each entry is `tool_name: (what you'd say, what it does)`. Keep the first short and natural - it is
an example of a real sentence, not a command syntax. Keep the second one line, plain, and honest
about limits.

**If you add a voice tool and do not add copy here, `tools/voice_guide.py` fails.** That is
deliberate: a tool nobody can discover may as well not exist, and a guide that silently omits things
teaches the wrong mental model of what the app can do.

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
    "Memory": "What it remembers about you, and why it said something.",
    "Settings and control": "Changing how it behaves, by voice.",
}

COPY = {
    # --- Getting started ---
    "get_sitrep": ("Give me a sitrep", "A status report: calendar, weather, car and a newsletter summary - whichever you have switched on."),
    "get_current_time": ("What time is it?", "The time and date where you are."),
    "get_current_location": ("Where am I?", "Your current location. Says it does not know rather than guessing when there is no GPS fix."),
    "area_info": ("Any severe weather nearby?", "Checks a live source for where you are now: severe weather (National Weather Service), earthquakes (USGS), wildfires (National Interagency Fire Center), or federal disaster declarations (FEMA). Always names its source, and needs a live GPS fix."),
    "get_reported_crime_history": ("What's the crime history around here?", "Reported offenses for the nearest police or sheriff's agency, from the FBI's Crime Data Explorer - agency-wide, roughly a year old, and voluntarily reported. It will not tell you whether somewhere is \"safe\"; nothing can answer that honestly from this data."),
    "show_app": ("Open the ledger", "Opens a screen in the app for you."),
    "end_conversation": ("Go to sleep", "Stops listening and goes quiet. \"That's all\", \"never mind\" and \"stand down\" do the same. It reads this as YOU going dormant, not as your bedtime - no goodnight."),
    "finish_intro": ("I'm done setting up", "Finishes first-run setup."),

    # --- Your day ---
    "manage_item": ("Add milk to the shopping list", "Adds, ticks off, or changes an item on any of your lists."),
    "read_list": ("What's on my shopping list?", "Reads back a list."),
    "set_reminder": ("Remind me to call the shop at four", "Sets a reminder for a time, or for when you arrive somewhere."),
    "read_calendar": ("What's on today?", "Reads your Google Calendar. Says nothing is on when nothing is - it never invents an appointment."),
    "tag_place": ("Save this as work", "Saves where you are now under a name, so reminders can trigger there."),
    "forget_place": ("Forget the old gym", "Removes a saved place."),
    "show_saved_places": ("Show my saved places", "Puts your saved places on screen."),
    "open_navigation": ("Navigate to the hardware store", "Hands off to your maps app."),

    # --- The cars ---
    "get_codes": ("Any trouble codes?", "Reads fault codes stored in the car right now."),
    "diagnose_codes": ("What's wrong with the car?", "Digs into the stored codes and explains them. Takes a few seconds."),
    "clear_codes": ("Clear the codes", "Clears stored fault codes. Asks you to confirm first."),
    "get_code_history": ("Has that code come back before?", "The history of faults this car has thrown."),
    "triage_symptom": ("It's making a whining noise on turns", "Describe how the car is behaving and it works from that, not from a code."),
    "check_readiness": ("Is it ready for emissions?", "Whether the car's self-tests have run - the emissions readiness monitors."),
    "check_cold_start": ("Did it do a cold start test?", "Whether the cold-start monitor has completed."),
    "get_vehicle_data": ("What's the coolant temperature?", "Live readings from the OBD dongle."),
    "read_vehicle_sensor": ("What's the oil temp?", "Reads one specific sensor by name."),
    "get_health": ("How's the car doing?", "An overall health summary - codes, temperatures, readiness."),
    "get_mpg": ("What am I getting to the gallon?", "Fuel economy from recorded drives."),
    "get_trend": ("Is the voltage getting worse?", "How a reading has moved over time."),
    "get_specs": ("What oil does it take?", "Factory specs for the car - engine, drivetrain, fluids."),
    "lookup_vin": ("Look up this VIN", "Decodes a VIN into make, model and year."),
    "check_recalls": ("Any recalls on it?", "Open safety recalls, from the NHTSA database."),
    "get_next_service": ("What's due next?", "The next maintenance item, by miles or by date."),
    "ask_maintenance": ("When did I last do the brakes?", "Questions about your service history."),
    "log_service": ("I changed the oil today", "Records a service you just did."),
    "log_past_service": ("I did the plugs back in June", "Records a service from the past."),
    "set_maintenance_interval": ("Set the oil change to every 5000 miles", "Changes how often an item is due."),
    "set_odometer": ("The odometer reads 148,200", "Updates the mileage, which keeps due-dates honest."),
    "log_build_entry": ("Log that I fitted new plugs, forty quid", "Records a build or modification, with cost."),
    "list_build_history": ("What have I done to this car?", "Reads back the build history."),
    "register_car": ("Add my Jeep", "Adds a car to the fleet."),
    "register_vehicle": ("Register this vehicle", "Registers a vehicle by VIN or details."),
    "manage_vehicle": ("Rename the Jeep to the XJ", "Renames, updates or removes a car."),
    "list_vehicles": ("What cars do I have?", "Lists your fleet."),
    "ask_fleet": ("Ask the fleet advisor about my tyres", "Sends a harder car question to the fleet specialist."),

    # --- Driving ---
    "activate_garage": ("Open the garage", "Triggers the garage relay. Asks you to confirm, and never claims to know whether the door opened or closed."),
    "control_volume": ("Turn it up", "Changes the volume."),

    # --- Money ---
    "import_statement": ("Import my bank statement", "Opens the statement importer."),
    "get_balance": ("What's my balance?", "Balance from your imported statements."),
    "get_spend": ("How much did I spend on fuel?", "Spending in a category or over a period."),
    "get_monthly_spend": ("What did I spend last month?", "A month's total."),
    "list_recent_transactions": ("What have I spent lately?", "Recent transactions."),
    "categorize_transactions": ("Sort out my uncategorised spending", "Sorts transactions into categories."),
    "set_category": ("That one's groceries", "Recategorises a transaction."),
    "set_budget": ("Budget two hundred a month for fuel", "Sets a monthly budget for a category."),
    "list_budget_categories": ("What budgets do I have?", "Lists your budget categories."),
    "log_pending_transaction": ("I just spent thirty on petrol", "Records a spend by voice before it hits the bank."),
    "list_pending_transactions": ("What have I logged but not banked?", "Voice-logged spends the bank has not posted yet."),
    "clear_pending_transaction": ("That petrol one has gone through", "Clears a pending spend once the bank catches up."),

    # --- Food and shopping ---
    "import_receipt": ("Scan this receipt", "Opens the camera to photograph a grocery receipt."),
    "manage_grocery": ("Add eggs to the pantry", "Adds or updates something in the pantry."),
    "list_recent_groceries": ("What did I buy last shop?", "Recent grocery purchases."),
    "get_grocery_spend": ("How much on groceries this month?", "Grocery spending."),
    "log_meal": ("I had chicken and rice", "Logs a meal. Calories and macros are estimates from the description, not measurements."),
    "list_recent_meals": ("What have I eaten today?", "Recent meals."),
    "get_meal_gap": ("When did I last eat?", "How long since your last logged meal."),
    "set_meal_target": ("Set my protein target to 180", "Sets a daily nutrition target."),
    "ask_pantry": ("Ask the pantry what I'm low on", "Sends a harder food question to the pantry specialist."),

    # --- Training and sleep ---
    "log_workout_set": ("Squats, five at a hundred kilos", "Logs a set."),
    "list_recent_workouts": ("What did I train this week?", "Recent workouts."),
    "get_workout_gap": ("When did I last train legs?", "How long since you trained."),
    "create_workout_plan": ("Make me a plan to get stronger", "Builds a training plan from a goal you describe."),
    "log_bodyweight": ("I'm eighty-two kilos", "Logs your bodyweight."),
    "log_sleep": ("I slept six hours, woke up twice", "Logs a night's sleep."),
    "list_recent_sleep": ("How have I been sleeping?", "Recent sleep."),
    "get_sleep_gap": ("When did I last log sleep?", "How long since your last sleep entry."),
    "set_sleep_target": ("I want eight hours a night", "Sets your nightly sleep target."),
    "ask_body": ("Ask the coach about my recovery", "Sends a harder training or nutrition question to the coach."),

    # --- Goals and advice ---
    "set_goal": ("I want to save five grand by December", "Sets a goal."),
    "list_goals": ("What am I working towards?", "Lists your current goals."),
    "close_goal": ("I hit the savings goal", "Closes a goal as achieved or abandoned."),
    "ask_goals": ("How am I doing on my goals?", "Sends a goals question to the specialist."),
    "ask_advisor": ("How am I doing overall?", "Asks one of the five advisors - body, notes, fleet, money, or overall."),
    "accept_proposal": ("Yes, do that", "Accepts something an advisor proposed."),
    "generate_goal_plan": ("Lose fat, gain muscle - build me a plan", "Turns a fitness/nutrition goal into a rough calorie, sleep, and workout plan to look over - a proposal, not something set up yet. Say a constraint once (\"I don't have gym access\") and it's remembered for next time."),
    "accept_goal_plan": ("Yes, set that up", "Sets up the workout part of a plan once you've agreed to the whole thing."),
    "undo_last_log": ("Undo that", "Undoes the last thing you logged."),

    # --- Music ---
    "play_music": ("Play the Roadtrip playlist", "Plays something on Spotify."),
    "control_music": ("Skip this", "Play, pause, skip, previous."),
    "get_music_queue": ("What's coming up?", "What is queued next."),
    "browse_my_music": ("What albums have I saved?", "Looks through your own Spotify library."),

    # --- Mail and calendar ---
    "search_mail": ("Any mail from the garage?", "Searches your Gmail. Mail is read and used, never stored."),
    "read_mail": ("Read me that one", "Reads an email out."),
    "ask_mail": ("What did the insurance email say?", "Digs through your mail to answer a question."),

    # --- Phone calls ---
    "answer_call": ("Answer it", "Picks up a ringing call. Add “and put it on speaker” and it does both. Cannot answer WhatsApp, Signal or Teams calls - Android does not allow it."),
    "decline_call": ("Decline it", "Rejects a ringing call."),

    # --- Memory ---
    "remember": ("Remember that the XJ takes 5W-30", "Stores something for later."),
    "recall_memory": ("What do you remember about the Jeep?", "Looks up what it has stored."),
    "why_did_you_say_that": ("Why did you say that?", "Explains what triggered the last thing it said on its own - the rule and the fact behind it."),

    # --- Settings and control ---
    "set_companion_name": ("Call yourself Alfred", "Renames your companion."),
    "set_personality": ("Be drier about it", "Changes the personality."),
    "set_driver": ("My name is Kevin", "Tells it what to call you."),
}

GROUPS = {
    "Getting started": ["get_sitrep", "get_current_time", "get_current_location", "area_info", "get_reported_crime_history", "show_app", "finish_intro", "end_conversation"],
    "Your day": ["manage_item", "read_list", "set_reminder", "read_calendar", "tag_place", "forget_place", "show_saved_places", "open_navigation"],
    "The cars": ["get_codes", "diagnose_codes", "clear_codes", "get_code_history", "triage_symptom", "check_readiness", "check_cold_start", "get_vehicle_data", "read_vehicle_sensor", "get_health", "get_mpg", "get_trend", "get_specs", "lookup_vin", "check_recalls", "get_next_service", "ask_maintenance", "log_service", "log_past_service", "set_maintenance_interval", "set_odometer", "log_build_entry", "list_build_history", "register_car", "register_vehicle", "manage_vehicle", "list_vehicles", "ask_fleet"],
    "Driving": ["activate_garage", "control_volume"],
    "Money": ["import_statement", "get_balance", "get_spend", "get_monthly_spend", "list_recent_transactions", "categorize_transactions", "set_category", "set_budget", "list_budget_categories", "log_pending_transaction", "list_pending_transactions", "clear_pending_transaction"],
    "Food and shopping": ["import_receipt", "manage_grocery", "list_recent_groceries", "get_grocery_spend", "log_meal", "list_recent_meals", "get_meal_gap", "set_meal_target", "ask_pantry"],
    "Training and sleep": ["log_workout_set", "list_recent_workouts", "get_workout_gap", "create_workout_plan", "log_bodyweight", "log_sleep", "list_recent_sleep", "get_sleep_gap", "set_sleep_target", "ask_body"],
    "Goals and advice": ["set_goal", "list_goals", "close_goal", "ask_goals", "ask_advisor", "accept_proposal", "generate_goal_plan", "accept_goal_plan", "undo_last_log"],
    "Music": ["play_music", "control_music", "get_music_queue", "browse_my_music"],
    "Mail and calendar": ["search_mail", "read_mail", "ask_mail"],
    "Phone calls": ["answer_call", "decline_call"],
    "Memory": ["remember", "recall_memory", "why_did_you_say_that"],
    "Settings and control": ["set_companion_name", "set_personality", "set_driver"],
}
