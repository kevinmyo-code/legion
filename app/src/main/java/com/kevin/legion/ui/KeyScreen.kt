package com.kevin.legion.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.kevin.legion.BuildConfig
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.GeminiKeyValidator
import com.kevin.legion.ai.KeyCheck
import com.kevin.legion.backend.MembershipResult
import com.kevin.legion.backend.SignInResult
import com.kevin.legion.backend.SupabaseAuth
import com.kevin.legion.backend.SupabaseConfig
import com.kevin.legion.backend.engine.EngineAuth
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineSyncNow
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.LoginResult
import com.kevin.legion.backend.engine.MeResult
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.DeckSectionRule
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch

/**
 * `settings/key` - the BYO Gemini key screen (ticket 07 resolution §2/§4).
 * Wording is the resolution's, substance verbatim: LEGION talks to Google
 * directly on the user's own key, no server in between, and the free-tier
 * training disclosure carries over from Midnight AI reworded to drop any
 * paid-tier implication (there is no commercial tier here - CLAUDE.md §2).
 *
 * Validation is a suspend call ([GeminiKeyValidator.check]) launched from a
 * click, so it uses `rememberCoroutineScope` rather than a `LaunchedEffect`
 * keyed on some paste-counter - the click already is the event
 * (`.claude/skills/compose-side-effects`).
 *
 * ```
 * paste -> GeminiKeyValidator.check
 *   VALID          -> CompanionProfile.saveGeminiKey (encrypted), proceed
 *   INVALID_KEY    -> "that key was rejected", stay on screen
 *   NETWORK_ERROR  -> offer save-and-verify-later, do not block
 * ```
 *
 * **Restyled 2026-08-12.** The screen logic is unchanged; it wore ticket-07-era
 * plain M3 (`Button("< Back")`, unstyled body text) which read as a different
 * app next to the restyled Settings it hangs off. Now [DeckScreenHeader] plus
 * the same panel/stamp vocabulary as its two siblings.
 */
@Composable
fun KeyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current

    var keyText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var showSaveAnyway by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(GeminiKeyProvider.hasKey()) }

    // --- Supabase project + household sign-in (backend-erp Phase 1) ---
    // Same paste-and-verify shape as the Gemini key above, one screen per ticket 05's Kevin
    // call ("one BYO screen, alongside the Gemini key") rather than a second settings route.
    var supabaseUrlText by remember { mutableStateOf(SupabaseConfig.url(context)) }
    var supabaseAnonKeyText by remember { mutableStateOf(SupabaseConfig.anonKey(context)) }
    var supabaseConfigStatus by remember { mutableStateOf<String?>(null) }
    var supabaseConfigured by remember { mutableStateOf(SupabaseConfig.isConfigured(context)) }

    var emailText by remember { mutableStateOf("") }
    var passwordText by remember { mutableStateOf("") }
    var signInChecking by remember { mutableStateOf(false) }
    var signInStatus by remember { mutableStateOf<String?>(null) }
    var signInStatusIsError by remember { mutableStateOf(false) }
    // "unknown" until the first membership check returns - an unread state must never render as
    // any of the other three (CLAUDE.md sec 1, "unreadable and empty are different sentences").
    var householdState by remember { mutableStateOf<MembershipResult?>(null) }

    val supabaseAuth = remember { SupabaseAuth(context) }

    // --- Django engine (ticket 09 first half: where it is, and how to sign in) ---
    // Same paste-and-verify shape as the two BYO sections above, one row under the existing
    // backend section per the brief rather than a second settings route.
    val engineConfig = remember { EngineConfig(context) }
    val engineAuth = remember { EngineAuth(engineConfig) }
    val engineTransport = remember { EngineTransport(context) }

    var engineUrlText by remember { mutableStateOf(engineConfig.baseUrl()) }
    var engineUrlStatus by remember { mutableStateOf<String?>(null) }
    var engineConfigured by remember { mutableStateOf(engineConfig.isConfigured()) }

    var engineEmailText by remember { mutableStateOf("") }
    var enginePasswordText by remember { mutableStateOf("") }
    // Defaults to the phone model, per the brief - a driver signing in from a new device does not
    // have to think of a name for it first.
    var engineDeviceNameText by remember { mutableStateOf(Build.MODEL.orEmpty()) }
    var engineSignInChecking by remember { mutableStateOf(false) }
    var engineSignInStatus by remember { mutableStateOf<String?>(null) }
    var engineSignInStatusIsError by remember { mutableStateOf(false) }
    // Unread until the first `me` check returns - same "unread must never render as any of the
    // other three" posture householdState already follows (CLAUDE.md sec 1).
    var engineState by remember { mutableStateOf<MeResult?>(null) }

    // "Sync now" (django-engine ticket 09 build item 6): the one surface that says IN WORDS what a
    // sync pass actually did. Every automatic path reports only to logcat, which is useless when
    // Kevin is stood in front of the phone asking whether the laptop engine is reachable at all.
    // Null until the button has been pressed once - an unrun state must never render as a result
    // (CLAUDE.md sec 1, "unreadable and empty are different sentences").
    var engineSyncRunning by remember { mutableStateOf(false) }
    var engineSyncStamp by remember { mutableStateOf<String?>(null) }

    suspend fun refreshEngineState() {
        engineState = if (engineConfig.isConfigured() && engineConfig.isSignedIn()) {
            engineAuth.me()
        } else {
            null
        }
    }

    LaunchedEffect(engineConfigured) {
        if (engineConfigured) refreshEngineState()
    }

    fun saveEngineUrl() {
        val saved = engineConfig.saveBaseUrl(engineUrlText)
        engineUrlStatus = if (saved) {
            "Saved."
        } else {
            "That didn't look like an address - start with http:// or https://."
        }
        engineConfigured = engineConfig.isConfigured()
    }

    fun engineSignIn() {
        engineSignInChecking = true
        scope.launch {
            when (val result = engineAuth.login(engineEmailText, enginePasswordText, engineDeviceNameText)) {
                is LoginResult.Ok -> {
                    engineSignInStatus = "Signed in."
                    engineSignInStatusIsError = false
                    enginePasswordText = ""
                    refreshEngineState()
                }
                is LoginResult.Refused -> {
                    engineSignInStatus = result.message
                    engineSignInStatusIsError = true
                }
                is LoginResult.Unreachable -> {
                    engineSignInStatus = result.message
                    engineSignInStatusIsError = true
                }
            }
            engineSignInChecking = false
        }
    }

    fun engineSyncNow() {
        engineSyncRunning = true
        scope.launch {
            // The sentence comes back from EngineSyncNow, never composed here - one place owns the
            // wording, and it is the place that can see the real counts and the real failure.
            engineSyncStamp = EngineSyncNow(context).run()
            engineSyncRunning = false
        }
    }

    suspend fun refreshHouseholdState() {
        householdState = if (SupabaseConfig.isConfigured(context)) supabaseAuth.isHouseholdMember() else null
    }

    LaunchedEffect(supabaseConfigured) {
        if (supabaseConfigured) refreshHouseholdState()
    }

    fun saveSupabaseConfig() {
        val saved = SupabaseConfig.save(context, supabaseUrlText, supabaseAnonKeyText)
        supabaseConfigStatus = if (saved) {
            "Saved."
        } else {
            "That didn't look like a Supabase project URL and anon key - check both fields."
        }
        supabaseConfigured = SupabaseConfig.isConfigured(context)
    }

    fun signIn() {
        signInChecking = true
        scope.launch {
            when (val result = supabaseAuth.signIn(emailText, passwordText)) {
                SignInResult.Success -> {
                    signInStatus = "Signed in."
                    signInStatusIsError = false
                    passwordText = ""
                    refreshHouseholdState()
                }
                is SignInResult.SucceededButNotPersisted -> {
                    signInStatus = result.message
                    signInStatusIsError = true
                    passwordText = ""
                    refreshHouseholdState()
                }
                is SignInResult.InvalidCredentials -> {
                    signInStatus = result.message
                    signInStatusIsError = true
                }
                is SignInResult.NetworkUnreachable -> {
                    signInStatus = result.message
                    signInStatusIsError = true
                }
                SignInResult.NotConfigured -> {
                    signInStatus = "Save the project URL and anon key above first."
                    signInStatusIsError = true
                }
            }
            signInChecking = false
        }
    }

    fun verify() {
        showSaveAnyway = false
        checking = true
        scope.launch {
            when (GeminiKeyValidator.check(keyText)) {
                KeyCheck.VALID -> {
                    CompanionProfile.saveGeminiKey(context, keyText)
                    // Refresh the process-wide cache immediately (GeminiKeyProvider.init
                    // is otherwise only called once, at AriaForegroundService.onCreate) -
                    // without this, anything that reads GeminiKeyProvider.hasKey() before
                    // the next process restart (e.g. the ledger tab's spend gate, ticket
                    // 08 Part 6) would keep reading "no key" even though one was just saved.
                    GeminiKeyProvider.init(context)
                    hasKey = GeminiKeyProvider.hasKey()
                    keyText = ""
                    status = "Saved."
                    statusIsError = false
                    checking = false
                }
                KeyCheck.INVALID_KEY -> {
                    status = "That key was rejected."
                    statusIsError = true
                    checking = false
                }
                KeyCheck.NETWORK_ERROR -> {
                    status = "Couldn't reach Google to verify right now."
                    statusIsError = true
                    showSaveAnyway = true
                    checking = false
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Gemini key", onBack = onBack)

            Column(
                Modifier
                    .padding(horizontal = 4.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(8.dp))

                Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            if (hasKey) "A key is set" else "No key set",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (hasKey) {
                                "The assistant and every LLM fallback can run. Pasting a new key replaces it."
                            } else {
                                "The assistant can't run without one. Ledger, pantry and fleet are unaffected."
                            },
                            style = LegionType.stamp,
                            // ADVISORY (ticket 13 re-home, ticket 09 answer §3): "no key set" is the
                            // fresh-install state, not a failure - amber, never chrome.
                            color = if (hasKey) sem.faint else sem.estimated,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("Your key", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "LEGION talks to Google directly with your key. Nothing goes through a " +
                            "server I run.",
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = { keyText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        // Mono and no autocorrect/autocapitalise, same as the Spotify client-ID
                        // field: an opaque token that a helpful keyboard silently corrupts.
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                        ),
                        label = { Text("Paste key", style = LegionType.stamp) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = ::verify, enabled = !checking && keyText.isNotBlank()) {
                            Text(
                                if (checking) "VERIFYING" else "VERIFY & SAVE",
                                style = LegionType.stamp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                if (showSaveAnyway) {
                    // NETWORK_ERROR does not block (resolution §2): the driver can
                    // save unverified and it will be exercised for real the next
                    // time something actually calls Gemini.
                    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Save it without verifying?",
                                style = LegionType.stamp,
                                color = sem.faint,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            TextButton(onClick = {
                                CompanionProfile.saveGeminiKey(context, keyText)
                                GeminiKeyProvider.init(context) // see the VALID branch's comment above
                                hasKey = GeminiKeyProvider.hasKey()
                                keyText = ""
                                status = "Saved. Not verified yet - we'll find out the next time it's used."
                                statusIsError = false
                                showSaveAnyway = false
                            }) {
                                Text("SAVE ANYWAY", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    "Note: on Google's free tier, content you send may be used to improve their " +
                        "models. That includes statement and receipt text.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        // ADVISORY (ticket 13 re-home, ticket 09 answer §3): INVALID_KEY and
                        // NETWORK_ERROR are both advisories - act on this - never ALARM.
                        color = if (statusIsError) sem.estimated else sem.faint,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }

                Spacer(Modifier.height(24.dp))

                // --- Household (Supabase) ---
                Text(
                    "Household",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Text(
                    "LEGION talks to YOUR OWN Supabase project directly, on your own URL and " +
                        "key - no server I run. The anon key is not a secret; it is public by " +
                        "design and safe to paste here.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(6.dp))

                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    OutlinedTextField(
                        value = supabaseUrlText,
                        onValueChange = { supabaseUrlText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                        ),
                        label = { Text("Project URL (https://<ref>.supabase.co)", style = LegionType.stamp) },
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = supabaseAnonKeyText,
                        onValueChange = { supabaseAnonKeyText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                        ),
                        label = { Text("Anon key", style = LegionType.stamp) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = ::saveSupabaseConfig) {
                            Text("SAVE", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    supabaseConfigStatus?.let {
                        Text(it, style = LegionType.stamp, color = sem.faint)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(
                        when (val state = householdState) {
                            null -> if (supabaseConfigured) "Checking..." else "Not configured yet."
                            MembershipResult.Member -> "Signed in and on the household roster."
                            is MembershipResult.NotAMember -> state.message
                            MembershipResult.NotSignedIn -> "Signed out."
                            is MembershipResult.NetworkUnreachable -> state.message
                            is MembershipResult.Indeterminate -> state.message
                            MembershipResult.NotConfigured -> "Not configured yet."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (householdState) {
                            MembershipResult.Member -> sem.faint
                            null -> sem.faint
                            else -> sem.estimated
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = emailText,
                        onValueChange = { emailText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Email,
                        ),
                        label = { Text("Email", style = LegionType.stamp) },
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = passwordText,
                        onValueChange = { passwordText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                        ),
                        label = { Text("Password", style = LegionType.stamp) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = ::signIn,
                            enabled = !signInChecking && emailText.isNotBlank() && passwordText.isNotBlank(),
                        ) {
                            Text(
                                if (signInChecking) "SIGNING IN" else "SIGN IN",
                                style = LegionType.stamp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    signInStatus?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (signInStatusIsError) sem.estimated else sem.faint,
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // --- Engine (Django) ---
                DeckSectionRule("Engine", modifier = Modifier.padding(horizontal = 12.dp))
                Text(
                    "LEGION can also talk to a Django engine you run yourself - your own server, " +
                        "your own Postgres, nothing hosted by me. Supabase above keeps working " +
                        "until an aspect is explicitly flipped below.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(6.dp))

                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    OutlinedTextField(
                        value = engineUrlText,
                        onValueChange = { engineUrlText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                        ),
                        label = { Text("Engine address (http://host:port)", style = LegionType.stamp) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = ::saveEngineUrl) {
                            Text("SAVE", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    engineUrlStatus?.let {
                        Text(it, style = LegionType.stamp, color = sem.faint)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(
                        when (val state = engineState) {
                            null -> when {
                                !engineConfigured -> "Not configured yet."
                                engineConfig.isSignedIn() -> "Checking..."
                                else -> "Not signed in."
                            }
                            is MeResult.Ok -> "Signed in as ${state.email} on this device."
                            is MeResult.Refused -> state.message
                            is MeResult.Unreachable -> state.message
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (engineState) {
                            is MeResult.Ok -> sem.faint
                            null -> sem.faint
                            else -> sem.estimated
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = engineEmailText,
                        onValueChange = { engineEmailText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Email,
                        ),
                        label = { Text("Email", style = LegionType.stamp) },
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = enginePasswordText,
                        onValueChange = { enginePasswordText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                        ),
                        label = { Text("Password", style = LegionType.stamp) },
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = engineDeviceNameText,
                        onValueChange = { engineDeviceNameText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            autoCorrectEnabled = false,
                        ),
                        label = { Text("Device name", style = LegionType.stamp) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = ::engineSignIn,
                            enabled = !engineSignInChecking && engineEmailText.isNotBlank() &&
                                enginePasswordText.isNotBlank() && engineDeviceNameText.isNotBlank(),
                        ) {
                            Text(
                                if (engineSignInChecking) "SIGNING IN" else "SIGN IN",
                                style = LegionType.stamp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    engineSignInStatus?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (engineSignInStatusIsError) sem.estimated else sem.faint,
                        )
                    }
                }

                // Debug-only per-aspect transport toggles (ticket 09's build item 4). Deliberately
                // absent from a release build rather than merely hidden - a driver on a release
                // build has no way to reach this row at all.
                //
                // The comment here used to read "Supabase is the truth for every aspect until
                // explicitly flipped"; that stopped being true on 2026-09-06, when events and
                // checklists took Django as their default after the A25 end-to-end run
                // (EngineTransport.DJANGO_BY_DEFAULT). A row shows the transport that will
                // ACTUALLY be used, so an aspect on its Django default reads SUPABASE until this
                // device is signed in to an engine.
                if (BuildConfig.DEBUG) {
                    Spacer(Modifier.height(12.dp))
                    DeckSectionRule("Transport (debug)", modifier = Modifier.padding(horizontal = 12.dp))
                    Text(
                        "events and checklists are on the engine by default once this device is " +
                            "signed in to one; every other aspect stays on Supabase until it is " +
                            "flipped here. A row shows what will actually be used. Tap to toggle " +
                            "it, which pins that aspect for good. This section never ships to a " +
                            "release build.",
                        style = LegionType.stamp,
                        color = sem.estimated,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        EngineTransport.KNOWN_ASPECTS.forEach { aspect ->
                            var transport by remember(aspect) {
                                mutableStateOf(engineTransport.transportFor(aspect))
                            }
                            DeckRow(
                                label = aspect,
                                value = transport.name,
                                valueColor = if (transport == Transport.DJANGO) sem.estimated else null,
                                modifier = Modifier.clickable {
                                    val next = if (transport == Transport.SUPABASE) {
                                        Transport.DJANGO
                                    } else {
                                        Transport.SUPABASE
                                    }
                                    engineTransport.setTransport(aspect, next)
                                    transport = next
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = ::engineSyncNow, enabled = !engineSyncRunning) {
                                Text(
                                    if (engineSyncRunning) "SYNCING" else "SYNC NOW",
                                    style = LegionType.stamp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        // Nothing at all until a real pass has returned - see the state
                        // declaration above for why an unrun state is not rendered as a result.
                        engineSyncStamp?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = sem.faint)
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
