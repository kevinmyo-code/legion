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
import com.kevin.legion.ai.KeyHealth
import com.kevin.legion.ai.GeminiUsageMeter
import com.kevin.legion.backend.ConversationAuditReconcile
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

    // The audit trail's upload backlog (2026-09-06). Null until the first read returns, and null
    // again if the table cannot be read - an unread state must never render as "nothing waiting",
    // which is the same "unreadable and empty are different sentences" rule (CLAUDE.md sec 1) the
    // engine block below follows. Re-read on every entry to this screen rather than cached: the
    // number's whole job is to be current when someone comes looking.
    var auditPending by remember { mutableStateOf<ConversationAuditReconcile.Pending?>(null) }
    LaunchedEffect(Unit) {
        auditPending = ConversationAuditReconcile.pendingSummary(context)
    }

    // Measured Gemini spend (2026-09-06). Same null-is-not-zero discipline as auditPending above:
    // null means the tables could not be read, and geminiSpendSentence says so rather than
    // reporting a comfortable 0. Re-read on entry, because the number's job is to be current when
    // someone comes looking - which, given what prompted it, will be someone asking where the
    // credits went.
    var spend by remember { mutableStateOf<GeminiUsageMeter.Spend?>(null) }
    LaunchedEffect(Unit) {
        spend = GeminiUsageMeter.spend(context)
    }

    // Whether the assistant can run at all (2026-09-06). Read on entry rather than collected: this
    // is the screen someone opens BECAUSE voice stopped working, so the value that matters is the
    // one at that moment. Null problem means nothing is wrong and the row does not render.
    val keyProblem = remember { KeyHealth.lastProblem }
    val keyProblemDetail = remember { KeyHealth.detail }

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

                // --- Audit trail backlog ---
                // NOT behind BuildConfig.DEBUG, unlike the transport rows further down. This table
                // is the only durable record of what a tool call actually did (the "it said 142k"
                // incident), it is deleted on a 14-day timer, and its upload silently discarded
                // three days of rows in September 2026 while every surface reported success. A
                // number nobody can see is how that lasted three days; CLAUDE.md sec 7 wants the
                // failure said in words, so this says it in a release build too.
                DeckSectionRule("Audit trail", modifier = Modifier.padding(horizontal = 12.dp))
                Text(
                    auditTrailBacklogSentence(auditPending),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        auditPending == null -> sem.estimated
                        auditPending?.rows == 0 -> sem.faint
                        else -> sem.estimated
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                Spacer(Modifier.height(24.dp))

                // --- Can the assistant run at all? ---
                // FIRST, above the spend figures, because it is the answer to the question that
                // brings someone to this screen: the wake word did nothing and they want to know
                // why. Absent entirely when nothing is wrong - see assistantAvailabilitySentence
                // for why a healthy install shows no reassurance here.
                assistantAvailabilitySentence(keyProblem, keyProblemDetail)?.let {
                    DeckSectionRule("Assistant", modifier = Modifier.padding(horizontal = 12.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = sem.estimated,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                }

                // --- Gemini spend ---
                // NOT behind BuildConfig.DEBUG, for the same reason the audit block above is not:
                // this is the answer to a question Kevin actually asked out loud ("my gemini
                // credits burned really fast - are we wasting a lot?"), and a number nobody can see
                // is how the reflection loop spent a week re-synthesizing the same memories.
                DeckSectionRule("Gemini usage", modifier = Modifier.padding(horizontal = 12.dp))
                Text(
                    geminiSpendSentence(spend),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (spend == null) sem.estimated else sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    liveConnectSentence(spend),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        spend == null -> sem.estimated
                        spend?.connectsWithoutTurnThisMonth ?: 0 > 0 -> sem.estimated
                        else -> sem.faint
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                // Only when something HAS been set aside - see backgroundPassSetAsideSentence for
                // why the empty case says nothing rather than reassuring.
                backgroundPassSetAsideSentence(spend)?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = sem.estimated,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
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

/**
 * The one sentence the Setup screen says about the audit trail's upload backlog.
 *
 * **Pulled out as a pure function purely so it can be unit-tested**, the same reasoning
 * [com.kevin.legion.data.local.auditContent]'s own doc gives: an inline `when` inside the
 * Composable would be correct today and untestable forever, and this particular sentence is the
 * one that has to be right - it is the whole visible defence against the failure it describes
 * (`conversation_audit` uploaded nothing for three days while every surface said success, see
 * [com.kevin.legion.data.local.ConversationAudit.clientUuid]).
 *
 * Three states, deliberately worded so none can be mistaken for another:
 * - `null` - the table could not be read. Says so. **Never rendered as "nothing waiting"**: an
 *   unreadable count and an empty one are different sentences (CLAUDE.md section 1), and reading
 *   the first as the second is exactly how a person is told everything is fine when the app cannot
 *   see.
 * - 0 rows - everything is on the server, said plainly.
 * - n rows - the count AND the oldest row's age, because a count alone does not distinguish "a
 *   turn from four minutes ago that has not synced yet" from "a fortnight of evidence about to be
 *   deleted". The age is the half that makes the number actionable.
 *
 * The age is rendered coarsely (days / hours / minutes) on purpose - the decision this informs is
 * "is anything wrong", not "exactly how wrong", and a precise duration invites reading a backlog
 * that is fine as a problem.
 */
internal fun auditTrailBacklogSentence(pending: ConversationAuditReconcile.Pending?): String = when {
    pending == null -> "Couldn't read the audit trail on this device."
    pending.rows == 0 -> "Every conversation and tool call on this device is on the server."
    // Retention keeps an un-uploaded row rather than deleting it
    // (ConversationAuditDao.trimUploadedOlderThan), so this backlog is safe - saying so stops the
    // sentence reading as an alarm about data already lost, which it is not.
    else -> {
        val rows = if (pending.rows == 1) "1 row" else "${pending.rows} rows"
        when (val age = pending.oldestAgeMs?.let(::coarseAge)) {
            null -> "$rows waiting to reach the server. Nothing is deleted while it waits."
            else -> "$rows waiting to reach the server, oldest $age old. Nothing is deleted while it waits."
        }
    }
}

/**
 * The one sentence the Setup screen says about whether the assistant can run at all.
 *
 * **Why it exists (2026-09-06, Kevin: "i ran out of credits and im probably not gonna top up for a
 * while").** On a key with no quota the Live socket fails its HTTP upgrade, so the wake word
 * appears to do nothing, the microphone appears deaf, and nothing anywhere says why. The app knew:
 * [KeyHealth] recorded the diagnosis in eight places. It was read in none, and was process-lifetime
 * only. This is the reader.
 *
 * **It states what was OBSERVED, never a cause nobody checked.** A 429 is Gemini's
 * `RESOURCE_EXHAUSTED` for a per-minute rate limit AND for an exhausted quota, and the status code
 * does not tell the two apart - so the sentence names both possibilities and shows the status it
 * saw rather than picking one and sounding confident. A 401/403 is unambiguous and gets a definite
 * sentence. This is CLAUDE.md section 7's outcome rule applied to a diagnosis: do not assert what
 * was not observed.
 *
 * **It is not a paywall and not a nag.** It names what does not work, says everything else still
 * does, and stops. Nothing here counts down, re-prompts, or asks for money - CLAUDE.md section 7's
 * compulsion ban. It disappears on its own the moment a call succeeds, because [KeyHealth.noteOk]
 * fires on any 200 or any accepted Live setup.
 *
 * Returns null when there is nothing wrong, so a healthy install shows no row at all rather than a
 * reassuring line nobody needs.
 */
internal fun assistantAvailabilitySentence(problem: String?, detail: String): String? = when (problem) {
    KeyHealth.PROBLEM_RATE_LIMITED -> buildString {
        append(
            "Voice is unavailable: Gemini refused the last call because the key is out of quota " +
                "or is being rate-limited. Everything else in LEGION still works by hand.",
        )
        // The evidence, so the claim above is checkable rather than merely plausible.
        if (detail.isNotBlank()) append(" Gemini said: $detail")
    }
    KeyHealth.PROBLEM_INVALID -> buildString {
        append(
            "Voice is unavailable: Gemini rejected the key. Everything else in LEGION still works " +
                "by hand.",
        )
        if (detail.isNotBlank()) append(" Gemini said: $detail")
    }
    else -> null
}

/**
 * The sentences the Setup screen says about what Gemini has actually cost (2026-09-06).
 *
 * **Why this exists.** Kevin asked whether the app was wasting his credits and nothing could
 * answer him, because nothing had ever read a token count off the Live socket and only one of the
 * thirty REST sub-agent call sites was metered. Every figure the app had ever shown was estimated
 * from prompt length. These sentences report [com.kevin.legion.data.local.GeminiUsage], which is
 * measured.
 *
 * **A number the API never reported is said in words, never rendered as zero.** Gemini omits
 * `usageMetadata` on some response shapes even inside a 200, so a window can contain calls whose
 * cost is genuinely unknown. Printing "0 tokens today" over a day of unreported calls would tell
 * him he is fine precisely when the app cannot see - the same failure as reading a refused
 * permission as an empty calendar (CLAUDE.md section 1). Three distinct states, and the caller
 * must not collapse any two:
 * - `null` spend - the tables could not be read at all.
 * - a window with calls but no reported totals - says the API did not report, and how many calls.
 * - a total with SOME unreported calls behind it - gives the number AND says it is a floor.
 *
 * Pulled out as a pure function for the same reason [auditTrailBacklogSentence] was: an inline
 * `when` inside the Composable would be correct today and untestable forever.
 */
internal fun geminiSpendSentence(spend: GeminiUsageMeter.Spend?): String = when {
    spend == null -> "Couldn't read this device's Gemini usage."
    spend.callsThisMonth == 0 ->
        "No Gemini calls recorded this month. Metering started 6 September 2026, so anything " +
            "before that was never counted."
    else -> "${window("today", spend.tokensToday, spend.callsToday, spend.unreportedToday)} " +
        window("this month", spend.tokensThisMonth, spend.callsThisMonth, spend.unreportedThisMonth)
}

/** One window's clause for [geminiSpendSentence]. See that function for why an unreported total is
 *  never printed as zero. */
private fun window(label: String, tokens: Long?, calls: Int, unreported: Int): String {
    val callWord = if (calls == 1) "1 call" else "$calls calls"
    return when {
        calls == 0 -> "No Gemini calls $label."
        tokens == null ->
            "$callWord $label, but the API reported no token count for any of them, so the cost " +
                "is unknown."
        unreported > 0 ->
            "At least $tokens tokens $label across $callWord - at least, because the API reported " +
                "no count for $unreported of them."
        else -> "$tokens tokens $label across $callWord."
    }
}

/**
 * The sentence about Live sockets that carried nothing - the shape that costs money for nothing,
 * since every connect pays for its setup prompt whether or not anything follows.
 *
 * Separate from [geminiSpendSentence] because it answers a different question: that one is "how
 * much", this one is "how much of it was wasted". August's reconnect storm was found by reading
 * logcat by hand, because [com.kevin.legion.MidnightEvents.sessionStart] is a `Log.d` and nothing
 * else; this is the same fact, durable and visible without a cable.
 *
 * **The wording changed on 2026-09-07 and the counter underneath it changed with it.** It used to
 * say "spoken into", because
 * [com.kevin.legion.service.GeminiLiveSession] only counted a connect as used when Gemini returned
 * a TRANSCRIPT for it. That undercounted badly - it reported 24 connects in a day with zero
 * carrying a turn on a phone that had been used, because `inputAudioTranscription` comes back empty
 * on some turns the model plainly heard, and because a proactive line spoken on a warm socket was
 * never counted at all. The counter now reads three signals
 * ([com.kevin.legion.service.GeminiLiveSession.turnCarriedWork]): a transcript, a spoken reply, or
 * a tool call. So "carried a turn" is the honest phrase and "spoken into" is not: a socket that
 * only ever spoke an unprompted line is counted, and nobody spoke into it.
 */
internal fun liveConnectSentence(spend: GeminiUsageMeter.Spend?): String = when {
    spend == null -> "Couldn't read this device's connection count."
    spend.connectsThisMonth == 0 -> "No voice connections this month."
    spend.connectsWithoutTurnThisMonth == 0 ->
        "${spend.connectsThisMonth} voice connections this month, every one of them used - " +
            "something was said, heard, or run on each."
    else ->
        "${spend.connectsThisMonth} voice connections this month, " +
            "${spend.connectsWithoutTurnThisMonth} of which carried nothing at all - nothing " +
            "heard, nothing said, no tool run. Each one still paid for its setup prompt."
}

/**
 * What the background passes have given up on, in words.
 *
 * A pass that quietly stopped and a pass that never ran look identical from the outside, and that
 * is exactly how reflection went a week re-synthesizing the same memories with nobody the wiser.
 * Empty is the normal state and says nothing at all, so this returns null rather than a reassuring
 * line nobody needs to read.
 */
internal fun backgroundPassSetAsideSentence(spend: GeminiUsageMeter.Spend?): String? {
    val rows = spend?.setAside?.takeIf { it.isNotEmpty() } ?: return null
    val head = if (rows.size == 1) "One background task has stopped retrying" else
        "${rows.size} background tasks have stopped retrying"
    return "$head. ${rows.first().setAsideReason}"
}

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L

/** A duration in the coarsest unit that is at least 1 - see [auditTrailBacklogSentence] for why
 *  precision here would be false comfort rather than information. */
private fun coarseAge(ms: Long): String {
    val minutes = ms / MS_PER_MINUTE
    val hours = minutes / MINUTES_PER_HOUR
    val days = hours / HOURS_PER_DAY
    return when {
        days >= 1L -> if (days == 1L) "1 day" else "$days days"
        hours >= 1L -> if (hours == 1L) "1 hour" else "$hours hours"
        minutes >= 1L -> if (minutes == 1L) "1 minute" else "$minutes minutes"
        else -> "under a minute"
    }
}
