package com.watchoutrf.desktop.ui.components.spectrum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.watchoutrf.desktop.data.shows.ShowConfig
import com.watchoutrf.desktop.ui.theme.*

/**
 * Save the current setup as a named show, and search / load / delete saved shows.
 *
 * @param onSave  returns null on success or an error message to display.
 */
@Composable
fun ShowsDialog(
    shows: List<ShowConfig>,
    currentSummary: String,
    onSave: (name: String, notes: String) -> String?,
    onLoad: (ShowConfig) -> Unit,
    onDelete: (ShowConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    val willReplace = name.isNotBlank() && shows.any { it.name.equals(name.trim(), ignoreCase = true) }
    val filtered = remember(shows, query) { shows.filter { it.matches(query) } }

    // The platform default caps dialogs at a narrow width that truncates the show details
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DarkSurface,
            modifier = Modifier.width(620.dp).wrapContentHeight(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Shows",
                        style = MaterialTheme.typography.titleMedium,
                        color = NeonGreen,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ─── Save the current setup ───
                SectionLabel("SAVE CURRENT SETUP")
                Text(
                    text = currentSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DialogTextField(
                        value = name,
                        onValueChange = { name = it; message = null },
                        label = "Show name",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    DialogTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = "Notes (venue, gear)",
                        modifier = Modifier.weight(1.3f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val error = onSave(name, notes)
                            messageIsError = error != null
                            message = error ?: "Saved \"${name.trim()}\"."
                            if (error == null) {
                                name = ""
                                notes = ""
                            }
                        },
                        enabled = name.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DeepBlack),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text(if (willReplace) "REPLACE" else "SAVE", style = MaterialTheme.typography.labelMedium)
                    }
                }
                val hint = message ?: if (willReplace) "A show with this name exists and will be replaced." else null
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message != null && !messageIsError) NeonGreen
                                else if (messageIsError) ErrorRed else AmberYellow,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = GridLine)
                Spacer(modifier = Modifier.height(14.dp))

                // ─── Saved shows ───
                SectionLabel("SAVED SHOWS (${shows.size})")
                DialogTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Search by name, notes, marker or date",
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (filtered.isEmpty()) {
                    Text(
                        text = if (shows.isEmpty()) "No shows saved yet." else "No show matches \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDim,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filtered, key = { it.name.lowercase() }) { show ->
                            ShowRow(
                                show = show,
                                onLoad = { onLoad(show) },
                                onDelete = { onDelete(show) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowRow(show: ShowConfig, onLoad: () -> Unit, onDelete: () -> Unit) {
    // Deleting takes two clicks so a show is not lost by accident
    var confirmDelete by remember(show.name) { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = show.name,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val range = show.frequencyRange
            val markers = if (show.markers.size == 1) "1 marker" else "${show.markers.size} markers"
            Text(
                text = "${show.formatSavedAt()}  ·  $markers  ·  ${range.formatStart()} – ${range.formatEnd()}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (show.notes.isNotBlank()) {
                Text(
                    text = show.notes,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (confirmDelete) {
            TextButton(onClick = { confirmDelete = false }) {
                Text("Cancel", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = onDelete) {
                Text("Delete", color = ErrorRed, style = MaterialTheme.typography.labelMedium)
            }
        } else {
            IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete show", tint = TextSecondary)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Button(
                onClick = onLoad,
                colors = ButtonDefaults.buttonColors(containerColor = CyanBright, contentColor = DeepBlack),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Text("LOAD", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextDim,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = leadingIcon,
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CyanBright,
            unfocusedBorderColor = GridLine,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedLabelColor = CyanBright,
            unfocusedLabelColor = TextSecondary,
            cursorColor = CyanBright,
        ),
        modifier = modifier,
    )
}
