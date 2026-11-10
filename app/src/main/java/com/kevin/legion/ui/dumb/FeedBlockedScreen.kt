package com.kevin.legion.ui.dumb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.legion.dumb.DumbFormat
import com.kevin.legion.dumb.FeedPassController
import com.kevin.legion.dumb.FeedPassRule
import com.kevin.legion.dumb.PassResult
import kotlinx.coroutines.delay

/** The screen's state, on saveable slots; every rule is re-checked by [FeedPassController]. */
private class PassScreenState(
    private val pass: FeedPassController,
    private val packageName: String,
    private val appLabel: String,
    private val onOpen: () -> Boolean,
) {
    var asking by mutableStateOf(pass.waitRemainingMs(packageName) != null)
    var waitLeftMs by mutableStateOf(pass.waitRemainingMs(packageName) ?: 0L)
    var typed by mutableStateOf("")
    var note by mutableStateOf<String?>(null)

    val matches: Boolean get() = FeedPassRule.sentenceMatches(typed, appLabel)
    val canOpen: Boolean get() = asking && waitLeftMs == 0L && matches

    /** One tick of the countdown. Returns false when there is nothing left to count. */
    fun tick(): Boolean {
        val left = pass.waitRemainingMs(packageName)
        if (left == null) {
            asking = false
            note = "The request lapsed. Ask again if you still want it."
            return false
        }
        waitLeftMs = left
        return left > 0L
    }

    fun ask() {
        when (val result = pass.request(packageName)) {
            is PassResult.Waiting -> {
                waitLeftMs = result.remainingMs
                note = null
                asking = true
            }
            is PassResult.AlreadyOpen -> if (!onOpen()) note = "$appLabel already has a pass."
            else -> note = passRefusalWords(result)
        }
    }

    fun grant() {
        val result = pass.grant(packageName, appLabel, typed)
        // A request that lapsed while he typed: show "Ask" again rather than a dead Open button.
        if (result == PassResult.NotRequested) asking = false
        note = grantWords(result, appLabel, onOpen)
    }

    fun type(text: String) {
        typed = text
        note = null
    }
}

/**
 * The normal-mode bounce screen (ADR 0066): "<App> is blocked", "Go back" (primary), and a quieter
 * "Ask for a 15 min pass". The pass runs the same 60 second wall-clock wait as leaving Matrix mode,
 * then a typed sentence, then opens the app. The controller re-checks every rule; this is only the view.
 */
@Composable
internal fun FeedBlockedScreen(
    appLabel: String,
    pass: FeedPassController,
    packageName: String,
    onBack: () -> Unit,
    onOpen: () -> Boolean,
    /** "Breathe instead": box breathing in place of the app (Kevin, 2026-11-10). Null hides it. */
    onBreathe: (() -> Unit)? = null,
) {
    // Remembered, not saved: a pending request is re-read from the wall clock when the screen is rebuilt.
    val ui = remember(packageName) { PassScreenState(pass, packageName, appLabel, onOpen) }
    BackHandler(onBack = onBack)
    LaunchedEffect(ui.asking) {
        while (ui.asking && ui.tick()) delay(TICK_MS)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(DumbColors.ground)
            .padding(start = 28.dp, end = 28.dp, top = 56.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PassHeader(appLabel)
        when {
            !ui.asking -> ui.note?.let { Text(it, color = DumbColors.dim, fontSize = 14.sp) }
            ui.waitLeftMs > 0L -> PassCountdown(ui.waitLeftMs)
            else -> PassSentenceField(
                sentence = FeedPassRule.sentence(appLabel),
                typed = ui.typed,
                onTyped = ui::type,
                note = ui.note ?: if (ui.matches) "matches" else "not yet",
            )
        }
        Box(Modifier.weight(1f))
        PassButtons(
            appLabel = appLabel,
            asking = ui.asking,
            canOpen = ui.canOpen,
            onBack = onBack,
            onGrant = ui::grant,
            onAsk = ui::ask,
            onBreathe = onBreathe,
        )
    }
}

/** Opens the app on a grant; otherwise the refusal in words. Null when the app opened. */
private fun grantWords(result: PassResult, appLabel: String, onOpen: () -> Boolean): String? = when (result) {
    is PassResult.Granted, is PassResult.AlreadyOpen ->
        if (onOpen()) {
            null
        } else {
            "Pass granted, but Android would not say how to open $appLabel. Open it from your home screen " +
                "within 15 minutes."
        }
    else -> passRefusalWords(result)
}

@Composable
private fun PassHeader(appLabel: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$appLabel is blocked", color = DumbColors.text, fontSize = 28.sp, fontWeight = FontWeight.Medium)
        Text(
            "Feed apps are blocked all the time, not only in Matrix mode. A pass opens $appLabel for 15 " +
                "minutes after a 60 second wait and a sentence, then it is blocked again.",
            color = DumbColors.body,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        )
    }
}

@Composable
private fun PassButtons(
    appLabel: String,
    asking: Boolean,
    canOpen: Boolean,
    onBack: () -> Unit,
    onGrant: () -> Unit,
    onAsk: () -> Unit,
    onBreathe: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBreathe != null) {
            Text(
                "Breathe instead",
                color = DumbColors.text,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClick = onBreathe)
                    .padding(12.dp),
            )
        }
        if (canOpen) {
            PillButton("Open $appLabel for 15 minutes", onGrant, primary = false, modifier = Modifier.fillMaxWidth())
        }
        PillButton("Go back", onBack, primary = true, modifier = Modifier.fillMaxWidth())
        if (!asking) {
            Text(
                "Ask for a 15 min pass",
                color = DumbColors.dim,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClick = onAsk)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun PassCountdown(leftMs: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            DumbFormat.secondsCeil(leftMs).toString(),
            color = DumbColors.text,
            fontFamily = DumbMono,
            fontSize = 96.sp,
            letterSpacing = (-4).sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text("seconds. Put the phone down if you like.", color = DumbColors.dim, fontSize = 14.sp)
    }
}

@Composable
private fun PassSentenceField(sentence: String, typed: String, onTyped: (String) -> Unit, note: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Type: $sentence", color = DumbColors.dim, fontSize = 14.sp)
        BasicTextField(
            value = typed,
            onValueChange = onTyped,
            singleLine = true,
            textStyle = TextStyle(color = DumbColors.text, fontSize = 17.sp),
            cursorBrush = SolidColor(DumbColors.text),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(DumbColors.field)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
        Text(note, color = DumbColors.dim, fontSize = 13.sp, fontFamily = DumbMono)
    }
}

/** What did NOT happen, in words, for a refused pass. */
internal fun passRefusalWords(result: PassResult): String = when (result) {
    is PassResult.Granted, is PassResult.AlreadyOpen -> ""
    is PassResult.Waiting -> "Still waiting: ${DumbFormat.secondsCeil(result.remainingMs)} seconds left."
    PassResult.MatrixOn -> "No pass while Matrix mode is on. Leaving Matrix mode is the only way out."
    PassResult.NotRequested -> "The wait has not started, or it lapsed. Ask for a pass again."
    is PassResult.TooEarly -> "Still waiting: ${DumbFormat.secondsCeil(result.remainingMs)} seconds left."
    PassResult.WrongSentence -> "That is not the sentence yet."
}

private const val TICK_MS = 250L
