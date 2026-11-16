#pragma once

// ============================================================
// 把本文件复制为 config.h 后修改（config.h 不会提交到仓库）
//   cp include/config_example.h include/config.h
// ============================================================

// ---- WiFi：ESP32-S3 只支持 2.4GHz，5G 频段连不上 ----
#define WIFI_SSID "YOUR_WIFI_SSID"
#define WIFI_PASS "YOUR_WIFI_PASSWORD"

// ---- HTTP 服务 ----
#define HTTP_PORT 8080
// 必须与手机 App 里填的 Token 完全一致，建议 16 位以上随机串
#define AUTH_TOKEN "CHANGE_ME_TO_A_LONG_RANDOM_TOKEN"

// ---- 继电器 ----
// 避开 ESP32-S3 的 strapping 引脚：GPIO0 / GPIO3 / GPIO45 / GPIO46 不要用
#define RELAY_PIN 4
// 高电平吸合填 1，低电平吸合填 0
#define RELAY_ACTIVE_HIGH 1

// ---- 充电控制阈值（与手机 App 里的阈值保持一致）----
#define LOW_THRESHOLD 30
#define HIGH_THRESHOLD 90

// ---- 失联保护：超过该时间没收到任何报文，强制断电（毫秒）----
#define OFFLINE_TIMEOUT_MS (30UL * 60UL * 1000UL)
