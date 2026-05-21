package com.hydrobuddy.bt

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private val btAdapter: BluetoothAdapter? by lazy {
        getSystemService(BluetoothManager::class.java)?.adapter
    }
    private val btClient = BluetoothClassicClient()
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) loadPairedDevices() }

    private val pairedDevices = mutableStateListOf<BluetoothDevice>()
    private val logEntries = mutableStateListOf<LogEntry>()

    private var statusText by mutableStateOf("Idle")
    private var connectedDeviceAddress by mutableStateOf<String?>(null)
    private var appScreen by mutableStateOf(AppScreen.Onboarding)
    private var userProfile by mutableStateOf<UserProfile?>(null)
    private var tracker by mutableStateOf<WaterTrackerController?>(null)
    private var tick by mutableIntStateOf(0)
    private var feedbackTick by mutableIntStateOf(0)
    private var lastGain by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hydrateFromStorage()
        ensureBluetoothPermissionAndLoad()

        setContent {
            HydroBuddyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (appScreen) {
                        AppScreen.Onboarding -> OnboardingFlow(onComplete = ::onOnboardingDone)
                        AppScreen.Home -> HomeRoute()
                        AppScreen.Settings -> SettingsRoute()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        btClient.close()
        super.onDestroy()
    }

    @Composable
    private fun HomeRoute() {
        val activeTracker = tracker
        val profile = userProfile
        if (activeTracker == null || profile == null) return

        LaunchedEffect(Unit) {
            while (true) {
                delay(TRACKER_TICK_MS)
                activeTracker.updateBuddy()
                tick++
            }
        }
        LaunchedEffect(Unit) {
            HydrationNotifier.ensureChannel(this@MainActivity)
            while (true) {
                delay(REMINDER_CHECK_MS)
                if (activeTracker.shouldRemind()) {
                    activeTracker.recordReminderSent()
                    HydrationNotifier.showReminder(this@MainActivity, tick)
                    tick++
                }
            }
        }

        val snapshot = remember(tick, logEntries.size) { activeTracker.snapshot() }
        HydroBuddyHomeScreen(
            onOpenSettings = { appScreen = AppScreen.Settings },
            entries = logEntries,
            snapshot = snapshot,
            feedbackTick = feedbackTick,
            lastGain = lastGain,
            onSip = { handleSip(activeTracker) },
            onPreset = { preset -> handlePreset(activeTracker, preset) },
            onEditEntry = { entryId, type, sipCount, preset ->
                activeTracker.applyEntryEdit(logEntries, entryId, type, sipCount, preset)
                persistEntries()
                tick++
            },
            onDeleteEntry = { entryId ->
                activeTracker.deleteEntry(logEntries, entryId)
                persistEntries()
                tick++
            }
        )
    }

    private fun handleSip(activeTracker: WaterTrackerController) {
        activeTracker.logSip(logEntries)
        persistEntries()
        lastGain = SIP_HEALTH_GAIN
        feedbackTick++
        tick++
    }

    private fun handlePreset(activeTracker: WaterTrackerController, preset: PresetDrink) {
        activeTracker.logPresetDrink(preset, logEntries)
        persistEntries()
        lastGain = preset.healthGain
        feedbackTick++
        tick++
    }

    @Composable
    private fun SettingsRoute() {
        SettingsScreen(
            statusText = statusText,
            pairedDevices = pairedDevices,
            connectedDeviceAddress = connectedDeviceAddress,
            userProfile = userProfile,
            tracker = tracker,
            onToggleReminders = { enabled ->
                tracker?.setRemindersEnabled(enabled)
                tick++
            },
            onToggleVibration = { enabled ->
                tracker?.setVibrationEnabled(enabled)
                tick++
            },
            onBack = { appScreen = AppScreen.Home },
            onRefresh = { ensureBluetoothPermissionAndLoad() },
            onToggleConnection = { device ->
                if (connectedDeviceAddress == device.address && btClient.isConnected()) {
                    btClient.close()
                    connectedDeviceAddress = null
                    statusText = "Disconnected"
                } else {
                    connectToDevice(device)
                }
            },
            onResetData = {
                clearAllAppData()
                appScreen = AppScreen.Onboarding
            }
        )
    }

    private fun onOnboardingDone(gender: String, heightCm: Int, weightKg: Int) {
        val baseline = calculateHiddenDrinkBaselineMl(gender, heightCm, weightKg)
        val profile = UserProfile(
            gender = gender,
            heightCm = heightCm,
            weightKg = weightKg,
            hiddenDrinkBaselineMl = baseline
        )
        userProfile = profile
        tracker = WaterTrackerController.create(this, profile)
        persistProfile()
        appScreen = AppScreen.Home
    }

    private fun hydrateFromStorage() {
        val done = prefs.getBoolean("onboarding_done", false)
        if (!done) {
            appScreen = AppScreen.Onboarding
            return
        }
        val gender = prefs.getString("gender", null)
        val height = prefs.getInt("height_cm", 0)
        val weight = prefs.getInt("weight_kg", 0)
        if (gender == null || height <= 0 || weight <= 0) {
            appScreen = AppScreen.Onboarding
            return
        }
        val baseline = prefs.getInt("hidden_drink_baseline_ml", 0)
            .takeIf { it > 0 }
            ?: calculateHiddenDrinkBaselineMl(gender, height, weight)
        val profile = UserProfile(gender, height, weight, baseline)
        userProfile = profile
        tracker = WaterTrackerController.create(this, profile)
        loadEntries()
        appScreen = AppScreen.Home
    }

    private fun loadEntries() {
        logEntries.clear()
        val json = prefs.getString("entries_json", "[]") ?: "[]"
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val typeRaw = obj.optString("type", "preset")
            val type = runCatching { LogEntryType.valueOf(typeRaw.replaceFirstChar { it.uppercase() }) }
                .getOrDefault(LogEntryType.Preset)
            val amountMl = if (obj.has("amountMl") && !obj.isNull("amountMl")) obj.optInt("amountMl") else null
            val sipCount = if (obj.has("sipCount") && !obj.isNull("sipCount")) obj.optInt("sipCount") else null
            val storedGain = obj.optInt("healthGain", -1)
            val healthGain = if (storedGain >= 0) storedGain else derivedHealthGain(type, sipCount, amountMl)
            logEntries.add(
                LogEntry(
                    id = obj.optString("id"),
                    timestampMillis = obj.optLong("timestampMillis"),
                    type = type,
                    sipCount = sipCount,
                    amountMl = amountMl,
                    healthGain = healthGain
                )
            )
        }
    }

    private fun derivedHealthGain(type: LogEntryType, sipCount: Int?, amountMl: Int?): Int = when (type) {
        LogEntryType.Sip -> (sipCount ?: 1) * SIP_HEALTH_GAIN
        LogEntryType.Preset -> amountMl?.let { PresetDrink.fromAmount(it)?.healthGain } ?: 0
    }

    private fun persistEntries() {
        val arr = JSONArray()
        logEntries.forEach { e ->
            val obj = JSONObject()
                .put("id", e.id)
                .put("timestampMillis", e.timestampMillis)
                .put("type", e.type.name.lowercase())
                .put("healthGain", e.healthGain)
            if (e.sipCount != null) obj.put("sipCount", e.sipCount)
            if (e.amountMl != null) obj.put("amountMl", e.amountMl)
            arr.put(obj)
        }
        prefs.edit { putString("entries_json", arr.toString()) }
    }

    private fun persistProfile() {
        val profile = userProfile ?: return
        prefs.edit {
            putBoolean("onboarding_done", true)
            putString("gender", profile.gender)
            putInt("height_cm", profile.heightCm)
            putInt("weight_kg", profile.weightKg)
            putInt("hidden_drink_baseline_ml", profile.hiddenDrinkBaselineMl)
        }
    }

    private fun clearAllAppData() {
        btClient.close()
        connectedDeviceAddress = null
        logEntries.clear()
        userProfile = null
        tracker = null
        prefs.edit { clear() }
        getSharedPreferences("hydro_buddy_state", Context.MODE_PRIVATE).edit { clear() }
    }

    private fun ensureBluetoothPermissionAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        loadPairedDevices()
    }

    private fun loadPairedDevices() {
        pairedDevices.clear()
        if (!hasBluetoothConnectPermission()) {
            statusText = "Bluetooth permission required"
            return
        }
        val adapter = btAdapter
        if (adapter == null) {
            statusText = "Bluetooth not supported"
            return
        }
        try {
            if (!adapter.isEnabled) {
                statusText = "Enable Bluetooth on phone"
                return
            }
            pairedDevices.addAll(adapter.bondedDevices.sortedBy { it.name ?: it.address })
            statusText = "Found ${pairedDevices.size} paired device(s)"
        } catch (_: SecurityException) {
            statusText = "Bluetooth permission denied"
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasBluetoothConnectPermission()) {
            statusText = "Bluetooth permission required"
            return
        }
        statusText = "Connecting to ${device.name ?: device.address}..."
        thread {
            try {
                btClient.connect(device) { runOnUiThread { logSipFromArduino() } }
                runOnUiThread {
                    connectedDeviceAddress = device.address
                    statusText = "Connected: ${device.name ?: device.address}"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    connectedDeviceAddress = null
                    statusText = if (e is SecurityException) {
                        "Bluetooth permission denied"
                    } else {
                        "Connect failed: ${e.message}"
                    }
                }
            }
        }
    }

    private fun logSipFromArduino() {
        val activeTracker = tracker ?: return
        handleSip(activeTracker)
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val PREFS_NAME = "hydro_buddy_prefs"
    }
}

internal enum class AppScreen { Onboarding, Home, Settings }

data class UserProfile(
    val gender: String,
    val heightCm: Int,
    val weightKg: Int,
    val hiddenDrinkBaselineMl: Int
)
