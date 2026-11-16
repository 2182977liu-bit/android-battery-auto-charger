#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <ArduinoJson.h>

#include "config.h"

WebServer server(HTTP_PORT);

// ---------------- 运行状态 ----------------
static bool relayOn = false;
static bool everSeen = false;
static unsigned long lastSeenMs = 0;
static unsigned long lastWifiRetryMs = 0;

static int lastLevel = -1;
static bool lastCharging = false;
static String lastDevice = "-";
static String lastModel = "-";
static String lastEvent = "-";
static long lastPhoneTs = 0;

// ---------------- 继电器 ----------------
static void setRelay(bool on, const char *reason) {
  int level = RELAY_ACTIVE_HIGH ? (on ? HIGH : LOW) : (on ? LOW : HIGH);
  digitalWrite(RELAY_PIN, level);
  if (on != relayOn) {
    relayOn = on;
    Serial.printf("[RELAY] %s  (%s)\n", on ? "ON  充电接通" : "OFF 充电断开", reason);
  }
}

// ---------------- 业务：收到手机报文 ----------------
static void handleBattery() {
  String token = server.header("X-Auth-Token");
  if (token != AUTH_TOKEN) {
    Serial.printf("[HTTP] 401 bad token from %s\n",
                  server.client().remoteIP().toString().c_str());
    server.send(401, "application/json", "{\"ok\":false,\"err\":\"bad_token\"}");
    return;
  }

  if (!server.hasArg("plain")) {
    server.send(400, "application/json", "{\"ok\":false,\"err\":\"empty_body\"}");
    return;
  }

  JsonDocument doc;
  DeserializationError err = deserializeJson(doc, server.arg("plain"));
  if (err) {
    Serial.printf("[HTTP] 400 bad json: %s\n", err.c_str());
    server.send(400, "application/json", "{\"ok\":false,\"err\":\"bad_json\"}");
    return;
  }

  String event = doc["event"] | "UNKNOWN";
  String device = doc["device_id"] | "unknown";
  String manufacturer = doc["manufacturer"] | "";
  String model = doc["model"] | "";
  int level = doc["level"] | -1;
  bool charging = doc["charging"] | false;
  String plugged = doc["plugged"] | "UNKNOWN";
  long ts = doc["ts"] | 0;
  float tempC = doc["temp_c"] | 0.0f;
  int voltageMv = doc["voltage_mv"] | 0;

  // 刷新在线状态
  lastSeenMs = millis();
  everSeen = true;
  lastEvent = event;
  lastDevice = device;
  lastModel = manufacturer + " " + model;
  lastLevel = level;
  lastCharging = charging;
  lastPhoneTs = ts;

  Serial.printf("[EVT] %-12s dev=%-8s model=%s level=%d%% chg=%d plug=%-9s "
                "temp=%.1fC v=%dmV relay=%s\n",
                event.c_str(), device.c_str(), lastModel.c_str(),
                level, charging ? 1 : 0, plugged.c_str(),
                tempC, voltageMv, relayOn ? "ON" : "OFF");

  // ---- 核心控制：只看电量，不依赖事件类型，天然带回滞 ----
  if (level >= 0) {
    if (level <= LOW_THRESHOLD && !relayOn) {
      setRelay(true, "level<=LOW");
    } else if (level >= HIGH_THRESHOLD && relayOn) {
      setRelay(false, "level>=HIGH");
    }
  }

  char buf[180];
  snprintf(buf, sizeof(buf),
           "{\"ok\":true,\"relay\":%s,\"level\":%d,\"low\":%d,\"high\":%d}",
           relayOn ? "true" : "false", lastLevel, LOW_THRESHOLD, HIGH_THRESHOLD);
  server.send(200, "application/json", buf);
}

// ---------------- 调试：GET /status ----------------
static void handleStatus() {
  unsigned long ageSec = everSeen ? (millis() - lastSeenMs) / 1000UL : 0;
  char buf[300];
  snprintf(buf, sizeof(buf),
           "{\"ok\":true,\"online\":%s,\"age_sec\":%lu,\"relay\":%s,"
           "\"level\":%d,\"charging\":%s,\"device\":\"%s\",\"model\":\"%s\","
           "\"last_event\":\"%s\",\"phone_ts\":%ld}",
           (everSeen && ageSec < OFFLINE_TIMEOUT_MS / 1000UL) ? "true" : "false",
           ageSec, relayOn ? "true" : "false", lastLevel,
           lastCharging ? "true" : "false",
           lastDevice.c_str(), lastModel.c_str(),
           lastEvent.c_str(), lastPhoneTs);
  server.send(200, "application/json", buf);
}

// ---------------- WiFi ----------------
static void connectWiFi() {
  Serial.printf("[WiFi] connecting to \"%s\" ...\n", WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 20000UL) {
    delay(500);
    Serial.print('.');
  }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("[WiFi] connected  ip=%s  rssi=%d dBm\n",
                  WiFi.localIP().toString().c_str(), WiFi.RSSI());
    Serial.printf("[WiFi] 手机 App 里填这个 IP，端口 %d\n", HTTP_PORT);
  } else {
    Serial.println("[WiFi] failed, 15s 后重试");
  }
}

void setup() {
  Serial.begin(115200);
  delay(400);
  Serial.println();
  Serial.println("=== AutoPhoneZ / ESP32-S3 电量接收端 ===");

  pinMode(RELAY_PIN, OUTPUT);
  setRelay(false, "boot safe state");

  connectWiFi();

  server.on("/battery", HTTP_POST, handleBattery);
  server.on("/status", HTTP_GET, handleStatus);
  server.onNotFound([]() {
    server.send(404, "application/json", "{\"ok\":false,\"err\":\"not_found\"}");
  });
  server.begin();
  Serial.printf("[HTTP] listening on port %d  (POST /battery, GET /status)\n", HTTP_PORT);
  Serial.printf("[CTRL] 低电量<=%d%% 开充, 高电量>=%d%% 断电, 失联 %lu 秒熔断\n",
                LOW_THRESHOLD, HIGH_THRESHOLD, OFFLINE_TIMEOUT_MS / 1000UL);
}

void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    if (millis() - lastWifiRetryMs > 15000UL) {
      lastWifiRetryMs = millis();
      connectWiFi();
    }
  } else {
    server.handleClient();
  }

  // 失联保护：超过阈值没收到任何报文就断电，避免在无人监管时一直充
  if (everSeen && relayOn && (millis() - lastSeenMs) > OFFLINE_TIMEOUT_MS) {
    setRelay(false, "offline timeout, fail-safe");
    everSeen = false;
  }
}
