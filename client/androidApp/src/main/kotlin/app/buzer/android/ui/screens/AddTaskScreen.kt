package app.buzer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp

@Composable
fun AddTaskScreen(onBack: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var assignee by remember { mutableStateOf("") }
    var dueDatetime by remember { mutableStateOf("") }
    var durationMinutes by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Add committed task")
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(assignee, { assignee = it }, label = { Text("Assignee") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            dueDatetime,
            { dueDatetime = it },
            label = { Text("Exact deadline") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            durationMinutes,
            { durationMinutes = it },
            label = { Text("Duration minutes") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
            Text("Save")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}
