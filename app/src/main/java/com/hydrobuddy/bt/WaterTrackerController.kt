package com.hydrobuddy.bt

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.math.roundToInt

/**
 * Stateful host for buddy + log entries. Owns:
 *   - profile-driven body multiplier
 *   - buddy health (live + persisted)
 *   - log entries (sip + preset)
 *   - reminder bookkeeping
 *
 * Persistence lives in a single SharedPreferences bag to stay slim.
 */
class WaterTrackerController(
    private val prefs: SharedPreferences,
    private val profile: UserProfile
) {
    private val bodyMultiplier: Float = calculateBodyMultiplier(profile.hiddenDrinkBaselineMl)

    private var dateKey: String = todayDateKey()
    private var buddy: BuddyState = BuddyState(
        health = INITIAL_BUDDY_HEALTH,
        lastDrinkAt = System.currentTimeMillis(),
        lastUpdatedAt = System.currentTimeMillis(),
        lastReminderAt = null
    )

    var remindersEnabled: Boolean = prefs.getBoolean("reminders_enabled", true)
        private set
    var vibrationEnabled: Boolean = prefs.getBoolean("vibration_enabled", true)
        private set

    init {
        load()
        ensureDayBoundary(System.currentTimeMillis())
    }

    fun snapshot(now: Long = System.currentTimeMillis()): BuddySnapshot {
        ensureDayBoundary(now)
        updateBuddy(now)
        val rounded = buddy.health.roundToInt().coerceIn(0, 100)
        return BuddySnapshot(
            health = rounded,
            healthFraction = (buddy.health / 100f).coerceIn(0f, 1f),
            mood = BuddyMood.fromHealth(rounded)
        )
    }

    fun updateBuddy(now: Long = System.currentTimeMillis()) {
        if (now <= buddy.lastUpdatedAt) return
        val newHealth = applyDepletionBetween(
            health = buddy.health,
            fromMillis = buddy.lastUpdatedAt,
            toMillis = now,
            lastDrinkAt = buddy.lastDrinkAt,
            bodyMultiplier = bodyMultiplier
        )
        buddy = buddy.copy(health = newHealth, lastUpdatedAt = now)
        persist()
    }

    /**
     * Log a single sip. Returns the affected entry (new or merged into the
     * recent group within [SIP_GROUPING_WINDOW_MS]).
     */
    fun logSip(
        currentEntries: MutableList<LogEntry>,
        now: Long = System.currentTimeMillis()
    ): LogEntry {
        updateBuddy(now)
        val gain = SIP_HEALTH_GAIN
        buddy = buddy.copy(
            health = (buddy.health + gain).coerceIn(0f, 100f),
            lastDrinkAt = now,
            lastUpdatedAt = now
        )
        persist()

        val mostRecent = currentEntries.maxByOrNull { it.timestampMillis }
        return if (shouldGroupWithLastSip(mostRecent, now)) {
            val merged = mostRecent!!.copy(
                timestampMillis = now,
                sipCount = (mostRecent.sipCount ?: 1) + 1,
                healthGain = mostRecent.healthGain + gain
            )
            val idx = currentEntries.indexOfFirst { it.id == mostRecent.id }
            currentEntries[idx] = merged
            merged
        } else {
            val entry = LogEntry(
                timestampMillis = now,
                type = LogEntryType.Sip,
                sipCount = 1,
                amountMl = null,
                healthGain = gain
            )
            currentEntries.add(0, entry)
            entry
        }
    }

    fun logPresetDrink(
        preset: PresetDrink,
        currentEntries: MutableList<LogEntry>,
        now: Long = System.currentTimeMillis()
    ): LogEntry {
        updateBuddy(now)
        buddy = buddy.copy(
            health = (buddy.health + preset.healthGain).coerceIn(0f, 100f),
            lastDrinkAt = now,
            lastUpdatedAt = now
        )
        persist()
        val entry = LogEntry(
            timestampMillis = now,
            type = LogEntryType.Preset,
            sipCount = null,
            amountMl = preset.amountMl,
            healthGain = preset.healthGain
        )
        currentEntries.add(0, entry)
        return entry
    }

    /**
     * Replace an entry (type / count / amount), recompute its healthGain, then
     * replay today's history to keep buddy honest.
     */
    fun applyEntryEdit(
        entries: MutableList<LogEntry>,
        targetId: String,
        newType: LogEntryType,
        newSipCount: Int?,
        newPreset: PresetDrink?,
        now: Long = System.currentTimeMillis()
    ): LogEntry? {
        val idx = entries.indexOfFirst { it.id == targetId }
        if (idx < 0) return null
        val old = entries[idx]
        val updated = when (newType) {
            LogEntryType.Sip -> {
                val count = (newSipCount ?: 1).coerceAtLeast(1)
                old.copy(
                    type = LogEntryType.Sip,
                    sipCount = count,
                    amountMl = null,
                    healthGain = count * SIP_HEALTH_GAIN
                )
            }
            LogEntryType.Preset -> {
                val p = newPreset ?: return null
                old.copy(
                    type = LogEntryType.Preset,
                    sipCount = null,
                    amountMl = p.amountMl,
                    healthGain = p.healthGain
                )
            }
        }
        entries[idx] = updated
        recalculateBuddy(entries, now)
        return updated
    }

    fun deleteEntry(entries: MutableList<LogEntry>, targetId: String, now: Long = System.currentTimeMillis()) {
        if (entries.removeAll { it.id == targetId }) {
            recalculateBuddy(entries, now)
        }
    }

    fun recalculateBuddy(entries: List<LogEntry>, now: Long = System.currentTimeMillis()) {
        buddy = recalculateBuddyFromHistory(
            entries = entries,
            bodyMultiplier = bodyMultiplier,
            now = now,
            lastReminderAt = buddy.lastReminderAt
        )
        persist()
    }

    fun shouldRemind(now: Long = System.currentTimeMillis()): Boolean {
        if (!remindersEnabled) return false
        ensureDayBoundary(now)
        updateBuddy(now)
        val minutesSinceDrink = (now - buddy.lastDrinkAt) / 60_000f
        val minutesSinceReminder = buddy.lastReminderAt?.let { (now - it) / 60_000f }
            ?: Float.MAX_VALUE
        val healthLow = buddy.health < LOW_HEALTH_THRESHOLD
        val gapLong = minutesSinceDrink >= MAX_TIME_WITHOUT_DRINK_MINUTES
        return (healthLow || gapLong) && minutesSinceReminder >= MIN_REMINDER_GAP_MINUTES
    }

    fun recordReminderSent(now: Long = System.currentTimeMillis()) {
        buddy = buddy.copy(lastReminderAt = now)
        persist()
    }

    fun setRemindersEnabled(enabled: Boolean) {
        remindersEnabled = enabled
        prefs.edit { putBoolean("reminders_enabled", enabled) }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        vibrationEnabled = enabled
        prefs.edit { putBoolean("vibration_enabled", enabled) }
    }

    private fun ensureDayBoundary(now: Long) {
        val today = todayDateKey(now)
        if (today == dateKey) return
        dateKey = today
        persist()
    }

    private fun load() {
        dateKey = prefs.getString("date_key", todayDateKey()) ?: todayDateKey()
        val now = System.currentTimeMillis()
        buddy = BuddyState(
            health = prefs.getFloat("buddy_health", INITIAL_BUDDY_HEALTH),
            lastDrinkAt = prefs.getLong("buddy_last_drink_at", now),
            lastUpdatedAt = prefs.getLong("buddy_last_updated_at", now),
            lastReminderAt = prefs.getLong("buddy_last_reminder_at", 0L).takeIf { it > 0L }
        )
    }

    private fun persist() {
        prefs.edit {
            putString("date_key", dateKey)
            putFloat("buddy_health", buddy.health)
            putLong("buddy_last_drink_at", buddy.lastDrinkAt)
            putLong("buddy_last_updated_at", buddy.lastUpdatedAt)
            putLong("buddy_last_reminder_at", buddy.lastReminderAt ?: 0L)
            putBoolean("reminders_enabled", remindersEnabled)
            putBoolean("vibration_enabled", vibrationEnabled)
        }
    }

    companion object {
        fun create(context: Context, profile: UserProfile): WaterTrackerController =
            WaterTrackerController(
                prefs = context.getSharedPreferences("hydro_buddy_state", Context.MODE_PRIVATE),
                profile = profile
            )
    }
}
