// HydroBuddy bottle — Leonardo + OLED + HC-06. Phone owns health/history.
// Two screens: offline (no app link) and connected (buddy UI + SIP button).

#include <Wire.h>
#include <EEPROM.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

// Leonardo / 32U4 boards have Serial1 for HC-06; other AVR boards will fail here:
#if !defined(HAVE_HWSERIAL1) && !defined(SERIAL_PORT_HARDWARE1) && !defined(__AVR_ATmega32U4__)
  #error "Serial1 is not available on this board. Select 'Arduino Leonardo' (or another ATmega32U4 board) under Tools > Board."
#endif
#define BTSerial Serial1

// --- Pins & display ---
constexpr uint8_t OLED_ADDR    = 0x3C;
constexpr int     OLED_WIDTH   = 128;
constexpr int     OLED_HEIGHT  = 64;
constexpr int     OLED_RESET   = -1;
constexpr uint8_t BUTTON_PIN   = 4;

constexpr int     MAX_BUDDY_HEALTH     = 99;
constexpr int     LOW_HEALTH_THRESHOLD = 40;
constexpr unsigned long GRACE_PERIOD_MS = 1UL * 60UL * 1000UL; // usually 20 min; 1 min for testing
constexpr unsigned long MAX_TIME_WITHOUT_DRINK_MS = 60UL * 60UL * 1000UL;

// --- EEPROM: magic byte, health 0–99, paired-once flag ---
constexpr int  EEPROM_MAGIC_ADDR  = 0;
constexpr int  EEPROM_HEALTH_ADDR = 1;
constexpr int  EEPROM_PAIRED_ADDR = 2;
constexpr uint8_t EEPROM_MAGIC_VAL = 0xA7;

constexpr unsigned long DEBOUNCE_MS         = 250;
constexpr unsigned long SIP_POPUP_MS        = 5000;
constexpr unsigned long SIP_POPUP_IN_MS     = 180;
constexpr unsigned long SIP_POPUP_OUT_MS    = 220;
constexpr int           SIP_POPUP_SLIDE_PX  = 3;
constexpr unsigned long SPARKLE_BURST_MS    = 1200;
constexpr unsigned long REDRAW_INTERVAL_MS  = 120;
constexpr unsigned long BT_BUFFER_MAX       = 64;

constexpr int SPRITE_X = 2;
constexpr int SPRITE_Y = 9;
constexpr int SPRITE_W = 46;
constexpr int SPRITE_H = 46;

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

Adafruit_SSD1306 display(OLED_WIDTH, OLED_HEIGHT, &Wire, OLED_RESET);

int  buddyHealth      = 0;
bool pairedOnce       = false; // set when app first sends SET_HEALTH (stored in EEPROM)
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
bool btSessionOpen         = false; // app sent LINK,1 (HydroBuddy connected in Settings)
bool hasReceivedHealthSync = false;
unsigned long justDrankUntilMs = 0;

String btBuffer;

void drawScreen();
void drawOfflineScreen();

bool isAppConnected() {
  return btSessionOpen && hasReceivedHealthSync;
}

void clearDisplayState() {
  hasReceivedHealthSync = false;
  sipPopupCount = 0;
  sparkleUntilMs = 0;
  buddyHealth = 0;
  displayedBarHealth = 0;
  justDrankUntilMs = 0;
}

void clearAppSession() {
  btSessionOpen = false;
  clearDisplayState();
}

void redrawCurrentScreen() {
  if (isAppConnected()) {
    drawScreen();
  } else {
    drawOfflineScreen();
  }
}

void setup() { // OLED, EEPROM, first frame (offline or connected)
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

  redrawCurrentScreen();
  lastDrawMs = millis();

  btBuffer.reserve(BT_BUFFER_MAX);
}

void loadFromEEPROM() { // read health + paired flag; init EEPROM on first boot
  uint8_t magic = EEPROM.read(EEPROM_MAGIC_ADDR);
  if (magic != EEPROM_MAGIC_VAL) {
    EEPROM.update(EEPROM_MAGIC_ADDR, EEPROM_MAGIC_VAL);
    EEPROM.update(EEPROM_HEALTH_ADDR, 0);
    EEPROM.update(EEPROM_PAIRED_ADDR, 0);
    pairedOnce  = false;
    buddyHealth = 0;
    return;
  }
  buddyHealth = 0; // phone sets health via SET_HEALTH when connected
  pairedOnce  = EEPROM.read(EEPROM_PAIRED_ADDR) == 1;
}

void saveHealthToEEPROM() {
  uint8_t v = (uint8_t)constrain(buddyHealth, 0, MAX_BUDDY_HEALTH);
  EEPROM.update(EEPROM_HEALTH_ADDR, v);
}

void markPairedIfNeeded() { // first app contact flips pairedOnce and saves to EEPROM
  if (pairedOnce) return;
  pairedOnce = true;
  EEPROM.update(EEPROM_PAIRED_ADDR, 1);
}

void loop() { // button, BT lines, timed UI updates, periodic redraw
  handleButton();
  readBluetooth();
  updateUiEffects();
  maybeRedraw();
}

void handleButton() { // debounce D4; LOW = pressed
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

void onSipPressed() { // connected only: notify phone; app replies with SET_HEALTH
  if (!isAppConnected()) {
    redrawCurrentScreen();
    lastDrawMs = millis();
    return;
  }

  BTSerial.println(F("SIP"));
  // Sparkles / +N / status come from SET_HEALTH reply (same path as phone-logged drinks).
}

void showSipFeedback() {
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

  redrawCurrentScreen();
  lastDrawMs = now;
}

void updateUiEffects() { // expire popups/sparkles; animate health bar toward buddyHealth
  unsigned long now = millis();
  bool changed = false;

  static bool lastAppLinkUp = false;
  bool appLinkUp = isAppConnected();
  if (appLinkUp != lastAppLinkUp) {
    lastAppLinkUp = appLinkUp;
    changed = true;
    if (!appLinkUp) {
      clearDisplayState();
    }
  }

  if (!appLinkUp) {
    if (changed) {
      redrawCurrentScreen();
      lastDrawMs = now;
    }
    return;
  }

  if (sipPopupCount > 0 && now >= sipPopupUntilMs) {
    sipPopupCount = 0;
    changed = true;
  }
  if (sparkleUntilMs != 0 && now >= sparkleUntilMs) {
    sparkleUntilMs = 0;
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
    redrawCurrentScreen();
    lastDrawMs = now;
  }
}

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
        btBuffer = "";
      }
    }
  }
}

void handleBluetoothLine(String line) { // app → LINK,0|1 or SET_HEALTH,<n>
  line.trim();
  if (line.length() == 0) return;

  if (line.startsWith("LINK,")) {
    btSessionOpen = line.substring(5).toInt() != 0;
    if (!btSessionOpen) {
      clearAppSession();
    } else {
      markPairedIfNeeded();
    }
    redrawCurrentScreen();
    lastDrawMs = millis();
    return;
  }

  if (!line.startsWith("SET_HEALTH,")) return;

  markPairedIfNeeded();
  int v = line.substring(11).toInt();
  if (v < 0) v = 0;
  if (v > MAX_BUDDY_HEALTH) v = MAX_BUDDY_HEALTH;
  unsigned long now = millis();

  if (!hasReceivedHealthSync) {
    hasReceivedHealthSync = true;
    lastSipMillis = now - GRACE_PERIOD_MS;
    justDrankUntilMs = 0;
  } else if (v > buddyHealth) {
    lastSipMillis = now;
    justDrankUntilMs = now + GRACE_PERIOD_MS;
    showSipFeedback(); // +1 popup, sparkles, fun status (sip, preset, or bottle button)
  } else if (v < buddyHealth) {
    justDrankUntilMs = 0;
  }

  buddyHealth = v;
  saveHealthToEEPROM();
  redrawCurrentScreen();
  lastDrawMs = millis();
}

void maybeRedraw() {
  if (millis() - lastDrawMs < REDRAW_INTERVAL_MS) return;
  redrawCurrentScreen();
  lastDrawMs = millis();
}

// Face overlay on droplet: 0=happy (75+), 1=smile (50–74), 2=frown (25–49), 3=sad
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
    case 1:
      display.drawLine(SPRITE_X + 18, SPRITE_Y + 33, SPRITE_X + 21, SPRITE_Y + 34, SSD1306_WHITE);
      display.drawFastHLine(SPRITE_X + 21, SPRITE_Y + 34, 5, SSD1306_WHITE);
      display.drawLine(SPRITE_X + 25, SPRITE_Y + 34, SPRITE_X + 28, SPRITE_Y + 33, SSD1306_WHITE);
      display.drawFastHLine(SPRITE_X + 22, SPRITE_Y + 35, 3, SSD1306_WHITE);
      break;
    case 2:
      display.drawLine(SPRITE_X + 18, SPRITE_Y + 34, SPRITE_X + 21, SPRITE_Y + 33, SSD1306_WHITE);
      display.drawFastHLine(SPRITE_X + 21, SPRITE_Y + 33, 5, SSD1306_WHITE);
      display.drawLine(SPRITE_X + 25, SPRITE_Y + 33, SPRITE_X + 28, SPRITE_Y + 34, SSD1306_WHITE);
      break;
    default:
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
  // During +N sip popup: random fun lines (not "Just drank" yet).
  if (sipPopupCount > 0) {
    switch (sipMessageIndex) {
      case 0: return F("Nice!");
      case 1: return F("Glug!");
      case 2: return F("Fresh!");
      case 3: return F("Sip sip!");
      default: return F("Slurp!");
    }
  }
  unsigned long sinceSip = millis() - lastSipMillis;
  // After popup ends, still in grace: show "Just drank".
  if (justDrankUntilMs > millis()) {
    return F("Just drank");
  }
  if (buddyHealth < LOW_HEALTH_THRESHOLD ||
      sinceSip >= MAX_TIME_WITHOUT_DRINK_MS) {
    return F("Time to drink");
  }
  if (buddyHealth >= 80)                return F("Hydrated");
  if (buddyHealth >= 55)                return F("Drink now");
  return F("Time to drink");
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

void drawScreen() { // full buddy UI: sprite, health, bar, status, sip popup
  display.clearDisplay();

  drawSparkles();
  display.drawBitmap(SPRITE_X, SPRITE_Y, kSpriteHappy46, SPRITE_W, SPRITE_H, SSD1306_WHITE);
  drawDropletFaceOverlay(currentFaceMood());

  drawHealthValue(buddyHealth);
  drawHealthBar(54, 35, 70, 10, displayedBarHealth);
  drawStatusMessage();
  drawSipPopup();

  display.display();
}

void drawCableIcon() {
  const int plugX = 6;
  const int plugY = 30;
  display.fillRect(plugX, plugY, 16, 10, SSD1306_WHITE);
  display.fillRect(plugX + 3, plugY + 2, 10, 6, SSD1306_BLACK);
  display.fillRect(plugX - 5, plugY + 2, 4, 2, SSD1306_WHITE);
  display.fillRect(plugX - 5, plugY + 6, 4, 2, SSD1306_WHITE);
  display.drawFastVLine(plugX + 8, plugY + 10, 14, SSD1306_WHITE);
  display.drawLine(plugX + 8, plugY + 24, plugX + 20, plugY + 30, SSD1306_WHITE);
  display.drawLine(plugX + 20, plugY + 30, plugX + 28, plugY + 30, SSD1306_WHITE);
}

void drawOfflineScreen() {
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1);

  int16_t x1, y1;
  uint16_t tw, th;
  display.getTextBounds(F("Not connected"), 0, 0, &x1, &y1, &tw, &th);
  display.setCursor((OLED_WIDTH - (int)tw) / 2, 2);
  display.print(F("Not connected"));

  drawCableIcon();

  display.setCursor(58, 28);
  display.print(F("Connect in"));
  display.setCursor(58, 40);
  display.print(F("app Settings"));

  display.display();
}
