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
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

// ---------- Hardware configuration ----------
constexpr uint8_t OLED_ADDR    = 0x3C;
constexpr int     OLED_WIDTH   = 128;
constexpr int     OLED_HEIGHT  = 64;
constexpr int     OLED_RESET   = -1;
constexpr uint8_t BUTTON_PIN   = 4;

// ---------- Buddy logic ----------
constexpr int     INITIAL_BUDDY_HEALTH = 75;
constexpr int     SIP_HEALTH_GAIN      = 8;
constexpr int     LOW_HEALTH_THRESHOLD = 40;
constexpr unsigned long MAX_TIME_WITHOUT_DRINK_MS = 60UL * 60UL * 1000UL;

// ---------- Timing ----------
constexpr unsigned long DEBOUNCE_MS         = 250;
constexpr unsigned long FEEDBACK_DURATION   = 900;
constexpr unsigned long FEEDBACK_FRAME_MS   = 60;
constexpr unsigned long REDRAW_INTERVAL_MS  = 1000;
constexpr unsigned long BT_BUFFER_MAX       = 64;

// ---------- Globals ----------
Adafruit_SSD1306 display(OLED_WIDTH, OLED_HEIGHT, &Wire, OLED_RESET);

int  buddyHealth      = INITIAL_BUDDY_HEALTH;
unsigned long lastSipMillis = 0;

int  lastButtonReading   = HIGH;
int  stableButtonState   = HIGH;
unsigned long lastButtonEdgeMs = 0;

bool feedbackActive       = false;
unsigned long feedbackStartMs = 0;
unsigned long lastFeedbackFrameMs = 0;
int  feedbackGain         = 0;

unsigned long lastDrawMs  = 0;

String btBuffer;

// ---------- Setup ----------
void setup() {
  Serial.begin(9600);
  Serial1.begin(9600);

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

  lastSipMillis = millis();
  drawScreen(false);
  lastDrawMs = millis();

  btBuffer.reserve(BT_BUFFER_MAX);
}

// ---------- Main loop ----------
void loop() {
  handleButton();
  readBluetooth();
  updateFeedback();
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
  applyHealthGain(SIP_HEALTH_GAIN);
  lastSipMillis = millis();
  triggerFeedback(SIP_HEALTH_GAIN);

  Serial1.print(F("SIP,1,"));
  Serial1.println(SIP_HEALTH_GAIN);
  sendHealth();

  Serial.print(F("SIP -> health=")); Serial.println(buddyHealth);
}

// ---------- Health math ----------
void applyHealthGain(int gain) {
  long newHealth = (long)buddyHealth + gain;
  if (newHealth > 100) newHealth = 100;
  if (newHealth < 0)   newHealth = 0;
  buddyHealth = (int)newHealth;
}

void sendHealth() {
  Serial1.print(F("HEALTH,"));
  Serial1.println(buddyHealth);
}

void sendAck() {
  Serial1.println(F("GET_ACK"));
}

// ---------- Feedback animation (non-blocking) ----------
void triggerFeedback(int gain) {
  feedbackActive   = true;
  feedbackStartMs  = millis();
  lastFeedbackFrameMs = 0;
  feedbackGain     = gain;
}

void updateFeedback() {
  if (!feedbackActive) return;

  unsigned long now = millis();
  if (now - feedbackStartMs >= FEEDBACK_DURATION) {
    feedbackActive = false;
    drawScreen(false);
    lastDrawMs = now;
    return;
  }

  if (now - lastFeedbackFrameMs >= FEEDBACK_FRAME_MS) {
    drawScreen(true);
    lastFeedbackFrameMs = now;
  }
}

// ---------- Bluetooth I/O ----------
void readBluetooth() {
  while (Serial1.available()) {
    char c = (char)Serial1.read();
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

  if (line.equalsIgnoreCase("GET_STATE")) {
    sendAck();
    sendHealth();
    return;
  }

  if (line.startsWith("SET_HEALTH,")) {
    int v = line.substring(11).toInt();
    if (v < 0) v = 0;
    if (v > 100) v = 100;
    buddyHealth = v;
    sendAck();
    drawScreen(false);
    lastDrawMs = millis();
    return;
  }

  if (line.startsWith("LOG_GAIN,")) {
    int gain = line.substring(9).toInt();
    applyHealthGain(gain);
    lastSipMillis = millis();
    triggerFeedback(gain);
    sendAck();
    sendHealth();
    return;
  }

  if (line.equalsIgnoreCase("ACK")) {
    // app acknowledged - nothing to do
    return;
  }

  Serial.print(F("Unknown cmd: ")); Serial.println(line);
}

// ---------- Drawing ----------
void maybeRedraw() {
  if (feedbackActive) return;
  if (millis() - lastDrawMs >= REDRAW_INTERVAL_MS) {
    drawScreen(false);
    lastDrawMs = millis();
  }
}

int healthState() {
  if (buddyHealth >= 80) return 4;
  if (buddyHealth >= 55) return 3;
  if (buddyHealth >= 30) return 2;
  return 1;
}

const __FlashStringHelper* topMessage(bool isFeedback) {
  if (isFeedback)                       return F("Good job!");
  if (buddyHealth >= 80)                return F("Buddy happy");
  unsigned long sinceSip = millis() - lastSipMillis;
  if (buddyHealth < LOW_HEALTH_THRESHOLD ||
      sinceSip >= MAX_TIME_WITHOUT_DRINK_MS) {
    return F("Take a sip?");
  }
  return F("Buddy ok");
}

void drawScreen(bool isFeedback) {
  display.clearDisplay();

  // Top: message
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(2, 2);
  display.print(topMessage(isFeedback));

  // Top right: +gain during feedback
  if (isFeedback && feedbackGain != 0) {
    char gainBuf[8];
    snprintf(gainBuf, sizeof(gainBuf), "%+d", feedbackGain);
    int16_t gx1, gy1; uint16_t gw, gh;
    display.getTextBounds(gainBuf, 0, 0, &gx1, &gy1, &gw, &gh);
    display.setCursor(OLED_WIDTH - (int)gw - 2, 2);
    display.print(gainBuf);
  }

  // Middle: STATE n  (inverted during feedback)
  int state = healthState();
  char stateBuf[10];
  snprintf(stateBuf, sizeof(stateBuf), "STATE %d", state);

  display.setTextSize(2);
  int16_t sx1, sy1; uint16_t sw, sh;
  display.getTextBounds(stateBuf, 0, 0, &sx1, &sy1, &sw, &sh);
  int stateX = (OLED_WIDTH - (int)sw) / 2;
  int stateY = 18;

  if (isFeedback) {
    display.fillRect(stateX - 2, stateY - 2, sw + 4, sh + 4, SSD1306_WHITE);
    display.setTextColor(SSD1306_BLACK);
  } else {
    display.setTextColor(SSD1306_WHITE);
  }
  display.setCursor(stateX, stateY);
  display.print(stateBuf);
  display.setTextColor(SSD1306_WHITE);

  if (isFeedback) {
    drawFeedbackDots(stateX + (int)sw / 2, stateY + (int)sh / 2);
  }

  // Bottom: big health value
  char healthBuf[6];
  snprintf(healthBuf, sizeof(healthBuf), "%d", buddyHealth);
  display.setTextSize(3);
  int16_t hx1, hy1; uint16_t hw, hh;
  display.getTextBounds(healthBuf, 0, 0, &hx1, &hy1, &hw, &hh);
  int hx = (OLED_WIDTH - (int)hw) / 2;
  int hy = OLED_HEIGHT - (int)hh - 2;
  display.setCursor(hx, hy);
  display.print(healthBuf);

  display.display();
}

void drawFeedbackDots(int cx, int cy) {
  unsigned long elapsed = millis() - feedbackStartMs;
  float progress = (float)elapsed / (float)FEEDBACK_DURATION;
  if (progress > 1.0f) progress = 1.0f;

  const int dotCount = 8;
  float radius = 22.0f + progress * 10.0f;
  uint16_t color = SSD1306_WHITE;

  for (int i = 0; i < dotCount; i++) {
    float angle = (2.0f * PI * i) / dotCount + progress * 0.6f;
    int dx = (int)(cos(angle) * radius);
    int dy = (int)(sin(angle) * radius);
    display.fillCircle(cx + dx, cy + dy, 1, color);
  }
}
