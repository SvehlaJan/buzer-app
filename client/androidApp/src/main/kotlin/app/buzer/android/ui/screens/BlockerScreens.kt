package app.buzer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.buzer.android.ui.reschedule.RescheduleInputParser
import app.buzer.android.ui.reschedule.RescheduleParseResult
import app.buzer.shared.model.RescheduleTaskRequest

@Composable
fun StartBlockerScreen(
    onBack: () -> Unit,
    onStart: () -> Unit = {},
    onPostpone: () -> Unit = {},
    showBack: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Start blocker")
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("I'm starting")
        }
        OutlinedButton(onClick = onPostpone, modifier = Modifier.fillMaxWidth()) {
            Text("Postpone 5 min")
        }
        if (showBack) {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}

@Composable
fun DeadlineBlockerScreen(
    onBack: () -> Unit,
    onDone: () -> Unit = {},
    onExtend: () -> Unit = {},
    onReschedule: (RescheduleTaskRequest) -> Unit = {},
    showBack: Boolean = true
) {
    var rescheduleVisible by remember { mutableStateOf(false) }
    var deadlineText by remember { mutableStateOf("") }
    var durationText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (rescheduleVisible) {
            Text("Reschedule task")
            OutlinedTextField(
                value = deadlineText,
                onValueChange = {
                    deadlineText = it
                    errorText = null
                },
                label = { Text("New deadline") },
                supportingText = { Text("Format: YYYY-MM-DD HH:MM") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "New deadline input" }
            )
            OutlinedTextField(
                value = durationText,
                onValueChange = {
                    durationText = it
                    errorText = null
                },
                label = { Text("Duration minutes") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Duration minutes input" }
            )
            errorText?.let { Text(it) }
            Button(
                onClick = {
                    when (val result = RescheduleInputParser.parse(deadlineText, durationText)) {
                        is RescheduleParseResult.Valid -> onReschedule(result.request)
                        is RescheduleParseResult.Invalid -> errorText = result.message
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save reschedule")
            }
            OutlinedButton(
                onClick = {
                    rescheduleVisible = false
                    errorText = null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        } else {
            Text("Deadline blocker")
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
            OutlinedButton(onClick = onExtend, modifier = Modifier.fillMaxWidth()) {
                Text("Extend 30 min")
            }
            OutlinedButton(
                onClick = {
                    rescheduleVisible = true
                    errorText = null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reschedule")
            }
            if (showBack) {
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
            }
        }
    }
}
