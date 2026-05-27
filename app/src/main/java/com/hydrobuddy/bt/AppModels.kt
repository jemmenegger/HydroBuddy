package com.hydrobuddy.bt

internal enum class AppScreen { Onboarding, Home, Settings }

data class UserProfile(
    val gender: String,
    val heightCm: Int,
    val weightKg: Int,
    val hiddenDrinkBaselineMl: Int
)
