package com.kevin.legion.ui.grades

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.soft.SoftColors

/** HOME's More sheet row that opens the Grades screen, the same shape as the Claude session row. */
@Composable
fun GradesMoreRow(onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Grades", color = SoftColors.text)
            Text("Your Canvas grades, and what you need.", color = SoftColors.text.copy(alpha = 0.7f))
        }
        OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = 44.dp)) { Text("Open") }
    }
}
