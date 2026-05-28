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

Bluetooth protocol (newline-terminated): bottle → app `SIP` or `SIP,<count>,<gain>`; app → bottle `SET_HEALTH,<n>`.

## Buddy logic (app)

- Health drains after a **20 min** grace period following a drink (~0.8/min × body multiplier).
- One sip: **+8** health (max **99**).
- App is source of truth when connected; Arduino can log sips offline and sync when paired.

## Reading the code

Open files in this order; each file has **inline comments** on functions and important logic:

1. `WaterTrackerLogic.kt` — health math (no Android)
2. `WaterTrackerController.kt` — prefs + sip/history
3. `MainActivity.kt` — navigation, storage, Bluetooth
4. `HomeScreen.kt` / `SettingsScreen.kt` / `OnboardingScreen.kt` — UI
5. `BluetoothClassicClient.kt` — HC-06 serial protocol
6. `arduino/bottle_buddy/bottle_buddy.ino` — OLED + button + bottle BT

### Build commands

```bash
./gradlew :app:assembleDebug    # build debug APK (needs JDK 17)
```

- `gradlew` — Gradle wrapper script (Mac/Linux).
- `gradlew.bat` — same for Windows.
