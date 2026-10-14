package com.kevin.midnightai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kevin.midnightai.ai.CompanionIdentity
import com.kevin.midnightai.ai.CompanionProfile
import com.kevin.midnightai.ai.DEFAULT_VOICE
import com.kevin.midnightai.ai.DriverProfile
import com.kevin.midnightai.ai.GeminiKeyProvider
import com.kevin.midnightai.ai.PersonaSelection
import com.kevin.midnightai.ai.EntitlementManager
import com.kevin.midnightai.service.GeminiLiveSession
import com.kevin.midnightai.service.LiveEvent
import com.kevin.midnightai.service.LiveToolbox
import com.kevin.midnightai.service.resolveLiveConnectionMode
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.vehicle.VehicleController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The unified guided-setup flow. Replaces the old all-voice
 * ([com.kevin.midnightai.ai.OnboardingFlow]) and all-typed onboarding screens
 * with a single per-step manager: at each field the driver can speak (the
 * companion asks in its chosen voice and captures the answer via the onboarding
 * tools), tap, or type. Voice is offered only when it can actually run
 * ([EntitlementManager.canStartVoice] and mic granted), so a driver with no
 * Gemini key saved completes the whole flow by tap/type and the mic lights up
 * once a key is saved.
 *
 * Reuses the existing step composables ([VoicePicker], [PersonaPicker],
 * [ObdPairOnboardingStep]) and only adds the per-field voice layer over the
 * text fields. Entry points (Cruise prompt, Settings redo) and the
 * [OnboardingState] flags are unchanged. The AVATAR/BACKDROP face-and-
 * wallpaper generation steps were removed 2026-09-27 - the companion's look
 * is now fixed, bundled seed art (see [com.kevin.midnightai.ai.SeedArt]),
 * so there's nothing left to generate or pick between during setup.
 */
private enum class ManagerStep { VOICE, NAME, PERSONALITY, DRIVER, CAR, OBD, DONE }

@Composable
fun OnboardingManager(onComplete: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Voice is offered per step only when it can actually connect and record.
    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    // Recompute when the entitlement mode changes (key saved mid-wizard, e.g. via
    // FinalStep's inline key entry) so voice availability updates without a restart.
    val entitlementMode by EntitlementManager.mode.collectAsState()
    val voiceAvailable = remember(micGranted, entitlementMode) {
        micGranted && EntitlementManager.canStartVoice()
    }

    var step by remember { mutableStateOf(ManagerStep.VOICE) }

    // Prefilled from the current profile so a "redo from Settings" run shows the
    // existing values, and an untouched field is preserved on persist rather than
    // being blanked.
    var selectedVoice by remember { mutableStateOf(CompanionProfile.voice(context).ifBlank { DEFAULT_VOICE }) }
    var companionName by remember { mutableStateOf(CompanionProfile.name(context)) }
    var personaProse by remember { mutableStateOf(CompanionProfile.persona(context)) }
    var personaSelections by remember { mutableStateOf<Map<String, PersonaSelection>>(emptyMap()) }
    var driverName by remember { mutableStateOf(DriverProfile.name(context)) }
    var driverAbout by remember { mutableStateOf(DriverProfile.about(context)) }
    var carMake by remember { mutableStateOf("") }
    var carModel by remember { mutableStateOf("") }
    var carTrim by remember { mutableStateOf("") }
    var carYearText by remember { mutableStateOf("") }

    fun persistAndFinish() {
        scope.launch {
            val finalName = companionName.ifBlank { "Midnight" }
            if (personaSelections.isNotEmpty()) {
                CompanionProfile.savePersona(context, finalName, personaSelections)
            } else {
                CompanionProfile.saveProfile(context, finalName, personaProse)
            }
            CompanionProfile.saveVoice(context, selectedVoice)
            if (carMake.isNotBlank() && carModel.isNotBlank()) {
                VehicleController.saveVehicleFacts(
                    context, carMake, carModel, carTrim, carYearText.toIntOrNull() ?: 0, 0,
                )
            }
            DriverProfile.save(context, driverName, driverAbout)
            OnboardingState.markComplete(context)
            onComplete()
        }
    }

    when (step) {
        ManagerStep.VOICE -> VoicePicker(
            initialVoice = selectedVoice,
            onConfirm = { selectedVoice = it; step = ManagerStep.NAME },
            onBack = onExit,
        )

        ManagerStep.NAME -> TriModalStep(
            title = "What should you call me?",
            prompt = "Give your companion a name. Speak it, or type it below.",
            voiceAvailable = voiceAvailable,
            voiceName = selectedVoice,
            instruction = NAME_INSTRUCTION,
            opener = NAME_OPENER,
            expectTool = "set_companion_name",
            onCaptured = { companionName = it.optString("name").trim() },
            onBack = { step = ManagerStep.VOICE },
            nextEnabled = companionName.isNotBlank(),
            onNext = { step = ManagerStep.PERSONALITY },
        ) {
            OutlinedTextField(
                value = companionName,
                onValueChange = { companionName = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ManagerStep.PERSONALITY -> PersonalityStep(
            voiceAvailable = voiceAvailable,
            voiceName = selectedVoice,
            initialSelections = personaSelections,
            onVoiceCaptured = { description, _ ->
                personaProse = description
                personaSelections = emptyMap()
                step = ManagerStep.DRIVER
            },
            onTapComplete = { personaSelections = it; step = ManagerStep.DRIVER },
            onBack = { step = ManagerStep.NAME },
        )

        ManagerStep.DRIVER -> TriModalStep(
            title = "Who are you?",
            prompt = "Tell your companion your name and anything it should know about you. " +
                "Speak it, or type it below.",
            voiceAvailable = voiceAvailable,
            voiceName = selectedVoice,
            instruction = DRIVER_INSTRUCTION,
            opener = DRIVER_OPENER,
            expectTool = "set_driver",
            onCaptured = {
                driverName = it.optString("name").trim()
                driverAbout = it.optString("about").trim()
            },
            onBack = { step = ManagerStep.PERSONALITY },
            nextEnabled = true,
            onNext = { step = ManagerStep.CAR },
        ) {
            OutlinedTextField(
                value = driverName,
                onValueChange = { driverName = it },
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = driverAbout,
                onValueChange = { driverAbout = it },
                label = { Text("Anything it should know? (optional)") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            )
        }

        ManagerStep.CAR -> TriModalStep(
            title = "What car am I?",
            prompt = "Year, make, model, and trim if you know it. Speak it, or type it below. " +
                "You can skip this.",
            voiceAvailable = voiceAvailable,
            voiceName = selectedVoice,
            instruction = CAR_INSTRUCTION,
            opener = CAR_OPENER,
            expectTool = "register_car",
            onCaptured = {
                val y = it.optInt("year", 0)
                carYearText = if (y > 0) y.toString() else ""
                carMake = it.optString("make").trim()
                carModel = it.optString("model").trim()
                carTrim = it.optString("trim").trim()
            },
            onBack = { step = ManagerStep.DRIVER },
            nextEnabled = true,
            onNext = { step = ManagerStep.OBD },
        ) {
            OutlinedTextField(
                value = carMake, onValueChange = { carMake = it },
                label = { Text("Make") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = carModel, onValueChange = { carModel = it },
                label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = carTrim, onValueChange = { carTrim = it },
                label = { Text("Trim (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = carYearText,
                onValueChange = { v -> carYearText = v.filter { it.isDigit() }.take(4) },
                label = { Text("Year") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }

        ManagerStep.OBD -> ObdPairOnboardingStep(
            onContinue = { step = ManagerStep.DONE },
            onSkip = { step = ManagerStep.DONE },
            onBack = { step = ManagerStep.CAR },
        )

        ManagerStep.DONE -> FinalStep(onStart = { persistAndFinish() })
    }
}

/**
 * A field step with all three input modes visible at once: a spoken-capture
 * button (only when [voiceAvailable]), and the [fields] slot for tapping/typing.
 * Voice fills the same fields, which stay editable before NEXT so the driver can
 * correct a misheard answer.
 */
@Composable
private fun TriModalStep(
    title: String,
    prompt: String,
    voiceAvailable: Boolean,
    voiceName: String,
    instruction: String,
    opener: String,
    expectTool: String,
    onCaptured: (JSONObject) -> Unit,
    onBack: () -> Unit,
    nextEnabled: Boolean,
    onNext: () -> Unit,
    fields: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        BackLabel(onBack)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(prompt, style = MaterialTheme.typography.bodyMedium, color = AriaColors.TextDim)
        Spacer(Modifier.height(16.dp))
        if (voiceAvailable) {
            VoiceCaptureButton(
                voiceName = voiceName,
                instruction = instruction,
                opener = opener,
                expectTool = expectTool,
                onCaptured = onCaptured,
            )
            Spacer(Modifier.height(16.dp))
            Text("OR TYPE IT BELOW", style = MaterialTheme.typography.labelSmall, color = AriaColors.TextDim)
            Spacer(Modifier.height(10.dp))
        }
        fields()
        Spacer(Modifier.height(20.dp))
        Button(onClick = onNext, enabled = nextEnabled) { Text("NEXT") }
    }
}

/**
 * Personality: voice describes the persona (captures set_personality's prose +
 * look), or the driver taps through [PersonaPicker]. When voice is available a
 * small chooser offers both; otherwise it goes straight to the tap picker.
 */
private enum class PersonalityMode { CHOOSE, TAP }

@Composable
private fun PersonalityStep(
    voiceAvailable: Boolean,
    voiceName: String,
    initialSelections: Map<String, PersonaSelection>,
    onVoiceCaptured: (description: String, look: String) -> Unit,
    onTapComplete: (Map<String, PersonaSelection>) -> Unit,
    onBack: () -> Unit,
) {
    var mode by remember { mutableStateOf(if (voiceAvailable) PersonalityMode.CHOOSE else PersonalityMode.TAP) }

    when (mode) {
        PersonalityMode.CHOOSE -> Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AriaColors.Background)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            BackLabel(onBack)
            Spacer(Modifier.height(10.dp))
            Text("My personality", style = MaterialTheme.typography.headlineMedium, color = AriaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Describe how I should act - speak it, or pick from a few options.",
                style = MaterialTheme.typography.bodyMedium,
                color = AriaColors.TextDim,
            )
            Spacer(Modifier.height(16.dp))
            VoiceCaptureButton(
                voiceName = voiceName,
                instruction = PERSONALITY_INSTRUCTION,
                opener = PERSONALITY_OPENER,
                expectTool = "set_personality",
                onCaptured = { onVoiceCaptured(it.optString("description").trim(), it.optString("look").trim()) },
            )
            Spacer(Modifier.height(18.dp))
            Text("OR", style = MaterialTheme.typography.labelSmall, color = AriaColors.TextDim)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { mode = PersonalityMode.TAP }) { Text("PICK IT WITH TAPS") }
        }

        PersonalityMode.TAP -> PersonaPicker(
            initial = initialSelections,
            onComplete = onTapComplete,
            onBack = { if (voiceAvailable) mode = PersonalityMode.CHOOSE else onBack() },
        )
    }
}

/**
 * One scoped, on-demand voice capture for a single onboarding field. Tapping
 * SPEAK opens a short Live turn in the companion's chosen voice
 * ([resolveLiveConnectionMode] resolves the driver's BYO key): it asks the
 * field question, listens once, and fires [onCaptured] when the model calls
 * [expectTool]. The session is torn down as soon as the answer lands or the turn
 * fails, so the mic is live only during an explicit capture (no persistent
 * listening, no stray pickup between steps).
 */
@Composable
private fun VoiceCaptureButton(
    voiceName: String,
    instruction: String,
    opener: String,
    expectTool: String,
    onCaptured: (JSONObject) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf("") }
    val sessionRef = remember { mutableStateOf<GeminiLiveSession?>(null) }

    fun close() {
        val s = sessionRef.value
        sessionRef.value = null
        active = false
        listening = false
        if (s != null) scope.launch { s.destroy() }
    }

    DisposableEffect(Unit) { onDispose { sessionRef.value?.destroy() } }

    fun begin() {
        error = false
        caption = ""
        active = true
        listening = false
        scope.launch {
            val mode = resolveLiveConnectionMode()
            if (mode == null) {
                active = false
                error = true
                return@launch
            }
            var s: GeminiLiveSession? = null
            s = GeminiLiveSession(context.applicationContext) { event ->
                when (event) {
                    is LiveEvent.Connected -> { listening = true; s?.beginConversation(opener) }
                    is LiveEvent.Subtitle -> caption = event.text
                    is LiveEvent.ToolCall -> {
                        s?.sendToolResponse(event.id, event.name, JSONObject().put("success", true))
                        if (event.name == expectTool && active) {
                            onCaptured(event.args)
                            close()
                        }
                    }
                    is LiveEvent.Closed -> {
                        if (active) error = true
                        close()
                    }
                    else -> {}
                }
            }
            sessionRef.value = s
            s.start(
                systemInstruction = instruction,
                functionDeclarations = LiveToolbox.onboardingDeclarations(),
                vad = true,
                voiceName = voiceName,
                idleTimeoutMs = 30_000L,
                connectionMode = mode,
            )
            // Connect-timeout: if the socket never reports Connected, surface an
            // error and fall the driver back to tapping/typing instead of hanging.
            delay(12_000)
            if (active && !listening) {
                error = true
                close()
            }
        }
    }

    when {
        active -> {
            val phase = animPhase(periodMs = if (listening) 1200 else 3200)
            val orbScale = 0.92f + 0.12f * triangle(phase)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .scale(orbScale)
                        .clip(CircleShape)
                        .background(if (listening) AriaColors.Amber else AriaColors.Panel),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("◔", color = AriaColors.Background, style = MaterialTheme.typography.headlineMedium)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (listening) "Listening…" else "Connecting…",
                    style = MaterialTheme.typography.labelLarge,
                    color = AriaColors.TextDim,
                )
                if (caption.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AriaColors.TextPrimary,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { close() }) { Text("STOP", color = AriaColors.Amber) }
            }
        }
        error -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Couldn't reach voice - tap or type instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AriaColors.TextDim,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { begin() }) { Text("TRY VOICE AGAIN", color = AriaColors.Amber) }
            }
        }
        else -> {
            Button(onClick = { begin() }) { Text("SPEAK") }
        }
    }
}

/** Final step: nothing left to configure. Persists and enters Cruise. */
@Composable
private fun FinalStep(onStart: () -> Unit) {
    // Two tiers only (CLAUDE.md sec 2): a no-key install can't talk or generate
    // art, so offer the key right here - the activation moment - so first run
    // goes straight from setup to a working companion. Reactive: saving the key
    // hides the prompt.
    var needsKey by remember {
        mutableStateOf(!GeminiKeyProvider.hasKey())
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("You're all set", style = MaterialTheme.typography.headlineSmall, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Music works automatically from your phone over Bluetooth - play, pause, and skip by voice. " +
                "No extra setup needed.",
            style = MaterialTheme.typography.bodyMedium,
            color = AriaColors.TextDim,
        )
        if (needsKey) {
            Spacer(Modifier.height(22.dp))
            Text("One last thing", style = MaterialTheme.typography.titleMedium, color = AriaColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "To hear your companion talk and generate its face and wallpapers, add a free Google " +
                    "Gemini key - it stays encrypted on this device. You can also add it anytime in " +
                    "Settings > AI power & plan.",
                style = MaterialTheme.typography.bodySmall,
                color = AriaColors.TextDim,
            )
            Spacer(Modifier.height(10.dp))
            ApiKeyEntry(onSaved = { needsKey = !GeminiKeyProvider.hasKey() })
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("START DRIVING") }
    }
}

@Composable
private fun BackLabel(onBack: () -> Unit) {
    Text(
        text = "‹ BACK",
        style = MaterialTheme.typography.labelSmall,
        color = AriaColors.Amber,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { onBack() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    )
}

// --- Per-field spoken-capture prompts. Each is scoped to ONE field + tool so the
// companion asks a single question, captures it, and stops.
//
// The identity clause comes from CompanionIdentity.onboardingClause(), never
// restated inline: these four were part of the set of eleven sites that each
// asserted "You ARE this car" independently, which is how DEFAULT_PERSONA came to
// say the opposite without anything catching it. `val` not `const val` because
// the clause is a function call; it returns a constant, so this is still resolved
// once at class-load.

private val NAME_INSTRUCTION =
    CompanionIdentity.onboardingClause() +
        " You are meeting your owner and getting set up. Ask ONE short, warm spoken question: " +
        "what they would like to call you. When they answer, call set_companion_name with the name you " +
        "heard, then stop. Ask nothing else. Do not read this instruction aloud."
private const val NAME_OPENER =
    "(System: say one short warm line and ask what they'd like to call you.)"

private val PERSONALITY_INSTRUCTION =
    CompanionIdentity.onboardingClause() +
        " You are getting set up with your owner. In one or two short spoken exchanges, learn how " +
        "you should act - your personality. When you have a feel for it, call set_personality with " +
        "'description' (a short second-person persona written as an instruction to yourself, grounded in " +
        "being the car, e.g. 'You are warm and easygoing, quick with a dry joke about your own quirks') " +
        "and 'look' (a brief visual descriptor of how you might appear as a character, used to draw your " +
        "face). Then stop. Do not read this instruction aloud."
private const val PERSONALITY_OPENER =
    "(System: ask in one short line what kind of personality they'd like you to have; offer a couple of " +
        "examples like warm and easygoing, or dry and witty.)"

private val DRIVER_INSTRUCTION =
    CompanionIdentity.onboardingClause() +
        " You are getting set up with your owner. Ask their name and, lightly, anything they'd like " +
        "you to know about them. When they answer, call set_driver with 'name' and 'about', then stop. " +
        "Do not read this instruction aloud."
private const val DRIVER_OPENER =
    "(System: ask their name and, briefly, anything they want you to know about them.)"

private val CAR_INSTRUCTION =
    CompanionIdentity.onboardingClause() +
        " You are getting set up with your owner. Ask what car you are - year, make, model, and trim " +
        "if they know it. When they answer, call register_car with year, make, model, and trim, then stop. " +
        "If they'd rather skip it, that's fine. Do not read this instruction aloud."
private const val CAR_OPENER =
    "(System: ask what car you are - year, make, model, and trim if they know it.)"
