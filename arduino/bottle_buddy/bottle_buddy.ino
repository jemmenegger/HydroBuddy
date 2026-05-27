/*
 * HydroBuddy - Bottle Buddy Arduino sketch
 *
 * Board:     Dreamer Nano v4.1 / Arduino Leonardo compatible
 * OLED:      0.96" SSD1306 128x64 I2C @ 0x3C  (SDA=D2, SCL=D3)
 * Bluetooth: HC-06 Classic on Serial1 @ 9600  (HC-06 TXD->D0/RX, RXD->D1/TX)
 * Button:    tactile, D4 <-> GND, INPUT_PULLUP (pressed = LOW)
 *
 * Protocol (newline-terminated text over Bluetooth):
 *   Arduino -> App   SIP,1,8 | HEALTH,<n> | GET_ACK
 *   App     -> Arduino   GET_STATE | SET_HEALTH,<n> | LOG_GAIN,<n> | ACK
 *
 * Behaviour:
 *   - Button press logs one sip locally (+8 health), shows feedback,
 *     and notifies the phone if connected.
 *   - When the app sends SET_HEALTH, the local value is overwritten
 *     (app is source of truth when connected).
 *   - Works fully offline if the phone isn't connected.
 */

#include <Wire.h>
#include <EEPROM.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

// ---------- Board sanity check ----------
// This sketch requires a second hardware UART (Serial1), which exists on
// ATmega32U4 boards (Arduino Leonardo / Dreamer Nano v4.1 / Pro Micro / etc.).
// If you see this error, set: Tools > Board > Arduino AVR Boards > Arduino Leonardo
#if !defined(HAVE_HWSERIAL1) && !defined(SERIAL_PORT_HARDWARE1) && !defined(__AVR_ATmega32U4__)
  #error "Serial1 is not available on this board. Select 'Arduino Leonardo' (or another ATmega32U4 board) under Tools > Board."
#endif
#define BTSerial Serial1

// ---------- Hardware configuration ----------
constexpr uint8_t OLED_ADDR    = 0x3C;
constexpr int     OLED_WIDTH   = 128;
constexpr int     OLED_HEIGHT  = 64;
constexpr int     OLED_RESET   = -1;
constexpr uint8_t BUTTON_PIN   = 4;

// ---------- Buddy logic ----------
constexpr int     SIP_HEALTH_GAIN      = 8;
constexpr int     MAX_BUDDY_HEALTH     = 99;
constexpr int     LOW_HEALTH_THRESHOLD = 40;
constexpr unsigned long MAX_TIME_WITHOUT_DRINK_MS = 60UL * 60UL * 1000UL;

// ---------- EEPROM persistence ----------
// The Arduino is "unconfigured" on first boot - it must be paired with the
// phone app once before showing any buddy stats. The pairing state and the
// last known health are stored in EEPROM so they survive power cycles.
constexpr int  EEPROM_MAGIC_ADDR  = 0;
constexpr int  EEPROM_HEALTH_ADDR = 1;
constexpr int  EEPROM_PAIRED_ADDR = 2;
constexpr uint8_t EEPROM_MAGIC_VAL = 0xA7;

// ---------- Timing ----------
constexpr unsigned long DEBOUNCE_MS         = 250;
constexpr unsigned long SIP_POPUP_MS        = 5000;
constexpr unsigned long SIP_POPUP_IN_MS     = 180;
constexpr unsigned long SIP_POPUP_OUT_MS    = 220;
constexpr int           SIP_POPUP_SLIDE_PX  = 3;
constexpr unsigned long SPARKLE_BURST_MS    = 1200;
constexpr unsigned long REDRAW_INTERVAL_MS  = 120;
constexpr unsigned long BT_BUFFER_MAX       = 64;
constexpr unsigned long BT_SYNC_STALE_MS    = 10UL * 60UL * 1000UL;
constexpr unsigned long BT_SYNCING_MS       = 1500;

// ---------- Sprite sizes ----------
constexpr int SPRITE_X = 2;
constexpr int SPRITE_Y = 9;
constexpr int SPRITE_W = 46;
constexpr int SPRITE_H = 46;

// ---------- Droplet sprite (PROGMEM) ----------
// One clean 46x46 base sprite; mood differences are overlaid procedurally.
const uint8_t PROGMEM kSpriteHappy46[] = {
  0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,
  0x00, 0x00, 0x07, 0x80, 0x00, 0x00, 0x00, 0x00, 0x04, 0x80, 0x00, 0x00,
  0x00, 0x00, 0x0C, 0xC0, 0x00, 0x00, 0x00, 0x00, 0x18, 0x40, 0x00, 0x00,
  0x00, 0x00, 0x30, 0x60, 0x00, 0x00, 0x00, 0x00, 0x60, 0x30, 0x00, 0x00,
  0x00, 0x00, 0x40, 0x18, 0x00, 0x00, 0x00, 0x00, 0xC0, 0x0C, 0x00, 0x00,
  0x00, 0x01, 0x98, 0x04, 0x00, 0x00, 0x00, 0x03, 0x38, 0x06, 0x00, 0x00,
  0x00, 0x06, 0x30, 0x03, 0x00, 0x00, 0x00, 0x0C, 0x00, 0x01, 0x80, 0x00,
  0x00, 0x19, 0xC0, 0x00, 0xC0, 0x00, 0x00, 0x33, 0xC0, 0x00, 0x60, 0x00,
  0x00, 0x27, 0xC0, 0x00, 0x30, 0x00, 0x00, 0x67, 0x80, 0x00, 0x10, 0x00,
  0x00, 0x4F, 0x00, 0x00, 0x18, 0x00, 0x00, 0xCE, 0x00, 0x00, 0x0C, 0x00,
  0x00, 0x80, 0x00, 0x00, 0x04, 0x00, 0x01, 0x80, 0x00, 0x00, 0x06, 0x00,
  0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01, 0x03, 0x80, 0x07, 0x02, 0x00,
  0x03, 0x04, 0x40, 0x08, 0x82, 0x00, 0x02, 0x0B, 0x20, 0x16, 0x43, 0x00,
  0x02, 0x0B, 0x20, 0x16, 0x41, 0x00, 0x06, 0x0A, 0x20, 0x14, 0x41, 0x00,
  0x04, 0x08, 0xA0, 0x11, 0x41, 0x00, 0x04, 0x04, 0x40, 0x08, 0x81, 0x00,
  0x04, 0x03, 0x9F, 0xE7, 0x01, 0x00, 0x04, 0x00, 0x10, 0x20, 0x01, 0x00,
  0x04, 0x1E, 0x10, 0x21, 0xE3, 0x00, 0x06, 0x1C, 0x1B, 0x60, 0xE2, 0x00,
  0x03, 0x00, 0x0F, 0xC0, 0x02, 0x00, 0x01, 0x00, 0x07, 0x80, 0x06, 0x00,
  0x01, 0x80, 0x00, 0x00, 0x0C, 0x00, 0x00, 0xC0, 0x00, 0x00, 0x08, 0x00,
  0x00, 0x60, 0x00, 0x00, 0x18, 0x00, 0x00, 0x30, 0x00, 0x00, 0x60, 0x00,
  0x00, 0x18, 0x00, 0x00, 0xC0, 0x00, 0x00, 0x0E, 0x00, 0x03, 0x80, 0x00,
  0x00, 0x03, 0xC0, 0x0E, 0x40, 0x00, 0x00, 0x1C, 0x7F, 0xF9, 0xE0, 0x00,
  0x00, 0x1F, 0x80, 0x07, 0xE0, 0x00, 0x00, 0x0F, 0xC0, 0x0F, 0xC0, 0x00,
};

// Expression differences are drawn procedurally to save flash on Leonardo.

// ---------- Globals ----------
Adafruit_SSD1306 display(OLED_WIDTH, OLED_HEIGHT, &Wire, OLED_RESET);

int  buddyHealth      = 0;
bool pairedOnce       = false;
unsigned long lastSipMillis = 0;

int  lastButtonReading   = HIGH;
int  stableButtonState   = HIGH;
unsigned long lastButtonEdgeMs = 0;

uint8_t sipPopupCount     = 0;
uint8_t sipMessageIndex   = 0;
unsigned long sipPopupUntilMs = 0;
unsigned long sipPopupAnimStartMs = 0;
unsigned long sparkleUntilMs = 0;
uint8_t displayedBarHealth = 0;

unsigned long lastDrawMs  = 0;
unsigned long lastBluetoothRxMillis = 0;
bool syncSeenThisBoot     = false;
bool syncPending          = false;
unsigned long syncPendingStartedMs = 0;

String btBuffer;

// ---------- Setup ----------
void setup() {
  Serial.begin(9600);
  BTSerial.begin(9600);

  pinMode(BUTTON_PIN, INPUT_PULLUP);
  lastButtonReading = digitalRead(BUTTON_PIN);
  stableButtonState = lastButtonReading;

  Wire.begin();
  if (!display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR)) {
    Serial.println(F("SSD1306 init failed"));
    while (true) { /* halt */ }
  }
  display.clearDisplay();
  display.display();

  loadFromEEPROM();
  lastSipMillis = millis();
  displayedBarHealth = (uint8_t)buddyHealth;

  if (pairedOnce) {
    drawScreen();
  } else {
    drawPairingScreen();
  }
  lastDrawMs = millis();

  btBuffer.reserve(BT_BUFFER_MAX);
}

// ---------- EEPROM helpers ----------
void loadFromEEPROM() {
  uint8_t magic = EEPROM.read(EEPROM_MAGIC_ADDR);
  if (magic != EEPROM_MAGIC_VAL) {
    EEPROM.update(EEPROM_MAGIC_ADDR, EEPROM_MAGIC_VAL);
    EEPROM.update(EEPROM_HEALTH_ADDR, 0);
    EEPROM.update(EEPROM_PAIRED_ADDR, 0);
    pairedOnce  = false;
    buddyHealth = 0;
    return;
  }
  uint8_t h = EEPROM.read(EEPROM_HEALTH_ADDR);
  if (h > MAX_BUDDY_HEALTH) h = MAX_BUDDY_HEALTH;
  buddyHealth = h;
  pairedOnce  = EEPROM.read(EEPROM_PAIRED_ADDR) == 1;
}

void saveHealthToEEPROM() {
  uint8_t v = (uint8_t)constrain(buddyHealth, 0, MAX_BUDDY_HEALTH);
  EEPROM.update(EEPROM_HEALTH_ADDR, v);
}

void markPairedIfNeeded() {
  if (pairedOnce) return;
  pairedOnce = true;
  EEPROM.update(EEPROM_PAIRED_ADDR, 1);
}

// ---------- Main loop ----------
void loop() {
  handleButton();
  readBluetooth();
  updateUiEffects();
  maybeRedraw();
}

// ---------- Button (debounced) ----------
void handleButton() {
  int reading = digitalRead(BUTTON_PIN);
  unsigned long now = millis();

  if (reading != lastButtonReading) {
    lastButtonEdgeMs = now;
    lastButtonReading = reading;
  }

  if ((now - lastButtonEdgeMs) >= DEBOUNCE_MS && reading != stableButtonState) {
    stableButtonState = reading;
    if (stableButtonState == LOW) {
      onSipPressed();
    }
  }
}

void onSipPressed() {
  // Until the phone app has talked to us at least once, the Arduino has no
  // valid health to display - just keep nagging the user to pair.
  if (!pairedOnce) {
    drawPairingScreen();
    lastDrawMs = millis();
    return;
  }

  applyHealthGain(SIP_HEALTH_GAIN);
  saveHealthToEEPROM();
  lastSipMillis = millis();
  recordLocalSip();

  BTSerial.print(F("SIP,1,"));
  BTSerial.println(SIP_HEALTH_GAIN);
  sendHealth();

  Serial.print(F("SIP -> health=")); Serial.println(buddyHealth);
}

// ---------- Health math ----------
void applyHealthGain(int gain) {
  long newHealth = (long)buddyHealth + gain;
  if (newHealth > MAX_BUDDY_HEALTH) newHealth = MAX_BUDDY_HEALTH;
  if (newHealth < 0)   newHealth = 0;
  buddyHealth = (int)newHealth;
}

void sendHealth() {
  BTSerial.print(F("HEALTH,"));
  BTSerial.println(buddyHealth);
}

void sendAck() {
  BTSerial.println(F("GET_ACK"));
}

// ---------- UI effects (non-blocking) ----------
void recordLocalSip() {
  unsigned long now = millis();

  if (sipPopupCount > 0 && now < sipPopupUntilMs) {
    if (sipPopupCount < 99) sipPopupCount++;
  } else {
    sipPopupCount = 1;
  }
  sipMessageIndex = (uint8_t)((sipMessageIndex + 1) % 5);
  sipPopupUntilMs = now + SIP_POPUP_MS;
  sipPopupAnimStartMs = now;

  sparkleUntilMs = now + SPARKLE_BURST_MS;
  syncPending = true;
  syncPendingStartedMs = now;

  drawScreen();
  lastDrawMs = now;
}

void updateUiEffects() {
  unsigned long now = millis();
  bool changed = false;

  if (sipPopupCount > 0 && now >= sipPopupUntilMs) {
    sipPopupCount = 0;
    changed = true;
  }
  if (sparkleUntilMs != 0 && now >= sparkleUntilMs) {
    sparkleUntilMs = 0;
    changed = true;
  }
  if (syncPending && (now - syncPendingStartedMs) >= BT_SYNCING_MS) {
    syncPending = false;
    changed = true;
  }
  if (displayedBarHealth != (uint8_t)buddyHealth) {
    int diff = buddyHealth - displayedBarHealth;
    int step = 1;
    if (diff >= 12 || diff <= -12) {
      step = 4;
    } else if (diff >= 6 || diff <= -6) {
      step = 2;
    }
    if (diff > 0) {
      displayedBarHealth = (uint8_t)min((int)displayedBarHealth + step, buddyHealth);
    } else {
      displayedBarHealth = (uint8_t)max((int)displayedBarHealth - step, buddyHealth);
    }
    changed = true;
  }

  if (changed) {
    if (pairedOnce) {
      drawScreen();
    } else {
      drawPairingScreen();
    }
    lastDrawMs = now;
  }
}

void noteSyncContact() {
  syncSeenThisBoot = true;
  lastBluetoothRxMillis = millis();
  syncPending = false;
}

// ---------- Bluetooth I/O ----------
void readBluetooth() {
  while (BTSerial.available()) {
    char c = (char)BTSerial.read();
    if (c == '\n' || c == '\r') {
      if (btBuffer.length() > 0) {
        handleBluetoothLine(btBuffer);
        btBuffer = "";
      }
    } else {
      if (btBuffer.length() < BT_BUFFER_MAX) {
        btBuffer += c;
      } else {
        btBuffer = ""; // overflow guard
      }
    }
  }
}

void handleBluetoothLine(String line) {
  line.trim();
  if (line.length() == 0) return;

  Serial.print(F("BT< ")); Serial.println(line);

  // Any valid line from the app counts as "we are talking to the phone" -
  // remember that so future boots show the buddy screen, not the pairing one.
  bool wasUnpaired = !pairedOnce;
  markPairedIfNeeded();

  if (line.equalsIgnoreCase("GET_STATE")) {
    noteSyncContact();
    sendAck();
    sendHealth();
    return;
  }

  if (line.startsWith("SET_HEALTH,")) {
    noteSyncContact();
    int v = line.substring(11).toInt();
    if (v < 0) v = 0;
    if (v > MAX_BUDDY_HEALTH) v = MAX_BUDDY_HEALTH;
    buddyHealth = v;
    saveHealthToEEPROM();
    sendAck();
    drawScreen();
    lastDrawMs = millis();
    return;
  }

  if (line.startsWith("LOG_GAIN,")) {
    noteSyncContact();
    int gain = line.substring(9).toInt();
    applyHealthGain(gain);
    saveHealthToEEPROM();
    lastSipMillis = millis();
    sparkleUntilMs = millis() + SPARKLE_BURST_MS;
    drawScreen();
    lastDrawMs = millis();
    sendAck();
    sendHealth();
    return;
  }

  if (line.equalsIgnoreCase("ACK")) {
    noteSyncContact();
    // First-time pairing: even a bare ACK means the phone is talking to us.
    // Switch off the pairing screen on the next redraw.
    if (wasUnpaired) {
      drawScreen();
      lastDrawMs = millis();
    }
    return;
  }

  Serial.print(F("Unknown cmd: ")); Serial.println(line);
}

// ---------- Drawing ----------
void maybeRedraw() {
  if (millis() - lastDrawMs < REDRAW_INTERVAL_MS) return;
  if (pairedOnce) {
    drawScreen();
  } else {
    drawPairingScreen();
  }
  lastDrawMs = millis();
}

uint8_t currentFaceMood() {
  if (buddyHealth >= 75) return 0;
  if (buddyHealth >= 50) return 1;
  if (buddyHealth >= 25) return 2;
  return 3;
}

void clearFaceOverlayArea() {
  display.fillRect(SPRITE_X + 15, SPRITE_Y + 29, 16, 8, SSD1306_BLACK);
}

void drawDropletFaceOverlay(uint8_t mood) {
  if (mood == 0) return;

  clearFaceOverlayArea();

  switch (mood) {
    case 1: // okay
      // Slight smile: visibly different from neutral, but calmer than happy.
      display.drawLine(SPRITE_X + 18, SPRITE_Y + 33, SPRITE_X + 21, SPRITE_Y + 34, SSD1306_WHITE);
      display.drawFastHLine(SPRITE_X + 21, SPRITE_Y + 34, 5, SSD1306_WHITE);
      display.drawLine(SPRITE_X + 25, SPRITE_Y + 34, SPRITE_X + 28, SPRITE_Y + 33, SSD1306_WHITE);
      display.drawFastHLine(SPRITE_X + 22, SPRITE_Y + 35, 3, SSD1306_WHITE);
      break;
    case 2: // slightly sad
      display.drawLine(SPRITE_X + 18, SPRITE_Y + 34, SPRITE_X + 21, SPRITE_Y + 33, SSD1306_WHITE);
      display.drawFastHLine(SPRITE_X + 21, SPRITE_Y + 33, 5, SSD1306_WHITE);
      display.drawLine(SPRITE_X + 25, SPRITE_Y + 33, SPRITE_X + 28, SPRITE_Y + 34, SSD1306_WHITE);
      break;
    default: // sad
      display.drawLine(SPRITE_X + 18, SPRITE_Y + 34, SPRITE_X + 20, SPRITE_Y + 33, SSD1306_WHITE);
      display.drawLine(SPRITE_X + 20, SPRITE_Y + 33, SPRITE_X + 22, SPRITE_Y + 32, SSD1306_WHITE);
      display.drawFastHLine(SPRITE_X + 22, SPRITE_Y + 31, 3, SSD1306_WHITE);
      display.drawLine(SPRITE_X + 24, SPRITE_Y + 32, SPRITE_X + 26, SPRITE_Y + 33, SSD1306_WHITE);
      display.drawLine(SPRITE_X + 26, SPRITE_Y + 33, SPRITE_X + 28, SPRITE_Y + 34, SSD1306_WHITE);
      display.drawLine(SPRITE_X + 12, SPRITE_Y + 22, SPRITE_X + 16, SPRITE_Y + 21, SSD1306_WHITE);
      display.drawLine(SPRITE_X + 30, SPRITE_Y + 21, SPRITE_X + 34, SPRITE_Y + 22, SSD1306_WHITE);
      break;
  }
}

const __FlashStringHelper* statusMessage() {
  if (!pairedOnce)                      return F("Pair app");
  if (sipPopupCount > 0) {
    switch (sipMessageIndex) {
      case 0: return F("Nice!");
      case 1: return F("Glug!");
      case 2: return F("Fresh!");
      case 3: return F("Sip sip!");
      default: return F("Slurp!");
    }
  }
  if (buddyHealth >= 80)                return F("Great job!");
  unsigned long sinceSip = millis() - lastSipMillis;
  if (buddyHealth < LOW_HEALTH_THRESHOLD ||
      sinceSip >= MAX_TIME_WITHOUT_DRINK_MS) {
    return F("Take sip");
  }
  if (buddyHealth >= 55)                return F("Buddy ok");
  return F("Need sip");
}

void drawSparklePlus(int x, int y) {
  display.drawFastHLine(x - 1, y, 3, SSD1306_WHITE);
  display.drawFastVLine(x, y - 1, 3, SSD1306_WHITE);
}

void drawSparkleDiamond(int x, int y) {
  display.drawPixel(x, y - 1, SSD1306_WHITE);
  display.drawPixel(x - 1, y, SSD1306_WHITE);
  display.drawPixel(x, y, SSD1306_WHITE);
  display.drawPixel(x + 1, y, SSD1306_WHITE);
  display.drawPixel(x, y + 1, SSD1306_WHITE);
}

void drawSparkleBig(int x, int y) {
  display.drawFastHLine(x - 2, y, 5, SSD1306_WHITE);
  display.drawFastVLine(x, y - 2, 5, SSD1306_WHITE);
  display.drawPixel(x - 1, y - 1, SSD1306_WHITE);
  display.drawPixel(x + 1, y - 1, SSD1306_WHITE);
  display.drawPixel(x - 1, y + 1, SSD1306_WHITE);
  display.drawPixel(x + 1, y + 1, SSD1306_WHITE);
}

void drawSparkles() {
  if (sparkleUntilMs == 0) return;
  unsigned long now = millis();
  bool s0 = (((now + 0UL) / 90UL) & 1U) == 0U;
  bool s1 = (((now + 35UL) / 120UL) & 1U) == 0U;
  bool s2 = (((now + 70UL) / 150UL) & 1U) == 0U;
  bool s3 = (((now + 20UL) / 110UL) & 1U) == 0U;
  bool s4 = (((now + 95UL) / 170UL) & 1U) == 0U;
  bool s5 = (((now + 50UL) / 130UL) & 1U) == 0U;

  if (s0) drawSparkleBig(10, 13);
  if (s1) drawSparkleDiamond(16, 8);
  if (s2) drawSparklePlus(24, 6);
  if (s3) drawSparkleBig(35, 7);
  if (s4) drawSparkleDiamond(42, 11);
  if (s5) drawSparklePlus(45, 17);
}

void drawSyncCheckIcon(int x, int y) {
  display.drawLine(x + 1, y + 4, x + 3, y + 6, SSD1306_WHITE);
  display.drawLine(x + 3, y + 6, x + 8, y + 1, SSD1306_WHITE);
}

void drawSyncingIcon(int x, int y) {
  display.drawCircle(x + 4, y + 4, 3, SSD1306_WHITE);
  display.drawPixel(x + 8, y + 4, SSD1306_BLACK);
  display.drawLine(x + 6, y + 1, x + 8, y + 2, SSD1306_WHITE);
  display.drawLine(x + 8, y + 2, x + 6, y + 3, SSD1306_WHITE);
  if (((millis() / 250UL) & 1U) == 0U) {
    display.drawPixel(x + 4, y + 4, SSD1306_WHITE);
  }
}

void drawUnsyncedIcon(int x, int y) {
  display.drawFastVLine(x + 4, y + 1, 4, SSD1306_WHITE);
  display.drawPixel(x + 4, y + 7, SSD1306_WHITE);
}

void drawSyncIcon() {
  const int iconX = 116;
  const int iconY = 2;

  if (syncPending) {
    drawSyncingIcon(iconX, iconY);
    return;
  }
  if (syncSeenThisBoot && (millis() - lastBluetoothRxMillis) <= BT_SYNC_STALE_MS) {
    drawSyncCheckIcon(iconX, iconY);
    return;
  }
  drawUnsyncedIcon(iconX, iconY);
}

void drawHealthValue(int health) {
  char healthBuf[4];
  snprintf(healthBuf, sizeof(healthBuf), "%d", health);
  uint8_t valueSize = 3;
  int valueY = 10;
  const int valueAreaX = 54;
  const int valueAreaW = 38;

  display.setTextSize(valueSize);
  int16_t x1, y1;
  uint16_t w, h;
  display.getTextBounds(healthBuf, 0, 0, &x1, &y1, &w, &h);
  int valueX = valueAreaX + ((valueAreaW - (int)w) / 2);
  if (valueX < valueAreaX) valueX = valueAreaX;
  display.setCursor(valueX, valueY);
  display.print(healthBuf);

  display.setTextSize(1);
  display.setCursor(95, 24);
  display.print(F("/99"));
}

void drawHealthBar(int x, int y, int w, int h, int health) {
  display.drawRoundRect(x, y, w, h, 2, SSD1306_WHITE);

  const int innerX = x + 2;
  const int innerY = y + 2;
  const int innerW = w - 4;
  const int innerH = h - 4;
  int fillW = (innerW * constrain(health, 0, MAX_BUDDY_HEALTH)) / MAX_BUDDY_HEALTH;
  if (fillW > 0) {
    display.fillRect(innerX, innerY, fillW, innerH, SSD1306_WHITE);
  }

  const int segments = 10;
  for (int i = 1; i < segments; ++i) {
    int sx = innerX + (i * innerW) / segments;
    display.drawFastVLine(sx, y + 1, h - 2, SSD1306_BLACK);
  }
}

void drawStatusMessage() {
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(54, 54);
  display.print(statusMessage());
}

void drawSipPopup() {
  if (sipPopupCount == 0) return;

  const int h = 16;
  const int popupRightEdge = 54 + 70;
  const int baseY = 47;
  unsigned long now = millis();
  unsigned long age = now - sipPopupAnimStartMs;
  unsigned long remaining = (sipPopupUntilMs > now) ? (sipPopupUntilMs - now) : 0;

  display.setTextColor(SSD1306_BLACK);
  display.setTextSize(1);

  char popupBuf[6];
  snprintf(popupBuf, sizeof(popupBuf), "+%u", sipPopupCount);
  int16_t x1, y1;
  uint16_t tw, th;
  display.getTextBounds(popupBuf, 0, 0, &x1, &y1, &tw, &th);

  const int w = max(16, (int)tw + 6);
  int yOffset = 0;
  if (age < SIP_POPUP_IN_MS) {
    yOffset = (int)((long)SIP_POPUP_SLIDE_PX * (long)(SIP_POPUP_IN_MS - age) / (long)SIP_POPUP_IN_MS);
  } else if (remaining < SIP_POPUP_OUT_MS) {
    yOffset = (int)((long)SIP_POPUP_SLIDE_PX * (long)(SIP_POPUP_OUT_MS - remaining) / (long)SIP_POPUP_OUT_MS);
  }

  const int x = popupRightEdge - w;
  const int y = baseY + yOffset;

  display.fillRoundRect(x, y, w, h, 2, SSD1306_WHITE);
  display.setCursor(x + ((w - (int)tw) / 2), y + 4);
  display.print(popupBuf);

  display.setTextColor(SSD1306_WHITE);
}

void drawScreen() {
  display.clearDisplay();

  drawSparkles();
  display.drawBitmap(SPRITE_X, SPRITE_Y, kSpriteHappy46, SPRITE_W, SPRITE_H, SSD1306_WHITE);
  drawDropletFaceOverlay(currentFaceMood());
  drawSyncIcon();

  drawHealthValue(buddyHealth);
  drawHealthBar(54, 35, 70, 10, displayedBarHealth);
  drawStatusMessage();
  drawSipPopup();

  display.display();
}

void drawPairingScreen() {
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  drawSparkles();
  display.drawBitmap(SPRITE_X, SPRITE_Y, kSpriteHappy46, SPRITE_W, SPRITE_H, SSD1306_WHITE);
  drawDropletFaceOverlay(3);
  drawSyncIcon();

  display.setTextSize(3);
  display.setCursor(60, 10);
  display.print(F("--"));

  display.setTextSize(1);
  display.setCursor(95, 24);
  display.print(F("/99"));

  drawHealthBar(54, 35, 70, 10, 0);
  drawStatusMessage();

  display.display();
}
