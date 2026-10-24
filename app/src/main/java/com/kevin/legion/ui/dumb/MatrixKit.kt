package com.kevin.legion.ui.dumb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One Matrix screen: a title, a Back row, and a scrolling body. System Back calls [onBack] too, so a
 * screen that has an inner level (an open list) can step out of it first by passing its own handler.
 */
@Composable
internal fun MatrixScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .background(MatrixColors.ground)
            .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = MatrixColors.text,
                fontFamily = DumbMono,
                fontSize = 26.sp,
                modifier = Modifier.weight(1f),
            )
            MatrixRow("Back", onBack, tone = MatrixTone.DIM)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = content)
    }
}

/** A tappable text row, at least 44dp tall. [detail] is a second dimmer line under the label. */
@Composable
internal fun MatrixRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: MatrixTone = MatrixTone.HEADING,
    detail: String? = null,
) {
    Column(
        modifier
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = tone.color(), fontFamily = DumbMono, fontSize = 18.sp)
        detail?.let { Text(it, color = MatrixColors.dim, fontFamily = DumbMono, fontSize = 13.sp) }
    }
}

/** A plain text line that does nothing when tapped: a heading, a status, a sentence of what did not happen. */
@Composable
internal fun MatrixNote(text: String, tone: MatrixTone = MatrixTone.BODY) {
    Text(
        text,
        color = tone.color(),
        fontFamily = DumbMono,
        fontSize = if (tone == MatrixTone.HEADING) 16.sp else 14.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** A labelled one-line text field. Enter runs [onDone]. Underline rule only, not a box. */
@Composable
internal fun MatrixField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = MatrixColors.dim, fontFamily = DumbMono, fontSize = 13.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = MatrixColors.text, fontFamily = DumbMono, fontSize = 18.sp),
            cursorBrush = SolidColor(MatrixColors.text),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(top = 6.dp),
        )
        Row(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(MatrixColors.outline)) {}
    }
}
