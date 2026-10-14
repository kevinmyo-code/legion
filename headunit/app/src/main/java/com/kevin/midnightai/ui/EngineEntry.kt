package com.kevin.midnightai.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kevin.midnightai.backend.engine.EngineAuth
import com.kevin.midnightai.backend.engine.EngineConfig
import com.kevin.midnightai.backend.engine.EngineHttp
import com.kevin.midnightai.backend.engine.EngineMcpClient
import com.kevin.midnightai.backend.engine.McpListResult
import com.kevin.midnightai.backend.engine.LoginResult
import com.kevin.midnightai.backend.engine.MeResult
import com.kevin.midnightai.ui.theme.AriaColors
import kotlinx.coroutines.launch

/**
 * Settings entry for the LEGION Django engine: where it lives, and who this head unit is signed in
 * as. Without it every engine tool fails in `EngineHttp.send` with "No engine address saved on
 * this device yet", which is exactly what a driver heard when asking for the grocery list.
 *
 * Logic ported from LEGION's KeyScreen engine block (same [EngineAuth] / [EngineConfig] flow),
 * restyled to this app's tokens. The failure sentences are [EngineAuth]'s own and are shown
 * verbatim, because each one already says what did NOT happen (CLAUDE.md section 7).
 *
 * **The password lives only in this composable's state.** It is cleared on a successful sign-in,
 * never logged, never written anywhere: the only thing persisted is the device token, encrypted by
 * [EngineConfig].
 */
@Composable
fun EngineEntry(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember { EngineConfig(context) }
    val auth = remember { EngineAuth(config) }

    var urlText by remember { mutableStateOf(config.baseUrl()) }
    var urlStatus by remember { mutableStateOf<String?>(null) }
    var configured by remember { mutableStateOf(config.isConfigured()) }
    var signedIn by remember { mutableStateOf(config.isSignedIn()) }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testIsError by remember { mutableStateOf(false) }

    // What the engine itself says about this token, re-read on entry rather than trusted from a
    // stored flag: a token can be revoked server-side while this screen is closed.
    var me by remember { mutableStateOf<MeResult?>(null) }

    suspend fun refreshMe() {
        me = if (config.isConfigured() && config.isSignedIn()) auth.me() else null
    }

    LaunchedEffect(configured, signedIn) {
        if (configured && signedIn) refreshMe() else me = null
    }

    // A token belongs to the engine that minted it. Pointing this device at a different address
    // drops it, or the old engine's bearer token would ride along on every request to the new one.
    fun saveUrlDroppingStaleSession(): Boolean {
        val before = config.baseUrl()
        val saved = config.saveBaseUrl(urlText)
        if (saved && config.baseUrl() != before && config.isSignedIn()) {
            config.clearSession()
            signedIn = false
            me = null
        }
        return saved
    }

    fun saveUrl() {
        val saved = saveUrlDroppingStaleSession()
        urlStatus = if (saved) {
            urlText = config.baseUrl()
            "Saved."
        } else {
            "That didn't look like an address - start with https://."
        }
        configured = config.isConfigured()
    }

    fun signIn() {
        working = true
        status = null
        scope.launch {
            // Saves the address first so one tap does both; a typed-but-unsaved URL otherwise
            // reads "No engine address saved yet" from login, which looks like a bug.
            if (saveUrlDroppingStaleSession()) configured = config.isConfigured()
            val deviceName = "Head unit ${Build.MODEL.orEmpty()}".trim()
            when (val result = auth.login(email, password, deviceName)) {
                is LoginResult.Ok -> {
                    status = "Signed in."
                    statusIsError = false
                    password = ""
                    signedIn = config.isSignedIn()
                    refreshMe()
                }
                is LoginResult.Refused -> { status = result.message; statusIsError = true }
                is LoginResult.Unreachable -> { status = result.message; statusIsError = true }
            }
            working = false
        }
    }

    // The exact call the voice agent makes first (`tools/list` on /mcp), through the same
    // transport, but with the cache read bypassed and its own client instance so the voice
    // agent's 5-minute cache is untouched. Shows the failure's own sentence: that is the
    // conclusive answer the voice model's paraphrase cannot give.
    fun testConnection() {
        testing = true
        testResult = null
        scope.launch {
            val client = EngineMcpClient(EngineHttp(config), config::baseUrl)
            when (val listed = client.listTools(fresh = true)) {
                is McpListResult.Listed -> {
                    testResult = "Engine OK: ${listed.tools.size} tools available to the voice agent."
                    testIsError = false
                }
                is McpListResult.Failed -> {
                    testResult = listed.failure.sentence
                    testIsError = true
                }
            }
            testing = false
        }
    }

    fun signOut() {
        working = true
        testResult = null
        scope.launch {
            auth.logout()
            signedIn = config.isSignedIn()
            me = null
            status = "Signed out of this device."
            statusIsError = false
            working = false
        }
    }

    Column(modifier) {
        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it; urlStatus = null },
            label = { Text("Engine address (https://...)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrect = false,
                keyboardType = KeyboardType.Uri,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row {
            OutlinedButton(onClick = ::saveUrl, enabled = urlText.isNotBlank()) { Text("SAVE ADDRESS") }
        }
        urlStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = AriaColors.TextDim)
        }
        Spacer(Modifier.height(16.dp))

        Text(
            text = when (val state = me) {
                null -> when {
                    !configured -> "No engine address yet."
                    signedIn -> "Checking..."
                    else -> "Not signed in."
                }
                is MeResult.Ok -> "Signed in as ${state.email} on this device."
                is MeResult.Refused -> state.message
                is MeResult.Unreachable -> state.message
            },
            style = MaterialTheme.typography.bodySmall,
            color = when (me) {
                is MeResult.Ok, null -> AriaColors.TextDim
                else -> AriaColors.ScanRed
            },
        )
        Spacer(Modifier.height(10.dp))

        if (signedIn) {
            Row {
                OutlinedButton(onClick = ::signOut, enabled = !working) { Text("SIGN OUT") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = ::testConnection, enabled = !working && !testing) {
                    Text(if (testing) "TESTING..." else "TEST CONNECTION")
                }
            }
            testResult?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (testIsError) AriaColors.ScanRed else AriaColors.NeonCyan,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        } else {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Email,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Password,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = ::signIn,
                enabled = !working && urlText.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
            ) { Text(if (working) "SIGNING IN..." else "SIGN IN") }
        }
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusIsError) AriaColors.ScanRed else AriaColors.NeonCyan,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
