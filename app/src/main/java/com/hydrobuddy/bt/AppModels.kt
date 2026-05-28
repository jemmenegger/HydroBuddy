// Shared navigation + user profile types used by MainActivity.

package com.hydrobuddy.bt

/** Which full-screen the app is showing. */
internal enum class AppScreen { Onboarding, Home, Settings }

/** Body stats from onboarding; hiddenDrinkBaselineMl drives how fast health drains. */
data class UserProfile(
    val gender: String,
    val heightCm: Int,
    val weightKg: Int,
    val hiddenDrinkBaselineMl: Int
)
