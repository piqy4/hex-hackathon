package com.hex.stenographer.ui.main

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column

@Composable
internal fun EditUtterance(text: String, speakerId: String, onSave: (String, String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(text) { mutableStateOf(text) }
    var speaker by remember(speakerId) { mutableStateOf(speakerId) }
    TextButton(onClick = { draft = text; speaker = speakerId; editing = true }) { Text("Редактировать") }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text("Текст реплики") },
        text = { Column {
            OutlinedTextField(value = speaker, onValueChange = { speaker = it }, label = { Text("Говорящий (speaker_1, speaker_2…)") })
            OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Расшифровка") })
        } },
        confirmButton = { TextButton(
            enabled = draft.isNotBlank() && speaker.matches(Regex("speaker_[1-9][0-9]*")),
            onClick = { onSave(draft, speaker); editing = false }
        ) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Отмена") } },
    )
}
