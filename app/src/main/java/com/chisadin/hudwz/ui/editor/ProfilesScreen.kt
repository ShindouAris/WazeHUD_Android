package com.chisadin.hudwz.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chisadin.hudwz.domain.HudProfile

@Composable
fun ProfilesScreen(
    profiles: List<HudProfile>,
    activeProfile: HudProfile,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: () -> Unit,
) {
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hồ sơ HUD", style = MaterialTheme.typography.headlineLarge)
                Text("Chọn một bố cục rồi mở Trình chỉnh sửa HUD ngang.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { dialog = ProfileDialog.Create }) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Text(" Tạo mới")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            items(profiles, key = { it.id }) { profile ->
                Card(onClick = { onSelect(profile.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = profile.id == activeProfile.id, onClick = { onSelect(profile.id) })
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleLarge)
                            Text("${profile.elements.count { it.visible }} thành phần đang hiện · tỷ lệ ${"%.0f".format(profile.hudScale * 100)}%")
                        }
                        IconButton(
                            onClick = { dialog = ProfileDialog.Rename(profile) },
                            modifier = Modifier.semantics { contentDescription = "Đổi tên ${profile.name}" },
                        ) { Icon(Icons.Rounded.Edit, contentDescription = null) }
                        IconButton(
                            onClick = { onDuplicate(profile.id) },
                            modifier = Modifier.semantics { contentDescription = "Nhân bản ${profile.name}" },
                        ) { Icon(Icons.Rounded.ContentCopy, contentDescription = null) }
                        IconButton(
                            onClick = { onDelete(profile.id) },
                            enabled = profiles.size > 1,
                            modifier = Modifier.semantics { contentDescription = "Xóa ${profile.name}" },
                        ) { Icon(Icons.Rounded.Delete, contentDescription = null) }
                    }
                }
            }
        }
        FilledTonalButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Edit, contentDescription = null)
            Text(" Mở Trình chỉnh sửa HUD · ${activeProfile.name}")
        }
    }
    dialog?.let { state ->
        var name by remember(state) { mutableStateOf(if (state is ProfileDialog.Rename) state.profile.name else "") }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (state is ProfileDialog.Create) "Tạo hồ sơ" else "Đổi tên hồ sơ") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Tên hồ sơ") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (state is ProfileDialog.Create) onCreate(name) else onRename((state as ProfileDialog.Rename).profile.id, name)
                    dialog = null
                }, enabled = name.isNotBlank()) { Text("Lưu") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Hủy") } },
        )
    }
}

private sealed interface ProfileDialog {
    data object Create : ProfileDialog
    data class Rename(val profile: HudProfile) : ProfileDialog
}
