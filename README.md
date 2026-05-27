# HydroBuddy

Simple Bottle Buddy — Android app + Arduino smart bottle companion. Track buddy health (0–99), log sips, and sync over Bluetooth Classic (HC-06).

## Project layout

```
HydroBuddy/
├── app/                          # Android app (Kotlin, Jetpack Compose)
│   └── src/main/java/com/hydrobuddy/bt/
│       ├── MainActivity.kt       # Navigation, BT, persistence
│       ├── WaterTrackerLogic.kt  # Pure buddy math (no Android)
│       ├── WaterTrackerController.kt
│       ├── HomeScreen.kt         # Buddy + History UI
│       ├── SettingsScreen.kt
│       ├── OnboardingScreen.kt
│       ├── BluetoothClassicClient.kt
│       ├── Theme.kt
│       └── AppModels.kt
├── arduino/bottle_buddy/         # Leonardo / Dreamer Nano sketch
│   └── bottle_buddy.ino
├── figma/                        # Design reference PNGs (not used at build time)
├── gradlew                       # Build wrapper
└── settings.gradle.kts
```

## Android

- **Min SDK** 24, **target** 34
- **Stack:** Compose Material 3, SharedPreferences, Bluetooth SPP
- **Build:** `./gradlew :app:assembleDebug`

Pair the HC-06 in system Bluetooth settings first, then connect in app Settings. On launch, the app auto-reconnects to the last device if not already connected.

## Arduino

- Board: **Arduino Leonardo** / ATmega32U4 (needs `Serial1` for HC-06)
- OLED: SSD1306 128×64 @ `0x3C`
- Upload `arduino/bottle_buddy/bottle_buddy.ino` with Leonardo selected in Arduino IDE

Protocol (newline-terminated): `SIP`, `SET_HEALTH,<n>`, `GET_STATE`, `LOG_GAIN,<n>`, `ACK`.

## Buddy logic (app)

- Health drains after a **20 min** grace period following a drink (~0.8/min × body multiplier).
- One sip: **+8** health (max **99**).
- App is source of truth when connected; Arduino can log sips offline and sync when paired.
