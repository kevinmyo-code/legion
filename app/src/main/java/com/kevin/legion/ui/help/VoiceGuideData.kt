// GENERATED FILE - DO NOT HAND EDIT.
//
// Produced by `python tools/voice_guide.py` from `tools/voice_guide_copy.py` - the exact same
// source `docs/voice.html` is generated from (command-center ticket 09, ADR 0035's in-app hands
// path). Edit the copy there and rerun the script; a hand edit here is overwritten on the next run
// and, if it diverges first, `voice_guide.py --check` fails the build and names the drift - same
// posture as the tool-list check this file's generator already enforces.

@file:Suppress("MaxLineLength") // generated prose literals; the copy file owns the wording

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

    const val INTRO: String = "LEGION is a voice assistant for the phone. Tap the mic, or say \"hey\" and your companion's name if the wake phrase is switched on, then just talk. It waits for you to finish a thought rather than cutting in at the first pause, and on the phone's own speaker you can talk over it to stop it. Say \"that will be all\" when you are done. It looks after cars, money, food, training, notes and mail - and it will tell you when it cannot do something rather than guessing."

    val GROUPS: List<Group> = listOf(
        Group(
            title = "Getting started",
            blurb = "The first things worth trying.",
            entries = listOf(
                Entry(name = "get_sitrep", say = "Give me my brief", does = "Your daily brief, built by your server at 5am: Canvas tasks due this week, money, the car, and tech news with its sources, then today's calendar and the weather from your phone, then a summary of your newsletters if you have them switched on. Ask \"what's the news today?\" for just the news. If the server can't be reached it reads the copy saved on your phone and says how old it is.", hands = "The Brief button on the Home screen, beside the month name (and Brief in matrix mode's list) opens the same brief, with a Refresh button."),
                Entry(name = "get_current_time", say = "What time is it?", does = "The time and date where you are.", hands = "Voice only - your phone's own clock and status bar are the hands equivalent."),
                Entry(name = "get_current_location", say = "Where am I?", does = "Your current location. Says it does not know rather than guessing when there is no GPS fix.", hands = "Fleet > Saved places screen shows current location at the top."),
                Entry(name = "area_info", say = "Any severe weather nearby?", does = "Checks a live source for where you are now: severe weather (National Weather Service), earthquakes (USGS), wildfires (National Interagency Fire Center), or federal disaster declarations (FEMA). Always names its source, and needs a live GPS fix.", hands = "Home screen, area card (weather/quake/wildfire/disaster alerts and air quality)."),
                Entry(name = "get_reported_crime_history", say = "What's the crime history around here?", does = "Reported offenses for the nearest police or sheriff's agency, from the FBI's Crime Data Explorer - agency-wide, roughly a year old, and voluntarily reported. It will not tell you whether somewhere is \"safe\"; nothing can answer that honestly from this data.", hands = "Voice only."),
                Entry(name = "show_app", say = "Open the ledger", does = "Opens a screen in the app for you.", hands = "Is itself navigation - every screen it can open is also reachable directly from the app's own tabs."),
                Entry(name = "finish_intro", say = "I'm done setting up", does = "Finishes first-run setup.", hands = "Onboarding screen's own final step."),
                Entry(name = "end_conversation", say = "That will be all", does = "Stops listening and goes quiet. \"That's all\", \"never mind\" and \"stand down\" do the same. It reads this as YOU going dormant, not as your bedtime - no goodnight.", hands = "Voice only - closing the app or its notification is the nearest hands equivalent."),
            ),
        ),
        Group(
            title = "Your day",
            blurb = "Calendar, lists, reminders and the round-up.",
            entries = listOf(
                Entry(name = "manage_item", say = "Remind me to renew my registration next month", does = "Adds, ticks off, reschedules, or removes a REMINDER - one with a due date/time, a place trigger, or a repeat. A plain to-do with none of those is a checklist line instead.", hands = "Calendar tab, tap a day then tap a reminder row to edit its time, repeat, place, or remove it."),
                Entry(name = "manage_checklist", say = "Tick off squats on bio", does = "Creates and manages your own reusable checklists - \"bio\", \"morning routine\", plain to-dos too - each optionally daily or weekly, each line optionally a number against a target instead of a plain tick. Your grocery/shopping list and your plain to-do list are both checklists, named \"Groceries\" and \"Todo\".", hands = "Meters screen's LISTS pane - the checklists screen."),
                Entry(name = "get_last_ticked", say = "When did I last buy toothpaste?", does = "Looks back through your checklist tick history for a matching line, across every checklist you've ever made - even one you've since deleted. It only knows what you TICKED, never what you bought: no price, nothing verified, and no record just means nothing was ticked, not that you never bought it.", hands = "Checklists screen, tap a line to see when it was last ticked."),
                Entry(name = "read_list", say = "What have I got coming up?", does = "Reads back your open reminders, soonest due first, each with its date, place or repeat.", hands = "Calendar tab, tap a day to see that day's reminders."),
                Entry(name = "set_reminder", say = "Remind me to grab my gym bag when I get to the gym", does = "Sets a reminder tied to a saved place, so it comes up when you next arrive there.", hands = "Partial - Calendar's day view shows and edits a place-triggered reminder, but there is no add-a-new-reminder dialog by hand."),
                Entry(name = "read_calendar", say = "What's on today?", does = "Reads LEGION's own calendar, the only one there is. Says nothing is on when nothing is - it never invents an appointment.", hands = "Calendar tab's month grid and day view, and Home's next-event tile."),
                Entry(name = "complete_task", say = "Mark my math assignments done", does = "Marks calendar tasks (assignments, quizzes, homework) done, or un-marks them. If more than one task fits it changes nothing and asks which, unless you said all of them. It reports only what it actually changed, and says so when a change is saved on the phone waiting for the server.", hands = "Calendar tab, tap a task's tick box."),
                Entry(name = "tag_place", say = "Save this place as Katie House, its address is 123 Main St, Katy", does = "Saves a place under a name, so reminders can trigger there and you can navigate to it. Give an address and it looks it up and reads back the address it actually saved; if the address matches several places or none, it saves nothing and says so. Without an address it saves where you are now and looks up that spot's address (if the lookup fails it still saves the spot and says the address is unknown). Reusing a name that already points somewhere else asks you first.", hands = "Fleet > Saved places: 'Save by address' (pick from the matches) or 'Tag current location'."),
                Entry(name = "rename_place", say = "Rename home to Katie House", does = "Gives a saved place a new name and keeps its location and address. Reminders tied to it move with it. Refuses if a place already has the new name.", hands = "Fleet > Saved places, Rename on the place's row."),
                Entry(name = "forget_place", say = "Forget the old gym", does = "Removes a saved place, but only after telling you what would be lost and hearing you say yes.", hands = "Fleet > Saved places screen, delete behind a confirm."),
                Entry(name = "show_saved_places", say = "Show my saved places", does = "Puts your saved places on screen.", hands = "Is itself the Fleet > Saved places screen."),
                Entry(name = "show_agenda_modal", say = "Show me my agenda", does = "Pops up today's due items - reminders, appointments, anything dated today - without leaving where you are.", hands = "Calendar tab, today's day view shows the same thing directly."),
                Entry(name = "show_generated_view", say = "Show me my grocery spend by month", does = "Builds a one-off chart or total for a niche money question no screen already covers. It only ever picks what to look up - every number on screen comes from your own real data, never from a guess.", hands = "Meters tab, the Ask section - pick source, aggregation, window and grouping by tapping."),
                Entry(name = "ask_engine", say = "What does the engine say is due this week?", does = "Asks your household server directly for records the phone does not keep itself - what is due, checklists, places, body, ledger, pantry and fleet history - and can add or change them. If the server is unreachable it says nothing was read or written, never that you have nothing.", hands = "A dispatcher onto data your own screens already show (Calendar, checklists, Body, Ledger, Pantry, Fleet, places, recordings); two internal sync tables with no screen are refused."),
            ),
        ),
        Group(
            title = "The cars",
            blurb = "Diagnostics, maintenance and history. Most of it needs the OBD dongle plugged in.",
            entries = listOf(
                Entry(name = "get_codes", say = "Any trouble codes?", does = "Reads fault codes stored in the car right now, with the freeze frame saved for each, and pops up the codes card.", hands = "Fleet tab, trouble-code panel."),
                Entry(name = "diagnose_codes", say = "What's wrong with the car?", does = "Digs into the stored codes and explains them. Takes a few seconds.", hands = "Fleet tab, code diagnosis drilldown."),
                Entry(name = "clear_codes", say = "Clear the codes", does = "Clears stored fault codes. Asks you to confirm first and waits for a plain yes; anything else is not a yes.", hands = "Fleet tab, clear-codes action, confirm-gated."),
                Entry(name = "get_code_history", say = "Show me the freeze frames", does = "The history of faults this car has thrown, each with its freeze frame (engine speed, coolant, speed, load, fuel trims and more, or 'no snapshot was saved'). A codes card pops up on screen; tap a code to see its snapshot.", hands = "Fleet tab, Faults page, 'Codes card with freeze frames'."),
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
                Entry(name = "check_recalls", say = "Any recalls on the Jeep?", does = "Open safety recalls, from the NHTSA database. If the car's year, make or model isn't saved, say them ('it's a 1998 Jeep Cherokee') and it checks anyway, then asks before saving them to the car.", hands = "Fleet tab, recalls panel."),
                Entry(name = "get_next_service", say = "When's the Jeep due for service?", does = "The next maintenance item, by miles or by date, for the car you name, or the car you're connected to or last picked. With several cars and none named it asks which; a car with no schedule set up is told so.", hands = "Fleet tab, next-due panel."),
                Entry(name = "ask_maintenance", say = "When did I last do the brakes?", does = "Questions about your service history.", hands = "Fleet > Service history screen."),
                Entry(name = "log_service", say = "I changed the oil today", does = "Records a service you just did.", hands = "Fleet > Service history screen - marking an item done now writes the service record and its cost."),
                Entry(name = "log_past_service", say = "I did the plugs back in June", does = "Records a service from the past.", hands = "Fleet > Service history screen, log a past service."),
                Entry(name = "set_maintenance_interval", say = "Set the oil change to every 5000 miles", does = "Changes how often an item is due.", hands = "Fleet tab, a maintenance item's interval editor."),
                Entry(name = "set_odometer", say = "The odometer reads 148,200", does = "Updates the mileage, which keeps due-dates honest.", hands = "Fleet tab, odometer field."),
                Entry(name = "log_build_entry", say = "Log that I fitted new plugs, forty quid", does = "Records a build or modification, with cost.", hands = "Fleet > Build sheet screen."),
                Entry(name = "list_build_history", say = "What have I done to this car?", does = "Reads back the build history.", hands = "Fleet > Build sheet screen."),
                Entry(name = "get_spend", say = "How much have I spent on the car?", does = "What the car's build sheet says you spent on it, in total or for one category such as mods or maintenance. Not your bank spending: for that, ask what you spent this month.", hands = "Fleet > Build sheet screen."),
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
                Entry(name = "navigate", say = "Take me to the hardware store", does = "Starts turn-by-turn directions inside LEGION, on its own map, with each turn spoken in one steady voice. It looks in your saved places, calendar and contacts first, then on the map. If several places could match it names the best one with its distance and waits for your yes before going. Say \"show me the routes\" to look without starting. It says so plainly when it could not find the place or no route came back.", hands = "Fleet > Navigate: type the place, pick a route, tap Start. Every saved place also has a Navigate button."),
                Entry(name = "change_trip", say = "Add a stop at a gas station", does = "Changes the trip you are on: add or drop a stop, avoid tolls, highways or ferries, take the other route, mute or unmute the spoken turns (never the assistant itself), show the whole route or follow you again. If the new route cannot include the change it says the trip is unchanged.", hands = "The navigation screen: the Stops, No tolls, Routes and Mute tiles and the overview button. Avoiding highways or ferries is voice only."),
                Entry(name = "trip_status", say = "How long until I get there?", does = "Reads the live trip: time left, distance left, arrival time, the next turn, the road you are on, the speed limit and traffic. With no trip it says it is not navigating, and a value the map does not have, like a speed limit on a road with none posted, it says it does not know.", hands = "The navigation screen's turn banner and trip sheet show the same numbers."),
                Entry(name = "end_trip", say = "Stop navigating", does = "Ends the trip, or clears a route you were only previewing. Says so if nothing was running.", hands = "The navigation screen's End button."),
                Entry(name = "activate_garage", say = "Open the garage", does = "Triggers the garage relay. Asks you to confirm, and never claims to know whether the door opened or closed.", hands = "Voice only - no garage button in the app."),
                Entry(name = "control_volume", say = "Turn it up", does = "Changes the volume.", hands = "Media panel, volume control."),
            ),
        ),
        Group(
            title = "Money",
            blurb = "Bank feed, statements, budgets and spending. Figures come from your linked bank's own feed or your imported statements.",
            entries = listOf(
                Entry(name = "get_balance", say = "What's my balance?", does = "Balance from your imported statements.", hands = "Money tab, balances."),
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
                Entry(name = "bought_log", say = "When did we last buy shampoo?", does = "The household's shared bought log. Log something you bought (today, or on a date you say, with an optional store, price and note, and private if you ask), ask when you last bought something, or hear the newest entries. It answers with the exact entry it found and its date, and who logged it. No match means there is no record, not that you never bought it. A price is only what someone typed, never checked against the bank. If the engine can't be reached it says nothing was logged. Ticking a line on Groceries logs a purchase too.", hands = "Lists, Bought log button: search 'When did we last buy...?' or log something by hand. A Groceries item shows 'last bought'."),
                Entry(name = "import_receipt", say = "Scan this receipt", does = "Opens the camera to photograph a grocery receipt.", hands = "Money > Pantry sub-route, scan flow."),
                Entry(name = "list_recent_groceries", say = "What did I buy last shop?", does = "Recent grocery purchases.", hands = "Money > Pantry sub-route."),
                Entry(name = "get_grocery_spend", say = "How much on groceries this month?", does = "Grocery spending.", hands = "Money > Pantry sub-route, spend panel."),
                Entry(name = "log_meal", say = "Log my usual breakfast", does = "Logs a meal. Name a saved meal and its saved estimate is logged; otherwise calories and macros are estimates from the description, not measurements.", hands = "Body tab, log-a-meal dialog; saved meals on the web Body page."),
                Entry(name = "list_recent_meals", say = "What have I eaten today?", does = "Recent meals.", hands = "Body tab, meal history."),
                Entry(name = "get_meal_gap", say = "How am I doing on calories today?", does = "Today's logged calories and protein against your target, as estimates. Says so when nothing is logged yet, never zero.", hands = "Body tab, intake panel."),
                Entry(name = "set_meal_target", say = "Set my protein target to 180", does = "Sets a daily calorie or protein target you say as your own number. Calories alone take protein from the rule. To lock in the plan you made with the assistant, say \"lock it in\" instead.", hands = "Body tab, meal-target dialog."),
                Entry(name = "ask_pantry", say = "Ask the pantry what I'm low on", does = "Sends a harder food question to the pantry specialist.", hands = "Is itself a read-only dispatcher; the Pantry sub-route already shows what it would answer."),
            ),
        ),
        Group(
            title = "Training and sleep",
            blurb = "Workouts, bodyweight and rest.",
            entries = listOf(
                Entry(name = "log_workout_set", say = "Log bench 135 for 8", does = "Ticks that set in today's workout; anything not in today's workout is logged as a standalone set.", hands = "Body tab, today's workout, tick the set."),
                Entry(name = "list_recent_workouts", say = "What did I train this week?", does = "Recent workouts.", hands = "Body tab, workout history."),
                Entry(name = "get_workout_gap", say = "How's my training this week?", does = "Workouts finished this week against the three your programme plans.", hands = "Body tab, This week card."),
                Entry(name = "get_todays_workout", say = "What's my workout today?", does = "Today's workout from your programme: each exercise as sets x reps at a weight, and why a weight went up or held. The numbers come from the programme's rules, never made up.", hands = "Body tab, Today's workout card."),
                Entry(name = "get_workout_programme", say = "What's on Day B? What's my next session?", does = "Your whole programme: every day with each exercise as sets x reps at a weight, sessions a week, and the next sessions in order. Days are flexible, so there are no fixed weekdays. The numbers come from the programme's rules, never made up.", hands = "Body tab: the workout card shows the next session's exercises and This week your sessions done."),
                Entry(name = "get_exercise_alternatives", say = "What can I do instead of bench?", does = "The alternatives to an exercise in your programme, and which ones your equipment and joints rule out. Pick one and Alfred proposes the change for you to accept.", hands = "Voice only, by Kevin's ruling (fewer screens); a proposed change shows on the workout card with Accept and Dismiss."),
                Entry(name = "suggest_meal", say = "What should I eat?", does = "What's left today in calories and protein, and up to five options from your saved meals and groceries you bought recently, best fit first. Every figure is an estimate. Say \"log that\" to log one.", hands = "Voice only, by Kevin's ruling (fewer screens); the Body tab's Today card shows what's eaten and what's left."),
                Entry(name = "log_bodyweight", say = "I'm eighty-two kilos", does = "Logs your bodyweight.", hands = "Body tab, log-bodyweight dialog."),
                Entry(name = "log_sleep", say = "I slept six hours, woke up twice", does = "Logs a night's sleep.", hands = "Body tab, log-sleep dialog."),
                Entry(name = "list_recent_sleep", say = "How have I been sleeping?", does = "Recent sleep.", hands = "Body tab, sleep history."),
                Entry(name = "get_sleep_gap", say = "Did I hit my sleep target?", does = "Last night's logged sleep against your nightly target. Says so when nothing is logged.", hands = "Body tab, sleep panel."),
                Entry(name = "set_sleep_target", say = "I want eight hours a night", does = "Sets your nightly sleep target.", hands = "Body tab, sleep-target dialog."),
                Entry(name = "get_energy_estimate", say = "What's my maintenance?", does = "Your maintenance calories, a daily target for the rate you chose, and protein, all estimates, and what they are based on: the formula, or your own weight trend. Names anything it still needs, like your height. A suggested change is only offered, never applied.", hands = "Web Body page, Energy card (the phone's Body screen gets one in the revamp)."),
                Entry(name = "get_weight_trend", say = "Am I losing weight?", does = "Your smoothed weight trend, how fast it is moving each week, how far to your goal, and your waist. The first week's water drop is flagged, not projected.", hands = "Web Body page, trend chart; Body tab, mass panel."),
                Entry(name = "list_saved_meals", say = "What are my saved meals?", does = "Your saved meals and their estimated calories and protein. Say a name to log one.", hands = "Web Body page, saved meals."),
                Entry(name = "ask_body", say = "How much should I eat to lose fat?", does = "Answers questions about your food, training, sleep, weight trend and calorie and protein estimates. Read-only.", hands = "Is itself a read-only dispatcher; the Body tab and the web Body page show what it would answer."),
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
                Entry(name = "accept_goal_plan", say = "Yes, set that up", does = "Sets up your three-day workout programme once you've agreed to the whole plan; its numbers come from the programme's rules.", hands = "Goals panel, plan-review dialog's accept action."),
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
            title = "The web",
            blurb = "LEGION's own browser. Sites you sign in to stay signed in; saved passwords come from Android's autofill.",
            entries = listOf(
                Entry(name = "browser", say = "Find me a picture of a red XJ on stock height", does = "Opens LEGION's own browser on a Google search, Google Images, or a web address, and says it opened only once the page has loaded. Say 'read it' to hear the page and its numbered links and buttons, then 'tap 3', 'scroll down', 'type my email into 2', 'go back' or 'close the browser'. It never types a password: tap the field and Android's autofill offers the saved one. Before anything that pays, orders, sends, posts, books, deletes or submits a form it asks you first, and only a yes in your very next reply goes ahead. Feed sites like Instagram or YouTube are blocked the same way their apps are. To get an answer spoken rather than a page shown, just ask the question.", hands = "Matrix home's Browser entry, or Browser in the app drawer: the address bar, back, forward, reload and close."),
            ),
        ),
        Group(
            title = "Books",
            blurb = "EPUB, PDF and CBZ files from the folder you picked on HOME. Reading and listening stay on the phone.",
            entries = listOf(
                Entry(name = "books", say = "What's the last book I read?", does = "Tells you the last book you were reading and where you left off, without opening it; say 'pull it up' to open it there. Also opens a named book from your Books folder in the reader ('Read me Moby-Dick'). Say 'read aloud' to have the phone's own voice read an ebook to you, offline. Listen is for ebooks only: a PDF or a comic opens to read but is not read aloud, and a CBR comic cannot be opened yet. If the folder is not set up, the book is not found, or two books fit, it says nothing was opened and why.", hands = "HOME, Books chip: tap a book to read it, or Read and Listen on the Continue card."),
            ),
        ),
        Group(
            title = "Claude session",
            blurb = "Claude Code on your own laptops, for what the phone can't do. Nothing goes unless you name it or say yes.",
            entries = listOf(
                Entry(name = "claude_session", say = "Use Claude on my Alienware to find the AW4 wiring PDF in my Downloads", does = "Sends a task to the Claude session on your Alienware or work laptop, but only when you name Claude or say yes to the offer; it offers when its own tools can't do something. It says whether the machine is on or, if it's off, since when, and that the task is queued. It never calls a task done when it was only sent. Ask what your Claude session is doing to hear each machine, the running tasks and their current step, the results, and the open Claude Code sessions, the ones waiting on you first. When a task finishes you hear the result (or get a notification), and if it asks something it listens a few seconds for your yes; a file it found is named with its size and only comes to your phone, into Downloads, if you say yes. Ask for files by name (\"find the scanned form on my PC and send it to my phone\") and they come straight to the phone and open there, with Share for WhatsApp or anything else; the engine deletes its copy once the phone has it. More than 20 files or 100 MB, or not what you named, and it asks first. Answer its question with a yes, or follow up (\"now email it to me\"). Ask for research written up (\"make me an itinerary for a day trip into Atlanta\") and it offers to have the Alienware make it as a page: when it's ready it asks \"want me to open it?\" and a yes opens it in LEGION's browser. The work laptop sends summaries only, never files. \"Stop telling me about Claude tasks\" turns the announcements off; \"tell me about Claude tasks again\" turns them back on.", hands = "Home, More, Claude session: machines, send a task, answer a question, Send to phone, the interactive sessions, and Open page on a finished task, Files to my phone in the send box and Open files on a task that brought some, the switch for announcements (also Settings, Proactive speech, Claude tasks); tapping a page's or files' notification opens it."),
            ),
        ),
        Group(
            title = "LEGION itself",
            blurb = "Updates to the app, published by you and installed only on your yes.",
            entries = listOf(
                Entry(name = "app_update", say = "Is there an update for LEGION?", does = "Checks whether a newer LEGION has been published, and says what it changes. The update downloads on Wi-Fi only (unless you allow mobile data) and is checked first: a file whose checksum or signing key does not match the app on your phone is thrown away, and it says so. It always asks before installing, and only your yes installs it. Asked during a conversation, it installs as soon as the conversation ends; in Matrix mode or with the car connected it won't install at all, and says why. LEGION closes for about a minute and comes back on its own. When an update is ready you get a notification with Install and Later, and Alfred mentions it once at a quiet moment.", hands = "Settings, Updates: this phone's version, the newest one and its notes, Check now, Install, Allow mobile data, the one-time Allow installs grant, and which wording Alfred is running; or Install on the update's notification."),
            ),
        ),
        Group(
            title = "Clock",
            blurb = "Alarms, timers, the stopwatch and bedtime. All on the phone, works offline.",
            entries = listOf(
                Entry(name = "clock", say = "Wake me up at 6:30 on weekdays", does = "Sets, lists and cancels alarms; starts, pauses and cancels named timers and says how long is left; runs the stopwatch with laps; and turns bedtime on or off. An alarm is a plain ring over the lock screen with Dismiss, Snooze and Brief me, which starts your daily brief in one tap. Bedtime turns Quiet and the feed block on; Quiet goes off again when you dismiss the next alarm, unless you had it on already, and the feed block stays on until you turn it off. Works offline.", hands = "Tap the big clock on Matrix home, or open Clock from the app drawer: Alarms, Timers, Stopwatch and Bedtime tabs; Pause, Cancel, Snooze and Dismiss on the notifications."),
            ),
        ),
        Group(
            title = "Watches",
            blurb = "Tell me when something happens: money, school, the car, a reply. Checked by the engine (mail by the phone), said once.",
            entries = listOf(
                Entry(name = "watch", say = "Tell me when my paycheck lands", does = "Sets a standing watch and tells you, unprompted, when it fires: a charge or a deposit over an amount (optionally at one merchant or from one payer), a bank balance under an amount, Canvas work due soon, a grade posted, car service coming due, or an email back from a company you applied to (only the company's name is said, never the mail). Lists, pauses, resumes and removes them: \"stop watching my balance\". Three are on from the start: a charge of \$500 or more, Canvas work due within a day, and car service due within two weeks. Never set from something in your mail or a web page.", hands = "Settings, Proactive speech, See and change your watches: list, add, pause and remove; the Watches switch there silences them all."),
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
                Entry(name = "memory", say = "Remember that the XJ takes 5W-30 / What do you remember about the Jeep? / Forget that", does = "Saves, looks up, lists and forgets what it remembers about you. Memories live on your engine, private to you unless you say the household may know one, and the web desk sees the same ones. Forget deletes it for good. With no connection it answers from the phone's last copy and says so, and it won't save or forget anything until it's back online. ID numbers are refused and pointed to the ID vault.", hands = "Settings > Data and privacy > Memory: search, add, edit, share or make private, and Forget (asks first, deletes for good)."),
                Entry(name = "show_vault_item", say = "Show my passport number", does = "Opens the ID vault on your phone at that item. You unlock it with your fingerprint and read it yourself; the assistant never sees, says or keeps the number. Copy puts it on the clipboard for 60 seconds.", hands = "Settings > Data and privacy > ID vault: add, edit, show and delete items behind your fingerprint."),
                Entry(name = "why_did_you_say_that", say = "Why did you say that?", does = "Explains what triggered the last thing it said on its own - the rule and the fact behind it.", hands = "Voice only - no audit-trail screen."),
            ),
        ),
        Group(
            title = "Settings and control",
            blurb = "Changing how it behaves, by voice.",
            entries = listOf(
                Entry(name = "set_companion_name", say = "Call yourself Alfred", does = "Renames your companion.", hands = "Settings > Assistant, then the assistant's own screen: name field."),
                Entry(name = "set_personality", say = "Be drier about it", does = "Changes the personality.", hands = "Voice only - the assistant's settings screen edits its name and voice, not its personality."),
                Entry(name = "set_driver", say = "My name is Kevin", does = "Tells it what to call you.", hands = "Voice only - no screen edits your name yet."),
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

    /** Total entries across every group above - 138 as of the last regeneration. */
    const val TOOL_COUNT: Int = 138
}
