package com.watchoutrf.desktop.ui.components.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchoutrf.desktop.domain.model.ScanMode
import com.watchoutrf.desktop.ui.theme.*

@Composable
fun ScanModeSelector(
    currentMode: ScanMode,
    maxHoldEnabled: Boolean,
    autoPeaksEnabled: Boolean,
    onModeChanged: (ScanMode) -> Unit,
    onToggleMaxHold: () -> Unit,
    onToggleAutoPeaks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "SCAN MODE",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
            color = TextDim,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        ScanMode.entries.forEach { mode ->
            val isSelected = currentMode == mode
            val shape = RoundedCornerShape(8.dp)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(shape)
                    .then(
                        if (isSelected) {
                            Modifier
                                .background(CyanBright.copy(alpha = 0.08f), shape)
                                .border(1.dp, CyanBright, shape)
                        } else {
                            Modifier.background(DarkSurfaceVariant, shape)
                        }
                    )
                    .clickable { onModeChanged(mode) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = mode.name.replace('_', ' '),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) CyanBright else TextSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        ToggleRow(label = "Max Hold", checked = maxHoldEnabled, onToggle = onToggleMaxHold)
        // Shows / hides the automatically detected peak markers (P1…P4)
        ToggleRow(label = "Auto Peaks", checked = autoPeaksEnabled, onToggle = onToggleAutoPeaks)
    }
}

/** Compact label + switch row. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(34.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (checked) AmberYellow else TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.scale(0.75f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = NeonGreen,
                checkedTrackColor = NeonGreen.copy(alpha = 0.3f),
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = DarkSurfaceVariant,
                uncheckedBorderColor = GridLine,
            ),
        )
    }
}
