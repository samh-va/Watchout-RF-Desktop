package com.watchoutrf.desktop.ui.components.spectrum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.watchoutrf.desktop.domain.model.Marker
import com.watchoutrf.desktop.domain.model.MarkerColor
import com.watchoutrf.desktop.ui.theme.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
@Composable
fun MarkerManagerDialog(
    markers: List<Marker>,
    onDismiss: () -> Unit,
    onUpdateMarkerLabel: (Int, String) -> Unit,
    onRemoveMarker: (Int) -> Unit,
    onAddMarker: (Long, String, MarkerColor?) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DarkSurface,
            modifier = Modifier
                .width(585.dp)
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Marker Manager",
                        style = MaterialTheme.typography.titleMedium,
                        color = NeonGreen,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Markers List
                if (markers.isEmpty()) {
                    Text(
                        text = "No custom markers added yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDim,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(markers) { marker ->
                            MarkerItemRow(
                                marker = marker,
                                onUpdateLabel = { newLabel -> onUpdateMarkerLabel(marker.id, newLabel) },
                                onRemove = { onRemoveMarker(marker.id) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = GridLine)
                Spacer(modifier = Modifier.height(16.dp))

                // Add Marker Section
                AddMarkerSection(onAddMarker = onAddMarker)
            }
        }
    }
}

@Composable
private fun MarkerItemRow(
    marker: Marker,
    onUpdateLabel: (String) -> Unit,
    onRemove: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(marker.label) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Label or TextField
        if (isEditing) {
            OutlinedTextField(
                value = editText,
                onValueChange = { editText = it },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanBright,
                    unfocusedBorderColor = GridLine,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    onUpdateLabel(editText)
                    isEditing = false
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanBright, contentColor = DeepBlack),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("Save", fontSize = 12.sp)
            }
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = marker.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${String.format("%.3f", marker.frequencyHz / 1_000_000.0)} MHz / ${String.format("%.1f", marker.amplitudeDbm)} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            
            IconButton(
                onClick = { 
                    isEditing = true 
                    editText = marker.label
                }, 
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = AmberYellow, modifier = Modifier.size(18.dp))
            }
        }
        
        if (!isEditing) {
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = ErrorRed, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AddMarkerSection(
    onAddMarker: (Long, String, MarkerColor?) -> Unit
) {
    var freqText by remember { mutableStateOf("") }
    var labelText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var selectedColor by remember { mutableStateOf<MarkerColor?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Add Custom Marker",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            
            // Color Picker
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MarkerColor.entries.forEach { colorEnum ->
                    val colorValue = markerColorToComposeColor(colorEnum)
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(colorValue)
                            .clickable { selectedColor = colorEnum }
                            .then(
                                if (selectedColor == colorEnum) {
                                    Modifier.background(Color.White.copy(alpha = 0.5f), CircleShape)
                                } else Modifier
                            )
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = freqText,
                onValueChange = { 
                    freqText = it
                    errorText = null
                },
                placeholder = { Text("Freq (MHz)", fontSize = 12.sp) },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanBright,
                    unfocusedBorderColor = GridLine,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = labelText,
                onValueChange = { labelText = it },
                placeholder = { Text("Label", fontSize = 12.sp) },
                modifier = Modifier
                    .weight(1.5f)
                    .height(56.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanBright,
                    unfocusedBorderColor = GridLine,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    try {
                        val mhz = freqText.toFloat()
                        val hz = (mhz * 1_000_000.0).toLong()
                        onAddMarker(hz, labelText, selectedColor)
                        freqText = ""
                        labelText = ""
                    } catch (e: Exception) {
                        errorText = "Invalid frequency"
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DeepBlack),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.height(56.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("ADD", fontSize = 12.sp)
            }
        }
        
        if (errorText != null) {
            Text(
                text = errorText!!,
                style = MaterialTheme.typography.labelSmall,
                color = ErrorRed,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun markerColorToComposeColor(color: MarkerColor): Color {
    return when (color) {
        MarkerColor.RED -> ErrorRed
        MarkerColor.GREEN -> NeonGreen
        MarkerColor.BLUE -> CyanBright
        MarkerColor.YELLOW -> AmberYellow
        MarkerColor.ORANGE -> WarningOrange
        MarkerColor.CYAN -> CyanDim
        MarkerColor.MAGENTA -> Color(0xFFFF00FF)
        MarkerColor.WHITE -> TextPrimary
    }
}
