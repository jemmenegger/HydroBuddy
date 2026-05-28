// Pure buddy math — no Android imports. Used by WaterTrackerController and tests.

package com.hydrobuddy.bt

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt

const val INITIAL_BUDDY_HEALTH: Float = 75f
const val MAX_BUDDY_HEALTH: Float = 99f
const val MAX_BUDDY_HEALTH_INT: Int = 99
const val GRACE_PERIOD_MINUTES: Float = 20f // no drain for this long after a drink
const val BASE_DRAIN_PER_MINUTE: Float = 0.8f

const val SIP_HEALTH_GAIN: Int = 8
const val SIP_GROUPING_WINDOW_MS: Long = 30_000L // merge rapid sips into one history row

const val TRACKER_TICK_MS: Long = 60_000L // MainActivity refresh interval

enum class LogEntryType { Sip, Preset }

enum class PresetDrink(val amountMl: Int, val label: String, val healthGain: Int) {
    SmallGlass(150, "Small glass", 18),
    Glass(250, "Glass", 28),
    Can(330, "Can", 36),
    Bottle(500, "Bottle", 50);

    companion object {
        fun fromAmount(amountMl: Int): PresetDrink? =
            entries.firstOrNull { it.amountMl == amountMl }
    }
}

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampMillis: Long,
    val type: LogEntryType,
    val sipCount: Int? = null,
    val amountMl: Int? = null,
    val healthGain: Int
)

/** Live buddy numbers the controller keeps in memory + prefs. */
data class BuddyState(
    val health: Float,
    val lastDrinkAt: Long,
    val lastUpdatedAt: Long
)

/** Rounded health + mood label for the home UI. */
data class BuddySnapshot(
    val health: Int,
    val healthFraction: Float,
    val mood: BuddyMood
)

enum class BuddyMood(val label: String) {
    Happy("Buddy is happy"),
    Okay("Buddy is okay"),
    Low("Buddy could use a sip"),
    VeryLow("Buddy needs a refill");

    companion object {
        fun fromHealth(health: Int): BuddyMood = when {
            health >= 80 -> Happy
            health >= 55 -> Okay
            health >= 30 -> Low
            else -> VeryLow
        }
    }
}

/** Estimates daily fluid need from gender/height/weight; drives drain multiplier. */
fun calculateHiddenDrinkBaselineMl(
    gender: String,
    heightCm: Int,
    weightKg: Int
): Int {
    val base = if (gender.lowercase() == "female") 1600 else 1900
    val weightAdjustment = (weightKg - 70) * 10
    val heightAdjustment = (heightCm - 170) * 2
    val raw = (base + weightAdjustment + heightAdjustment).coerceIn(1200, 2800)
    return (raw / 50.0).roundToInt() * 50
}

/** Higher baseline → slightly faster health drain (0.85–1.25×). */
fun calculateBodyMultiplier(hiddenDrinkBaselineMl: Int): Float =
    (hiddenDrinkBaselineMl / 1900f).coerceIn(0.85f, 1.25f)

/** Drawable name for mascot image (state1 = happiest … state4 = saddest). */
fun buddyMascotState(health: Int): String = when {
    health >= 80 -> "state1"
    health >= 55 -> "state2"
    health >= 30 -> "state3"
    else -> "state4"
}

/**
 * Lowers health between two timestamps: 20 min grace after last drink, then
 * BASE_DRAIN_PER_MINUTE × minutes × bodyMultiplier.
 */
fun applyDepletionBetween(
    health: Float,
    fromMillis: Long,
    toMillis: Long,
    lastDrinkAt: Long,
    bodyMultiplier: Float
): Float {
    if (toMillis <= fromMillis) return health
    val graceEndsAt = lastDrinkAt + (GRACE_PERIOD_MINUTES * 60_000f).toLong()
    val drainStart = maxOf(fromMillis, graceEndsAt)
    if (drainStart >= toMillis) return health
    val minutes = (toMillis - drainStart) / 60_000f
    val loss = BASE_DRAIN_PER_MINUTE * minutes * bodyMultiplier
    return (health - loss).coerceIn(0f, MAX_BUDDY_HEALTH)
}

/** True if the newest log row is a sip within 30s — we bump sip count instead of new row. */
fun shouldGroupWithLastSip(lastEntry: LogEntry?, now: Long): Boolean {
    if (lastEntry == null) return false
    if (lastEntry.type != LogEntryType.Sip) return false
    return (now - lastEntry.timestampMillis) <= SIP_GROUPING_WINDOW_MS
}

/** Replays today's history to rebuild health after edit/delete. */
fun recalculateBuddyFromHistory(
    entries: List<LogEntry>,
    bodyMultiplier: Float,
    now: Long = System.currentTimeMillis()
): BuddyState {
    val zone = ZoneId.systemDefault()
    val startOfDay = Instant.ofEpochMilli(now)
        .atZone(zone)
        .toLocalDate()
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

    val todayEntries = entries
        .filter { it.timestampMillis in startOfDay..now }
        .sortedBy { it.timestampMillis }

    var health = INITIAL_BUDDY_HEALTH
    var lastDrinkAt = startOfDay
    var lastUpdatedAt = startOfDay

    todayEntries.forEach { entry ->
        health = applyDepletionBetween(
            health = health,
            fromMillis = lastUpdatedAt,
            toMillis = entry.timestampMillis,
            lastDrinkAt = lastDrinkAt,
            bodyMultiplier = bodyMultiplier
        )
        health = (health + entry.healthGain).coerceIn(0f, MAX_BUDDY_HEALTH)
        lastDrinkAt = entry.timestampMillis
        lastUpdatedAt = entry.timestampMillis
    }

    health = applyDepletionBetween(
        health = health,
        fromMillis = lastUpdatedAt,
        toMillis = now,
        lastDrinkAt = lastDrinkAt,
        bodyMultiplier = bodyMultiplier
    )

    return BuddyState(
        health = health,
        lastDrinkAt = lastDrinkAt,
        lastUpdatedAt = now
    )
}

fun todayDateKey(nowMillis: Long = System.currentTimeMillis()): String =
    Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
