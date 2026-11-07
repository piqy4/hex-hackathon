package com.hex.stenographer.ui.main

import androidx.compose.runtime.*
import androidx.compose.material3.*

@Composable
internal fun EditUtterance(text: String, onSave: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(text) { mutableStateOf(text) }
    TextButton(onClick = { draft = text; editing = true }) { Text("Редактировать") }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text("Текст реплики") },
        text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Расшифровка") }) },
        confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = { onSave(draft); editing = false }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Отмена") } },
    )
}
