// Android wrapper: loads/saves buddy health in prefs, applies sip/preset/history edits.

package com.hydrobuddy.bt

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.math.roundToInt

class WaterTrackerController(
    private val prefs: SharedPreferences,
    private val profile: UserProfile
) {
    private val bodyMultiplier: Float = calculateBodyMultiplier(profile.hiddenDrinkBaselineMl)

    private var dateKey: String = todayDateKey()
    private var buddy: BuddyState = BuddyState(
        health = INITIAL_BUDDY_HEALTH,
        lastDrinkAt = System.currentTimeMillis(),
        lastUpdatedAt = System.currentTimeMillis()
    )

    init {
        load()
        ensureDayBoundary(System.currentTimeMillis())
    }

    /** Applies time drain, returns UI snapshot (health 0–99, fraction, mood). */
    fun snapshot(now: Long = System.currentTimeMillis()): BuddySnapshot {
        ensureDayBoundary(now)
        updateBuddy(now)
        val rounded = buddy.health.roundToInt().coerceIn(0, MAX_BUDDY_HEALTH_INT)
        return BuddySnapshot(
            health = rounded,
            healthFraction = (buddy.health / MAX_BUDDY_HEALTH).coerceIn(0f, 1f),
            mood = BuddyMood.fromHealth(rounded)
        )
    }

    /** Minute-by-minute health drain since last update. */
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

    /** +8 health; may merge into previous sip row if within 30s. */
    fun logSip(
        currentEntries: MutableList<LogEntry>,
        now: Long = System.currentTimeMillis()
    ): LogEntry {
        updateBuddy(now)
        val gain = SIP_HEALTH_GAIN
        buddy = buddy.copy(
            health = (buddy.health + gain).coerceIn(0f, MAX_BUDDY_HEALTH),
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
            health = (buddy.health + preset.healthGain).coerceIn(0f, MAX_BUDDY_HEALTH),
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

    /** User changed a history row — recompute health from full day log. */
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
            now = now
        )
        persist()
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
            lastUpdatedAt = prefs.getLong("buddy_last_updated_at", now)
        )
    }

    private fun persist() {
        prefs.edit {
            putString("date_key", dateKey)
            putFloat("buddy_health", buddy.health)
            putLong("buddy_last_drink_at", buddy.lastDrinkAt)
            putLong("buddy_last_updated_at", buddy.lastUpdatedAt)
        }
    }

    companion object {
        const val STATE_PREFS_NAME = "hydro_buddy_state"

        fun create(context: Context, profile: UserProfile): WaterTrackerController =
            WaterTrackerController(
                prefs = context.getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE),
                profile = profile
            )
    }
}
