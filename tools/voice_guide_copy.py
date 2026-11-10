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
    "LEGION is a voice assistant for the phone. Tap the mic, or say \"hey\" and your companion's name if the wake "
    "phrase is switched on, then just talk. It waits for you to finish a thought rather than cutting in at "
    "the first pause, and on the phone's own speaker you can talk over it to stop it. Say \"that will be "
    "all\" when you are done. It looks "
    "after cars, money, food, training, notes and mail - and it will tell you when it cannot do "
    "something rather than guessing."
)

GROUP_BLURBS = {
    "Getting started": "The first things worth trying.",
    "Your day": "Calendar, lists, reminders and the round-up.",
    "The cars": "Diagnostics, maintenance and history. Most of it needs the OBD dongle plugged in.",
    "Driving": "Things that only make sense while you are actually out.",
    "Money": "Bank feed, statements, budgets and spending. Figures come from your linked bank's own feed or your imported statements.",
    "Food and shopping": "Groceries, receipts and meals.",
    "Training and sleep": "Workouts, bodyweight and rest.",
    "Goals and advice": "Longer-term things, and the per-aspect advisors.",
    "Music": "Spotify, mostly. Needs it connected in Setup.",
    "Mail and calendar": "Needs a Google account connected.",
    "The web": "LEGION's own browser. Sites you sign in to stay signed in; saved passwords come from Android's autofill.",
    "Books": "EPUB, PDF and CBZ files from the folder you picked on HOME. Reading and listening stay on the phone.",
    "Claude session": "Claude Code on your own laptops, for what the phone can't do. Nothing goes unless you name it or say yes.",
    "LEGION itself": "Updates to the app, published by you and installed only on your yes.",
    "Seeing": "The phone camera, shown to the assistant while you are parked. Nothing it sees is kept.",
    "Clock": "Alarms, timers, the stopwatch and bedtime. All on the phone, works offline.",
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
    "get_sitrep": ("Give me my brief", "Your daily brief, built by your server at 5am: Canvas tasks due this week, money, the car, and tech news with its sources, then today's calendar and the weather from your phone, then a summary of your newsletters if you have them switched on. Ask \"what's the news today?\" for just the news. If the server can't be reached it reads the copy saved on your phone and says how old it is.", "The Brief button on the Home screen, beside the month name (and Brief in matrix mode's list) opens the same brief, with a Refresh button."),
    "get_current_time": ("What time is it?", "The time and date where you are.", "Voice only - your phone's own clock and status bar are the hands equivalent."),
    "get_current_location": ("Where am I?", "Your current location. Says it does not know rather than guessing when there is no GPS fix.", "Fleet > Saved places screen shows current location at the top."),
    "area_info": ("Any severe weather nearby?", "Checks a live source for where you are now: severe weather (National Weather Service), earthquakes (USGS), wildfires (National Interagency Fire Center), or federal disaster declarations (FEMA). Always names its source, and needs a live GPS fix.", "Home screen, area card (weather/quake/wildfire/disaster alerts and air quality)."),
    "get_reported_crime_history": ("What's the crime history around here?", "Reported offenses for the nearest police or sheriff's agency, from the FBI's Crime Data Explorer - agency-wide, roughly a year old, and voluntarily reported. It will not tell you whether somewhere is \"safe\"; nothing can answer that honestly from this data.", "Voice only."),
    "show_app": ("Open the ledger", "Opens a screen in the app for you.", "Is itself navigation - every screen it can open is also reachable directly from the app's own tabs."),
    "end_conversation": ("That will be all", "Stops listening and goes quiet. \"That's all\", \"never mind\" and \"stand down\" do the same. It reads this as YOU going dormant, not as your bedtime - no goodnight.", "Voice only - closing the app or its notification is the nearest hands equivalent."),
    "finish_intro": ("I'm done setting up", "Finishes first-run setup.", "Onboarding screen's own final step."),

    # --- Your day ---
    "manage_item": ("Remind me to renew my registration next month", "Adds, ticks off, reschedules, or removes a REMINDER - one with a due date/time, a place trigger, or a repeat. A plain to-do with none of those is a checklist line instead.", "Calendar tab, tap a day then tap a reminder row to edit its time, repeat, place, or remove it."),
    "manage_checklist": ("Tick off squats on bio", "Creates and manages your own reusable checklists - \"bio\", \"morning routine\", plain to-dos too - each optionally daily or weekly, each line optionally a number against a target instead of a plain tick. Your grocery/shopping list and your plain to-do list are both checklists, named \"Groceries\" and \"Todo\".", "Meters screen's LISTS pane - the checklists screen."),
    "claude_session": ("Use Claude on my Alienware to find the AW4 wiring PDF in my Downloads", "Sends a task to the Claude session on your Alienware or work laptop, but only when you name Claude or say yes to the offer; it offers when its own tools can't do something. It says whether the machine is on or, if it's off, since when, and that the task is queued. It never calls a task done when it was only sent. Ask what your Claude session is doing to hear each machine, the running tasks and their current step, the results, and the open Claude Code sessions, the ones waiting on you first. When a task finishes you hear the result (or get a notification), and if it asks something it listens a few seconds for your yes; a file it found is named with its size and only comes to your phone, into Downloads, if you say yes. Ask for files by name (\"find the scanned form on my PC and send it to my phone\") and they come straight to the phone and open there, with Share for WhatsApp or anything else; the engine deletes its copy once the phone has it. More than 20 files or 100 MB, or not what you named, and it asks first. Answer its question with a yes, or follow up (\"now email it to me\"). Ask for research written up (\"make me an itinerary for a day trip into Atlanta\") and it offers to have the Alienware make it as a page: when it's ready it asks \"want me to open it?\" and a yes opens it in LEGION's browser. The work laptop sends summaries only, never files. \"Stop telling me about Claude tasks\" turns the announcements off; \"tell me about Claude tasks again\" turns them back on.", "Home, More, Claude session: machines, send a task, answer a question, Send to phone, the interactive sessions, and Open page on a finished task, Files to my phone in the send box and Open files on a task that brought some, the switch for announcements (also Settings, Proactive speech, Claude tasks); tapping a page's or files' notification opens it."),
    "bought_log": ("When did we last buy shampoo?", "The household's shared bought log. Log something you bought (today, or on a date you say, with an optional store, price and note, and private if you ask), ask when you last bought something, or hear the newest entries. It answers with the exact entry it found and its date, and who logged it. No match means there is no record, not that you never bought it. A price is only what someone typed, never checked against the bank. If the engine can't be reached it says nothing was logged. Ticking a line on Groceries logs a purchase too.", "Lists, Bought log button: search 'When did we last buy...?' or log something by hand. A Groceries item shows 'last bought'."),
    "get_last_ticked": ("When did I last buy toothpaste?", "Looks back through your checklist tick history for a matching line, across every checklist you've ever made - even one you've since deleted. It only knows what you TICKED, never what you bought: no price, nothing verified, and no record just means nothing was ticked, not that you never bought it.", "Checklists screen, tap a line to see when it was last ticked."),
    "read_list": ("What have I got coming up?", "Reads back your open reminders, soonest due first, each with its date, place or repeat.", "Calendar tab, tap a day to see that day's reminders."),
    "set_reminder": ("Remind me to grab my gym bag when I get to the gym", "Sets a reminder tied to a saved place, so it comes up when you next arrive there.", "Partial - Calendar's day view shows and edits a place-triggered reminder, but there is no add-a-new-reminder dialog by hand."),
    "complete_task": ("Mark my math assignments done", "Marks calendar tasks (assignments, quizzes, homework) done, or un-marks them. If more than one task fits it changes nothing and asks which, unless you said all of them. It reports only what it actually changed, and says so when a change is saved on the phone waiting for the server.", "Calendar tab, tap a task's tick box."),
    "read_calendar": ("What's on today?", "Reads LEGION's own calendar, the only one there is. Says nothing is on when nothing is - it never invents an appointment.", "Calendar tab's month grid and day view, and Home's next-event tile."),
    "tag_place": ("Save this place as Katie House, its address is 123 Main St, Katy", "Saves a place under a name, so reminders can trigger there and you can navigate to it. Give an address and it looks it up and reads back the address it actually saved; if the address matches several places or none, it saves nothing and says so. Without an address it saves where you are now and looks up that spot's address (if the lookup fails it still saves the spot and says the address is unknown). Reusing a name that already points somewhere else asks you first.", "Fleet > Saved places: 'Save by address' (pick from the matches) or 'Tag current location'."),
    "rename_place": ("Rename home to Katie House", "Gives a saved place a new name and keeps its location and address. Reminders tied to it move with it. Refuses if a place already has the new name.", "Fleet > Saved places, Rename on the place's row."),
    "forget_place": ("Forget the old gym", "Removes a saved place, but only after telling you what would be lost and hearing you say yes.", "Fleet > Saved places screen, delete behind a confirm."),
    "show_saved_places": ("Show my saved places", "Puts your saved places on screen.", "Is itself the Fleet > Saved places screen."),
    "show_agenda_modal": ("Show me my agenda", "Pops up today's due items - reminders, appointments, anything dated today - without leaving where you are.", "Calendar tab, today's day view shows the same thing directly."),
    "show_generated_view": ("Show me my grocery spend by month", "Builds a one-off chart or total for a niche money question no screen already covers. It only ever picks what to look up - every number on screen comes from your own real data, never from a guess.", "Meters tab, the Ask section - pick source, aggregation, window and grouping by tapping."),

    # --- Navigation (mapbox-nav ticket 11) ---
    "navigate": ("Take me to the hardware store", "Starts turn-by-turn directions inside LEGION, on its own map, with each turn spoken in one steady voice. It looks in your saved places, calendar and contacts first, then on the map. If several places could match it names the best one with its distance and waits for your yes before going. Say \"show me the routes\" to look without starting. It says so plainly when it could not find the place or no route came back.", "Fleet > Navigate: type the place, pick a route, tap Start. Every saved place also has a Navigate button."),
    "change_trip": ("Add a stop at a gas station", "Changes the trip you are on: add or drop a stop, avoid tolls, highways or ferries, take the other route, mute or unmute the spoken turns (never the assistant itself), show the whole route or follow you again. If the new route cannot include the change it says the trip is unchanged.", "The navigation screen: the Stops, No tolls, Routes and Mute tiles and the overview button. Avoiding highways or ferries is voice only."),
    "trip_status": ("How long until I get there?", "Reads the live trip: time left, distance left, arrival time, the next turn, the road you are on, the speed limit and traffic. With no trip it says it is not navigating, and a value the map does not have, like a speed limit on a road with none posted, it says it does not know.", "The navigation screen's turn banner and trip sheet show the same numbers."),
    "end_trip": ("Stop navigating", "Ends the trip, or clears a route you were only previewing. Says so if nothing was running.", "The navigation screen's End button."),

    # --- The cars ---
    "get_codes": ("Any trouble codes?", "Reads fault codes stored in the car right now, with the freeze frame saved for each, and pops up the codes card.", "Fleet tab, trouble-code panel."),
    "diagnose_codes": ("What's wrong with the car?", "Digs into the stored codes and explains them. Takes a few seconds.", "Fleet tab, code diagnosis drilldown."),
    "clear_codes": ("Clear the codes", "Clears stored fault codes. Asks you to confirm first and waits for a plain yes; anything else is not a yes.", "Fleet tab, clear-codes action, confirm-gated."),
    "get_code_history": ("Show me the freeze frames", "The history of faults this car has thrown, each with its freeze frame (engine speed, coolant, speed, load, fuel trims and more, or 'no snapshot was saved'). A codes card pops up on screen; tap a code to see its snapshot.", "Fleet tab, Faults page, 'Codes card with freeze frames'."),
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
    "check_recalls": ("Any recalls on the Jeep?", "Open safety recalls, from the NHTSA database. If the car's year, make or model isn't saved, say them ('it's a 1998 Jeep Cherokee') and it checks anyway, then asks before saving them to the car.", "Fleet tab, recalls panel."),
    "get_next_service": ("When's the Jeep due for service?", "The next maintenance item, by miles or by date, for the car you name, or the car you're connected to or last picked. With several cars and none named it asks which; a car with no schedule set up is told so.", "Fleet tab, next-due panel."),
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
    "ask_engine": ("What does the engine say is due this week?", "Asks your household server directly for records the phone does not keep itself - what is due, checklists, places, body, ledger, pantry and fleet history - and can add or change them. If the server is unreachable it says nothing was read or written, never that you have nothing.", "A dispatcher onto data your own screens already show (Calendar, checklists, Body, Ledger, Pantry, Fleet, places, recordings); two internal sync tables with no screen are refused."),
    "ask_fleet": ("Ask the fleet advisor about my tyres", "Sends a harder car question to the fleet specialist.", "Is itself a read-only dispatcher; the fleet screens above already show what it would answer."),

    # --- Driving ---
    "activate_garage": ("Open the garage", "Triggers the garage relay. Asks you to confirm, and never claims to know whether the door opened or closed.", "Voice only - no garage button in the app."),
    "control_volume": ("Turn it up", "Changes the volume.", "Media panel, volume control."),

    # --- Money ---
    "get_balance": ("What's my balance?", "Balance from your imported statements.", "Money tab, balances."),
    "get_spend": ("How much have I spent on the car?", "What the car's build sheet says you spent on it, in total or for one category such as mods or maintenance. Not your bank spending: for that, ask what you spent this month.", "Fleet > Build sheet screen."),
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
    "log_meal": ("Log my usual breakfast", "Logs a meal. Name a saved meal and its saved estimate is logged; otherwise calories and macros are estimates from the description, not measurements.", "Body tab, log-a-meal dialog; saved meals on the web Body page."),
    "list_recent_meals": ("What have I eaten today?", "Recent meals.", "Body tab, meal history."),
    "get_meal_gap": ("How am I doing on calories today?", "Today's logged calories and protein against your target, as estimates. Says so when nothing is logged yet, never zero.", "Body tab, intake panel."),
    "set_meal_target": ("Set my protein target to 180", "Sets a daily calorie or protein target you say as your own number. Calories alone take protein from the rule. To lock in the plan you made with the assistant, say \"lock it in\" instead.", "Body tab, meal-target dialog."),
    "ask_pantry": ("Ask the pantry what I'm low on", "Sends a harder food question to the pantry specialist.", "Is itself a read-only dispatcher; the Pantry sub-route already shows what it would answer."),

    # --- Training and sleep ---
    "log_workout_set": ("Log bench 135 for 8", "Ticks that set in today's workout; anything not in today's workout is logged as a standalone set.", "Body tab, today's workout, tick the set."),
    "list_recent_workouts": ("What did I train this week?", "Recent workouts.", "Body tab, workout history."),
    "get_workout_gap": ("How's my training this week?", "Workouts finished this week against the three your programme plans.", "Body tab, This week card."),
    "get_todays_workout": ("What's my workout today?", "Today's workout from your programme: each exercise as sets x reps at a weight, and why a weight went up or held. The numbers come from the programme's rules, never made up.", "Body tab, Today's workout card."),
    "get_workout_programme": ("What's on Day B? What's my next session?", "Your whole programme: every day with each exercise as sets x reps at a weight, sessions a week, and the next sessions in order. Days are flexible, so there are no fixed weekdays. The numbers come from the programme's rules, never made up.", "Body tab: the workout card shows the next session's exercises and This week your sessions done."),
    "suggest_meal": ("What should I eat?", "What's left today in calories and protein, and up to five options from your saved meals and groceries you bought recently, best fit first. Every figure is an estimate. Say \"log that\" to log one.", "Voice only, by Kevin's ruling (fewer screens); the Body tab's Today card shows what's eaten and what's left."),
    "get_exercise_alternatives": ("What can I do instead of bench?", "The alternatives to an exercise in your programme, and which ones your equipment and joints rule out. Pick one and Alfred proposes the change for you to accept.", "Voice only, by Kevin's ruling (fewer screens); a proposed change shows on the workout card with Accept and Dismiss."),
    "log_bodyweight": ("I'm eighty-two kilos", "Logs your bodyweight.", "Body tab, log-bodyweight dialog."),
    "log_sleep": ("I slept six hours, woke up twice", "Logs a night's sleep.", "Body tab, log-sleep dialog."),
    "list_recent_sleep": ("How have I been sleeping?", "Recent sleep.", "Body tab, sleep history."),
    "get_sleep_gap": ("Did I hit my sleep target?", "Last night's logged sleep against your nightly target. Says so when nothing is logged.", "Body tab, sleep panel."),
    "set_sleep_target": ("I want eight hours a night", "Sets your nightly sleep target.", "Body tab, sleep-target dialog."),
    "ask_body": ("How much should I eat to lose fat?", "Answers questions about your food, training, sleep, weight trend and calorie and protein estimates. Read-only.", "Is itself a read-only dispatcher; the Body tab and the web Body page show what it would answer."),
    "get_energy_estimate": ("What's my maintenance?", "Your maintenance calories, a daily target for the rate you chose, and protein, all estimates, and what they are based on: the formula, or your own weight trend. Names anything it still needs, like your height. A suggested change is only offered, never applied.", "Web Body page, Energy card (the phone's Body screen gets one in the revamp)."),
    "get_weight_trend": ("Am I losing weight?", "Your smoothed weight trend, how fast it is moving each week, how far to your goal, and your waist. The first week's water drop is flagged, not projected.", "Web Body page, trend chart; Body tab, mass panel."),
    "list_saved_meals": ("What are my saved meals?", "Your saved meals and their estimated calories and protein. Say a name to log one.", "Web Body page, saved meals."),

    # --- Goals and advice ---
    "set_goal": ("I want to save five grand by December", "Sets a goal.", "Goals panel, add-a-goal flow."),
    "list_goals": ("What am I working towards?", "Lists your current goals.", "Goals panel."),
    "close_goal": ("I hit the savings goal", "Closes a goal as achieved or abandoned.", "Goals panel, close-goal action."),
    "ask_goals": ("How am I doing on my goals?", "Sends a goals question to the specialist.", "Is itself a read-only dispatcher; the Goals panel already shows what it would answer."),
    "ask_advisor": ("How am I doing overall?", "Asks one of the five advisors - body, notes, fleet, money, or overall.", "Is itself a read-only dispatcher; each aspect's own screen shows the same data."),
    "accept_proposal": ("Yes, do that", "Accepts something an advisor proposed.", "Advisor proposals render with accept/dismiss buttons wherever that aspect surfaces them (Money tab today)."),
    "generate_goal_plan": ("Lose fat, gain muscle - build me a plan", "Turns a fitness/nutrition goal into a rough calorie, sleep, and workout plan to look over - a proposal, not something set up yet. Say a constraint once (\"I don't have gym access\") and it's remembered for next time.", "Goals panel, plan-review dialog."),
    "accept_goal_plan": ("Yes, set that up", "Sets up your three-day workout programme once you've agreed to the whole plan; its numbers come from the programme's rules.", "Goals panel, plan-review dialog's accept action."),
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
    "memory": ("Remember that the XJ takes 5W-30 / What do you remember about the Jeep? / Forget that", "Saves, looks up, lists and forgets what it remembers about you. Memories live on your engine, private to you unless you say the household may know one, and the web desk sees the same ones. Forget deletes it for good. With no connection it answers from the phone's last copy and says so, and it won't save or forget anything until it's back online. ID numbers are refused and pointed to the ID vault.", "Settings > Data and privacy > Memory: search, add, edit, share or make private, and Forget (asks first, deletes for good)."),
    "show_vault_item": ("Show my passport number", "Opens the ID vault on your phone at that item. You unlock it with your fingerprint and read it yourself; the assistant never sees, says or keeps the number. Copy puts it on the clipboard for 60 seconds.", "Settings > Data and privacy > ID vault: add, edit, show and delete items behind your fingerprint."),
    "why_did_you_say_that": ("Why did you say that?", "Explains what triggered the last thing it said on its own - the rule and the fact behind it.", "Voice only - no audit-trail screen."),

    # --- Settings and control ---
    "set_companion_name": ("Call yourself Alfred", "Renames your companion.", "Settings > Assistant, then the assistant's own screen: name field."),
    "set_personality": ("Be drier about it", "Changes the personality.", "Voice only - the assistant's settings screen edits its name and voice, not its personality."),
    "set_driver": ("My name is Kevin", "Tells it what to call you.", "Voice only - no screen edits your name yet."),

    # --- Your own trackers ---
    "list_aspects": ("What are you tracking for me?", "Lists every area it tracks - cars, money, food, and anything you have set up yourself.", "Voice only for now - a dedicated screen for user-authored trackers has not been built yet."),
    "describe_aspect": ("What can I log under Workouts?", "Reads back what fields one of your trackers has.", "Voice only for now, same as list_aspects."),
    "query_records": ("Show me my last few workouts", "Finds entries in one of your trackers, optionally narrowed by a value.", "Voice only for now, same as list_aspects."),
    "create_record": ("Log bench press, 185 for 5", "Writes one new entry into one of your trackers. Only writes what you actually said - never guesses a value.", "Voice only for now, same as list_aspects."),
    "update_record": ("Change that to 190 pounds", "Changes one or more fields on an entry you already logged.", "Voice only for now, same as list_aspects."),
    "delete_record": ("Delete that entry", "Removes one entry (into a 30-day recoverable trash) right away, or asks you to confirm first when it would remove several at once.", "Voice only for now, same as list_aspects."),
    "browser": ("Find me a picture of a red XJ on stock height", "Opens LEGION's own browser on a Google search, Google Images, or a web address, and says it opened only once the page has loaded. Say 'read it' to hear the page and its numbered links and buttons, then 'tap 3', 'scroll down', 'type my email into 2', 'go back' or 'close the browser'. It never types a password: tap the field and Android's autofill offers the saved one. Before anything that pays, orders, sends, posts, books, deletes or submits a form it asks you first, and only a yes in your very next reply goes ahead. Feed sites like Instagram or YouTube are blocked the same way their apps are. To get an answer spoken rather than a page shown, just ask the question.", "Matrix home's Browser entry, or Browser in the app drawer: the address bar, back, forward, reload and close."),
    "books": ("What's the last book I read?", "Tells you the last book you were reading and where you left off, without opening it; say 'pull it up' to open it there. Also opens a named book from your Books folder in the reader ('Read me Moby-Dick'). Say 'read aloud' to have the phone's own voice read an ebook to you, offline. Listen is for ebooks only: a PDF or a comic opens to read but is not read aloud, and a CBR comic cannot be opened yet. If the folder is not set up, the book is not found, or two books fit, it says nothing was opened and why.", "HOME, Books chip: tap a book to read it, or Read and Listen on the Continue card."),
    "aspect_clerk": ("Log three sets of squats at 225, 5 reps each", "A helper that works out what to read or write across your trackers for a request with more than one step - finding something first, or logging several things from one sentence. Tells you plainly how much of it actually got written.", "Voice only for now, same as list_aspects."),
    "create_aspect": ("Start tracking my reading - book, pages, date finished", "Drafts a brand-new tracker from what you describe and reads the shape back to you. Creates nothing until you say yes.", "Voice only for now, same as list_aspects."),
    "app_update": ("Is there an update for LEGION?", "Checks whether a newer LEGION has been published, and says what it changes. The update downloads on Wi-Fi only (unless you allow mobile data) and is checked first: a file whose checksum or signing key does not match the app on your phone is thrown away, and it says so. It always asks before installing, and only your yes installs it. Asked during a conversation, it installs as soon as the conversation ends; in Matrix mode or with the car connected it won't install at all, and says why. LEGION closes for about a minute and comes back on its own. When an update is ready you get a notification with Install and Later, and Alfred mentions it once at a quiet moment.", "Settings, Updates: this phone's version, the newest one and its notes, Check now, Install, Allow mobile data, the one-time Allow installs grant, and which wording Alfred is running; or Install on the update's notification."),
    "clock": ("Wake me up at 6:30 on weekdays", "Sets, lists and cancels alarms; starts, pauses and cancels named timers and says how long is left; runs the stopwatch with laps; and turns bedtime on or off. An alarm is a plain ring over the lock screen with Dismiss, Snooze and Brief me, which starts your daily brief in one tap. Bedtime turns Quiet and the feed block on; Quiet goes off again when you dismiss the next alarm, unless you had it on already, and the feed block stays on until you turn it off. Works offline.", "Tap the big clock on Matrix home, or open Clock from the app drawer: Alarms, Timers, Stopwatch and Bedtime tabs; Pause, Cancel, Snooze and Dismiss on the notifications."),
    "look": ("Look at this", "Opens the phone camera so the assistant can see what you point it at, about one picture a second, until you close it. Parked only: it refuses while the car is moving, if the phone can tell. What it says about the picture is a guess, your car's stored codes come first, and it never talks you through an unsafe repair. Nothing it sees is saved or remembered.", "Fleet screen, the Show button under the car panel: the same camera screen; Stop showing ends it."),
    "update_aspect": ("Add a genre field to my reading tracker", "Drafts one change to a tracker you already have - a new field, a new category of entry, or a rename - and reads it back. Changes nothing until you say yes.", "Voice only for now, same as list_aspects."),
}

GROUPS = {
    "Getting started": ["get_sitrep", "get_current_time", "get_current_location", "area_info", "get_reported_crime_history", "show_app", "finish_intro", "end_conversation"],
    "Your day": ["manage_item", "manage_checklist", "get_last_ticked", "read_list", "set_reminder", "read_calendar", "complete_task", "tag_place", "rename_place", "forget_place", "show_saved_places", "show_agenda_modal", "show_generated_view", "ask_engine"],
    "The cars": ["get_codes", "diagnose_codes", "clear_codes", "get_code_history", "triage_symptom", "check_readiness", "check_cold_start", "get_vehicle_data", "read_vehicle_sensor", "get_health", "get_mpg", "get_trend", "get_specs", "lookup_vin", "check_recalls", "get_next_service", "ask_maintenance", "log_service", "log_past_service", "set_maintenance_interval", "set_odometer", "log_build_entry", "list_build_history", "get_spend", "register_car", "register_vehicle", "manage_vehicle", "list_vehicles", "ask_fleet"],
    "Driving": ["navigate", "change_trip", "trip_status", "end_trip", "activate_garage", "control_volume"],
    "Money": ["get_balance", "get_monthly_spend", "list_recent_transactions", "categorize_transactions", "set_category", "set_budget", "list_budget_categories", "log_pending_transaction", "list_pending_transactions", "clear_pending_transaction"],
    "Food and shopping": ["bought_log", "import_receipt", "list_recent_groceries", "get_grocery_spend", "log_meal", "list_recent_meals", "get_meal_gap", "set_meal_target", "ask_pantry"],
    "Training and sleep": ["log_workout_set", "list_recent_workouts", "get_workout_gap", "get_todays_workout", "get_workout_programme", "get_exercise_alternatives", "suggest_meal", "log_bodyweight", "log_sleep", "list_recent_sleep", "get_sleep_gap", "set_sleep_target", "get_energy_estimate", "get_weight_trend", "list_saved_meals", "ask_body"],
    "Goals and advice": ["set_goal", "list_goals", "close_goal", "ask_goals", "ask_advisor", "accept_proposal", "generate_goal_plan", "accept_goal_plan", "undo_last_log"],
    "Music": ["play_music", "control_music", "get_music_queue", "browse_my_music"],
    "Mail and calendar": ["search_mail", "read_mail", "ask_mail", "track_package", "flight_status"],
    "The web": ["browser"],
    "Books": ["books"],
    "Claude session": ["claude_session"],
    "LEGION itself": ["app_update"],
    "Seeing": ["look"],
    "Clock": ["clock"],
    "Phone calls": ["answer_call", "decline_call", "place_call"],
    "Recordings": ["start_voice_note", "stop_voice_note", "read_voice_note", "list_voice_notes"],
    "Memory": ["memory", "show_vault_item", "why_did_you_say_that"],
    "Settings and control": ["set_companion_name", "set_personality", "set_driver"],
    "Your own trackers": ["list_aspects", "describe_aspect", "query_records", "create_record", "update_record", "delete_record", "aspect_clerk", "create_aspect", "update_aspect"],
}
