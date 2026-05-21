package com.hydrobuddy.bt

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AccentBlue = Color(0xFF338AB8)
private val TitleBlue = Color(0xFF66B0DE)
private val TextBlue = Color(0xFF184B70)
private val MutedText = Color(0xFF9CB0C0)
private val DangerRed = Color(0xFFD95C5C)

@Composable
fun SettingsScreen(
    statusText: String,
    pairedDevices: List<BluetoothDevice>,
    connectedDeviceAddress: String?,
    userProfile: UserProfile?,
    tracker: WaterTrackerController?,
    onToggleReminders: (Boolean) -> Unit,
    onToggleVibration: (Boolean) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleConnection: (BluetoothDevice) -> Unit,
    onResetData: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenHorizontalPadding, vertical = ScreenTopPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsHeader(onBack = onBack)

        if (userProfile != null) {
            SectionTitle("Profile")
            ProfileRow(label = "Gender", value = userProfile.gender.replaceFirstChar { it.uppercase() })
            ProfileRow(label = "Height", value = "${userProfile.heightCm} cm")
            ProfileRow(label = "Weight", value = "${userProfile.weightKg} kg")
            HorizontalDivider()
        }

        if (tracker != null) {
            SectionTitle("Reminders")
            ToggleRow(
                label = "Reminders",
                enabled = tracker.remindersEnabled,
                onToggle = onToggleReminders
            )
            ToggleRow(
                label = "Vibration",
                enabled = tracker.vibrationEnabled,
                onToggle = onToggleVibration
            )
            HorizontalDivider()
        }

        SectionTitle("Arduino connection")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Paired devices", color = TextBlue, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onRefresh, modifier = Modifier.height(22.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh paired devices", tint = TextBlue)
            }
        }
        if (
            statusText.startsWith("Bluetooth", ignoreCase = true) ||
            statusText.startsWith("Enable Bluetooth", ignoreCase = true) ||
            statusText.startsWith("Connect", ignoreCase = true)
        ) {
            Text(statusText, style = MaterialTheme.typography.bodySmall, color = MutedText)
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(pairedDevices) { device ->
                DeviceRow(
                    device = device,
                    connected = connectedDeviceAddress == device.address,
                    onToggle = { onToggleConnection(device) }
                )
            }
        }
        HorizontalDivider()

        SectionTitle("Data")
        Button(
            onClick = onResetData,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
        ) {
            Text("Reset all data", color = Color.White)
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = TitleBlue
        )
        Box(
            modifier = Modifier
                .size(HeaderIconSize)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = TitleBlue)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = TextBlue,
        style = MaterialTheme.typography.titleSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    )
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextBlue, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp))
        Text(value, color = MutedText, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp))
    }
}

@Composable
private fun ToggleRow(label: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextBlue, style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = { onToggle(!enabled) },
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (enabled) AccentBlue else Color(0xFFCDD9E2)
            )
        ) {
            Text(if (enabled) "On" else "Off", color = Color.White)
        }
    }
}

@Composable
private fun DeviceRow(device: BluetoothDevice, connected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val name = try {
                device.name ?: "(No name)"
            } catch (_: SecurityException) {
                "(Permission needed)"
            }
            Text(name, color = TextBlue)
            Text(device.address, style = MaterialTheme.typography.bodySmall, color = MutedText)
        }
        Button(
            onClick = onToggle,
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
        ) {
            Text(if (connected) "Disconnect" else "Connect")
        }
    }
    HorizontalDivider()
}
