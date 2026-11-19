# ESP32 接口说明（电量汇聚 + 分区控制）

> 本文档是**服务端（ESP32-S3）**要实现、**上报端 App** 与**管理端 App** 共同遵守的协议。
> 先把协议定死，硬件到位后照这份文档写固件即可，两端 App 不用再改。

## 一、角色与数据流

```
┌─────────────────┐    POST /battery     ┌──────────────────────┐
│ 上报端 App ×N    │  X-Auth-Token: xxx   │  ESP32-S3 服务端      │
│ (电量哨兵)       │  JSON 电量事件        │                      │
└─────────────────┘ ───────────────────► │ · 收报文、存状态      │
                                          │ · 每设备阈值存 NVS    │
┌─────────────────┐    GET /relays       │ · 按 device_id 控制   │
│ 管理端 App       │ ◄─────────────────── │   对应继电器通道      │
│ (充电管理台)      │  POST /relay         │                      │
└─────────────────┘  （继电器开关测试）    └──────────┬───────────┘
                                                     │ GPIO
                                                     ▼
                                            继电器 0..3 / 充电口
```

三条原则：

1. **ESP32 是唯一的数据汇聚点与控制点**。管理端 App 只是「显示器 + 遥控器」，关掉不影响自动充电。
2. **上报链路不变**：手机端仍然是 `POST /battery`，现有 App 一行代码不用改。
3. **阈值判断在 ESP32 侧**：手机只报真相，不参与控制。

> 当前管理端 App 是**继电器测试版**，只调用 `/relays` 和 `/relay` 两条接口；
> `/devices/*` 三条设备接口预留给网页端和按电量自动控制的场景。

## 二、鉴权

所有接口（`/battery`、`/relays`、`/relay`、`/devices`、`/devices/config`、`/devices/relay`）统一用请求头：

```
X-Auth-Token: <与服务端一致的 Token>
```

不一致返回 `401 {"ok":false,"err":"bad_token"}`。

> 当前是 HTTP 明文，仅限可信局域网使用，不要把 8080 映射到公网。

## 三、接口清单

| 方法 | 路径 | 调用方 | 作用 |
|---|---|---|---|
| POST | `/battery` | 上报端 App | 上报电量事件（**现有协议，不变**） |
| GET | `/relays` | **管理端 App（测试）** | 获取 ESP32 控制了几路继电器、每路当前状态 |
| POST | `/relay` | **管理端 App（测试）** | 手动开/关某一路继电器 |
| GET | `/devices` | 网页/调试 | 拉取全部设备状态 + 各自配置 |
| POST | `/devices/config` | 网页/调试 | 下发某台设备的阈值与继电器通道 |
| POST | `/devices/relay` | 网页/调试 | 按设备手动覆盖继电器 |
| GET | `/status` | 调试 | 兼容旧接口，简单状态列表 |

---

## 四、POST /battery（现有，保持兼容）

```http
POST /battery HTTP/1.1
X-Auth-Token: autoz-phone-2026
Content-Type: application/json

{
  "device_id": "phoneA",
  "manufacturer": "Xiaomi",
  "model": "23127PN0CC",
  "brand": "Xiaomi",
  "android": "14",
  "sdk": 34,
  "event": "LOW_BATTERY",
  "level": 29,
  "charging": false,
  "plugged": "UNPLUGGED",
  "voltage_mv": 3620,
  "temp_c": 30.5,
  "low_th": 30,
  "high_th": 90,
  "relay": 0,
  "ts": 1763350000
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `device_id` | string | 设备唯一标识，管理端按它区分不同手机 |
| `model` | string | 机型 |
| `android` | string | Android 版本 |
| `event` | string | `HELLO` / `LOW_BATTERY` / `HIGH_BATTERY` / `PLUGGED` / `UNPLUGGED` / `HEARTBEAT` / `TEST` |
| `level` | int | 电量 0~100（已归一化） |
| `charging` | bool | 是否正在充电 |
| `plugged` | string | `AC` / `USB` / `WIRELESS` / `UNPLUGGED` |
| `voltage_mv` | int | 电池电压 mV |
| `temp_c` | float | 电池温度 ℃ |
| `low_th` | int | 低电量阈值 %，**上报端 App 里设置**，低于它该充电 |
| `high_th` | int | 高电量阈值 %，**上报端 App 里设置**，达到它该断电 |
| `relay` | int | 绑定的继电器通道号，**上报端 App 里选择**；`-1` = 不绑定（只上报不控制） |
| `ts` | long | 手机侧 Unix 秒 |

> 上报端 v1.7 起，充电区间（`low_th`/`high_th`）和绑定通道（`relay`）都在手机 App 里设置，
> 随每条报文一起上报。ESP32 收到后按 `relay` 找到对应 GPIO，用 `level` 与阈值比较决定通断；
> `relay = -1` 的设备只记录数据，不占用继电器通道。

响应：

```json
{"ok": true, "device_id": "phoneA", "relay": 1, "relay_on": true, "level": 29}
```

> 注意：**ESP32 不要依赖 `event` 类型做控制**，只按 `level` 与该设备自己的阈值判断，
> 这样即使漏收一条事件也不会跑偏。`event` 只用于展示和统计。

---

## 五、GET /devices

管理端每 3 秒轮询一次。

```http
GET /devices HTTP/1.1
X-Auth-Token: autoz-phone-2026
```

响应：

```json
{
  "ok": true,
  "server_time": 1763350000,
  "devices": [
    {
      "device_id": "phoneA",
      "model": "Xiaomi 23127PN0CC",
      "android": "14",
      "level": 55,
      "charging": true,
      "plugged": "AC",
      "temp_c": 30.5,
      "voltage_mv": 3620,
      "event": "HEARTBEAT",
      "age_sec": 12,
      "online": true,
      "count": 128,
      "last_ip": "192.168.124.7",
      "low_th": 30,
      "high_th": 90,
      "relay": 1,
      "relay_on": true,
      "manual": false
    }
  ]
}
```

关键字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `device_id` | string | 设备标识 |
| `level` | int | 最新电量，未上报过为 -1 |
| `age_sec` | long | 距最后一条报文的秒数，从未上报为 -1 |
| `online` | bool | 建议判定：`age_sec >= 0 && age_sec < 900` |
| `count` | long | 累计报文数 |
| `low_th` / `high_th` | int | 该设备当前阈值 |
| `relay` | int | 绑定的继电器通道，**-1 表示不控制**，0~3 为通道号 |
| `relay_on` | bool | 该通道当前通断状态 |
| `manual` | bool | 是否处于手动覆盖状态 |

---

## 六、POST /devices/config

```http
POST /devices/config HTTP/1.1
X-Auth-Token: autoz-phone-2026
Content-Type: application/json

{"device_id": "phoneA", "low_th": 30, "high_th": 90, "relay": 1}
```

字段与校验：

| 字段 | 必填 | 规则 |
|---|---|---|
| `device_id` | 是 | 必须已存在或允许预创建 |
| `low_th` | 是 | 5~60 |
| `high_th` | 是 | 50~100，且必须 `low_th < high_th` |
| `relay` | 是 | -1（不控制）或 0~3 |

响应：

```json
{"ok": true, "device_id": "phoneA", "low_th": 30, "high_th": 90, "relay": 1}
```

错误：

- `400 {"ok":false,"err":"bad_threshold"}` — 阈值越界或 low >= high
- `400 {"ok":false,"err":"bad_channel"}` — 通道不在 -1~3
- `404 {"ok":false,"err":"unknown_device"}` — 严格模式下设备不存在

> 简化实现可以允许「设备尚不存在时先建配置」，等第一次上报后生效。

---

## 七、POST /devices/relay

手动覆盖，调试和应急用。

```json
{"device_id": "phoneA", "on": true}
```

```json
{"device_id": "phoneA", "auto": true}
```

- `on: true` → 强制该通道通电，`manual = true`，此后阈值判断不生效
- `on: false` → 强制断电，`manual = true`
- `auto: true` → 清除手动状态，回到阈值自动控制

响应：

```json
{"ok": true, "device_id": "phoneA", "relay": 1, "relay_on": true, "manual": true}
```

---

## 八、GET /status（兼容保留）

旧版 App / 脚本用的简化接口，返回：

```json
{
  "ok": true,
  "devices": [
    {"id": "phoneA", "online": true, "age_sec": 12, "level": 55,
     "charging": true, "relay": true, "last_event": "HEARTBEAT"}
  ],
  "totals": {"devices": 1, "online": 1, "packets": 128}
}
```

新代码请用 `/devices`。

---

## 九、ESP32-S3 实现要点

### 9.1 数据结构

```c
struct DeviceState {
    char     id[24];
    char     model[48];
    char     android[8];
    int      level;          // -1 表示未知
    bool     charging;
    char     plugged[12];
    int      voltage_mv;
    float    temp_c;
    int      low_th, high_th, relay;  // relay: -1 不控制
    bool     relay_on;
    bool     manual;
    uint32_t count;
    time_t   last_seen;      // 本地接收时间
    char     last_event[16];
    char     last_ip[16];
};
```

建议最多 8 台设备，用定长数组即可，避免动态内存。

### 9.2 持久化

每台设备的 `low_th / high_th / relay` 存 NVS（`Preferences` 库），键名：

```
cfg_<device_id>  ->  "30,90,1"
```

开机读取，配置变更时写回。状态（level、last_seen 等）不需要持久化，重启清零。

### 9.3 控制逻辑

```
收到 /battery：
  找到/创建 DeviceState，更新字段、last_seen、count
  报文里的 low_th / high_th / relay 以手机为准：覆盖本地配置并写 NVS
  若 manual == true：跳过阈值判断
  否则：
    if level <= low_th  -> relay_on = true
    if level >= high_th -> relay_on = false
  按 relay 通道号驱动对应 GPIO（relay = -1 时只记录不控制）

收到 /devices/config：
  更新该设备 low_th/high_th/relay，写 NVS
  立即按新阈值重算一次 relay_on 并动作

收到 /devices/relay：
  on/auto 逻辑见第七节

收到 /relay（测试充电 App）：
  该通道 manual = true，直接 setRelay(id, on)
```

> 建议加一条「手动测试后恢复自动」的策略：收到该通道绑定设备的下一条 `/battery`
> 且事件为 `LOW_BATTERY` / `HIGH_BATTERY` 时清除 manual，或提供超时自动恢复，
> 避免测试完一直通电。

### 9.4 通道映射与驱动电平

| 通道 | 默认 GPIO（可改） |
|---|---|
| 0 | GPIO4 |
| 1 | GPIO5 |
| 2 | GPIO6 |
| 3 | GPIO7 |

- 避开 strapping 引脚 `GPIO0 / GPIO3 / GPIO45 / GPIO46`
- 同一通道被两台设备同时绑定时：后配置的生效，或直接拒绝并返回 `bad_channel`
  （推荐后者，避免两台手机抢同一路充电口）

**驱动电平必须和你的继电器模块匹配**，这是"App 显示成功但继电器不响"的最常见原因：

```cpp
const int  RELAY_PINS[]    = {4, 5, 6, 7};
const int  RELAY_COUNT     = 4;
const bool RELAY_ACTIVE_LOW = true;   // 低电平触发模块 = true；高电平触发 = false

void setRelay(int ch, bool on) {
  if (ch < 0 || ch >= RELAY_COUNT) return;
  int level = on ? (RELAY_ACTIVE_LOW ? LOW : HIGH)
                 : (RELAY_ACTIVE_LOW ? HIGH : LOW);
  digitalWrite(RELAY_PINS[ch], level);
  Serial.printf("relay ch%d -> %s (GPIO%d=%d)\n",
                ch, on ? "ON" : "OFF", RELAY_PINS[ch], level);
}
```

- 不确定触发方式时：把 IN 直接接 GND，吸合就是低电平触发（`RELAY_ACTIVE_LOW = true`）
- GPIO 不能直驱线圈，必须用带三极管/光耦的模块，并**共地**
- 5V 继电器模块不要用 3.3V 供电

### 9.5 安全兜底

1. **上电默认全断**（继电器 OFF），等第一条报文再决定
2. **失联熔断**：某设备超过 `OFFLINE_TIMEOUT_MS`（默认 30 分钟）没有报文，
   将其通道强制断电，避免满电无人管
3. **手动覆盖不自动过期**：`manual = true` 保留到收到 `auto:true` 或重启
4. **掉电恢复**：重启后从 NVS 恢复阈值配置，状态从零开始

### 9.6 固件最小实现清单

按这个顺序改，改完就能同时支撑「电量哨兵」和「测试充电」两个 App：

- [ ] 按实际硬件改 `RELAY_PINS` / `RELAY_COUNT` / `RELAY_ACTIVE_LOW`
- [ ] `setup()` 里每路 `pinMode(OUTPUT)` 并**默认断开**
- [ ] 所有接口校验 `X-Auth-Token`，不一致返回 401
- [ ] `POST /battery`：解析 `device_id / level / low_th / high_th / relay`，存配置、按阈值控制对应通道
- [ ] `GET /relays`：返回 `count` 和每路 `id / name / on / manual`
- [ ] `POST /relay`：校验通道号，置 `manual=true`，执行通断
- [ ] 失联熔断：超时无报文的设备，其通道强制断电
- [ ] 关键动作打串口日志（通道、目标状态、GPIO 电平），方便排查"没声音"

### 9.7 自测命令（烧录后先跑这几条）

把 `ESP32_IP` 和 `TOKEN` 换成你的：

```bash
# 1. 能拿到继电器列表，说明 /relays 通了
curl -H "X-Auth-Token: TOKEN" http://ESP32_IP:8080/relays

# 2. 通道 0 开 / 关，应该听到"咔哒"声，串口有日志
curl -X POST -H "X-Auth-Token: TOKEN" -H "Content-Type: application/json" \
     -d '{"id":0,"on":true}'  http://ESP32_IP:8080/relay
curl -X POST -H "X-Auth-Token: TOKEN" -H "Content-Type: application/json" \
     -d '{"id":0,"on":false}' http://ESP32_IP:8080/relay

# 3. 模拟手机低电量上报，通道 0 应自动通电
curl -X POST -H "X-Auth-Token: TOKEN" -H "Content-Type: application/json" \
     -d '{"device_id":"phoneA","level":25,"low_th":30,"high_th":90,"relay":0}' \
     http://ESP32_IP:8080/battery

# 4. 模拟充到高电量，通道 0 应自动断电
curl -X POST -H "X-Auth-Token: TOKEN" -H "Content-Type: application/json" \
     -d '{"device_id":"phoneA","level":92,"low_th":30,"high_th":90,"relay":0}' \
     http://ESP32_IP:8080/battery

# 5. 通道越界应返回 400 bad_channel
curl -X POST -H "X-Auth-Token: TOKEN" -H "Content-Type: application/json" \
     -d '{"id":9,"on":true}' http://ESP32_IP:8080/relay
```

> 第 2 步没声音就先别调 App：回到 9.4 查触发电平、供电、共地、GPIO 编号。

---

## 十、管理端 App 的行为（当前为继电器测试版）

管理端 App（`manager/`，应用名「测试充电」，包名 `com.autophonez.manager`）**只做继电器开关测试**：

| 行为 | 说明 |
|---|---|
| 连接 | 填 ESP32 的 IP:端口 + Token，保存后每 2 秒 `GET /relays` |
| 展示 | 每路继电器一行：通道号、当前 ON/OFF、是否手动状态 |
| 测试 | 每行「开」「关」按钮；顶部还有「全部打开 / 全部关闭」 |
| 边界 | **不做阈值配置、不做电量展示**。充放电逻辑完全在 ESP32 固件里 |

`/devices`、`/devices/config`、`/devices/relay` 这三条是**设备侧数据接口**，
网页端和后续按电量自动控制的场景会用到，管理端当前版本不调用。

---

## 十·五、继电器测试接口（管理端当前使用）

### GET /relays

```http
GET /relays HTTP/1.1
X-Auth-Token: autoz-phone-2026
```

响应：

```json
{
  "ok": true,
  "count": 4,
  "relays": [
    {"id": 0, "name": "通道 0", "on": false, "manual": false},
    {"id": 1, "name": "通道 1", "on": true,  "manual": false},
    {"id": 2, "name": "通道 2", "on": false, "manual": true},
    {"id": 3, "name": "通道 3", "on": false, "manual": false}
  ]
}
```

- `count`：ESP32 实际控制的继电器数量，**由固件决定，App 按返回长度渲染**
- `id`：通道号，从 0 开始
- `on`：当前通断状态
- `manual`：是否处于手动覆盖（测试期间一般为 true）

### POST /relay

```http
POST /relay HTTP/1.1
X-Auth-Token: autoz-phone-2026
Content-Type: application/json

{"id": 0, "on": true}
```

响应：

```json
{"ok": true, "id": 0, "on": true, "manual": true}
```

错误：

- `400 {"ok":false,"err":"bad_channel"}` — 通道号超出实际数量
- `400 {"ok":false,"err":"bad_request"}` — 缺少 `on` 字段或 JSON 非法
- `401` — Token 不匹配

> 手动测试后通道进入 `manual = true`；固件侧建议提供「恢复自动」逻辑
> （例如收到新的 `/battery` 事件、或定时超时后回到阈值控制），
> 避免测试完忘记切回自动导致一直通电。

---

## 十一、开发期联调（不用 ESP32）

`tools/web_dashboard.py` 已经实现了同一套 `/devices`、`/devices/config`、`/devices/relay`，
可以直接当模拟服务端：

```bash
python tools/web_dashboard.py          # 8080 / test-token-123456
```

然后：

1. 管理端 App 填 `电脑IP:8080` + 同一个 Token，点「保存并连接」
2. 制造设备数据：
   ```bash
   curl -X POST "http://127.0.0.1:8080/api/simulate?id=phoneA"
   curl -X POST "http://127.0.0.1:8080/api/simulate?id=phoneB"
   ```
3. 在 App 里改 `phoneA` 的阈值为 `25/85`、通道 `0`，观察面板上的模拟继电器状态
4. 手动强制通电/断电，确认 `relay_on` 与 `manual` 变化

验证通过的界面行为，换到 ESP32 后应当完全一致。

---

## 十二、错误码汇总

| 状态码 | 含义 |
|---|---|
| 200 | 成功 |
| 400 | JSON 解析失败 / 阈值非法 / 通道非法 |
| 401 | Token 不匹配 |
| 404 | 路径不存在 / 设备不存在（严格模式） |
| 405 | 方法不允许（例如对 `/devices` 用了 POST） |

统一响应体格式：`{"ok": true|false, "err": "原因"}`。

---

## 附录 A：核心代码骨架（Arduino / ESP32-S3）

> 依赖：`WebServer`、`ArduinoJson 7`、`Preferences`。下面是骨架，
> 配置结构体与 NVS 读写按 9.1 / 9.2 补全即可。

```cpp
#include <WiFi.h>
#include <WebServer.h>
#include <ArduinoJson.h>

// ============ 按实际硬件修改这三行 ============
const int  RELAY_PINS[]     = {4, 5, 6, 7};  // 每路继电器 IN 接的 GPIO
const int  RELAY_COUNT      = 4;             // 实际继电器数量
const bool RELAY_ACTIVE_LOW = true;          // 低电平触发=true，高电平触发=false
// ===========================================

WebServer server(8080);
bool relayOn[RELAY_COUNT]     = {false};
bool relayManual[RELAY_COUNT] = {false};

struct Device {
  String id;
  int level = -1;
  int low_th = 30, high_th = 90, relay = -1;
  uint32_t lastSeenMs = 0;
};
Device devices[8];   // 最多 8 台手机

void setRelay(int ch, bool on) {
  if (ch < 0 || ch >= RELAY_COUNT) return;
  int level = on ? (RELAY_ACTIVE_LOW ? LOW : HIGH)
                 : (RELAY_ACTIVE_LOW ? HIGH : LOW);
  digitalWrite(RELAY_PINS[ch], level);
  relayOn[ch] = on;
  Serial.printf("relay ch%d -> %s (GPIO%d=%d)\n",
                ch, on ? "ON" : "OFF", RELAY_PINS[ch], level);
}

bool authOk() {
  if (server.header("X-Auth-Token") != AUTH_TOKEN) {
    server.send(401, "application/json", "{\"ok\":false,\"err\":\"bad_token\"}");
    return false;
  }
  return true;
}

// 电量哨兵上报：按报文里的阈值与绑定通道控制
void handleBattery() {
  if (!authOk()) return;
  JsonDocument doc;
  if (deserializeJson(doc, server.arg("plain")) != DeserializationError::Ok) {
    server.send(400, "application/json", "{\"ok\":false,\"err\":\"bad_json\"}");
    return;
  }
  String id = doc["device_id"] | "";
  Device &d = findOrCreate(id);              // 按 id 查找/新建，见 9.1
  d.level   = doc["level"]   | -1;
  d.low_th  = doc["low_th"]  | d.low_th;
  d.high_th = doc["high_th"] | d.high_th;
  d.relay   = doc["relay"]   | d.relay;
  d.lastSeenMs = millis();
  saveCfg(d);                                // 写 NVS，见 9.2

  if (d.relay >= 0 && d.relay < RELAY_COUNT && d.level >= 0 && !relayManual[d.relay]) {
    if (d.level <= d.low_th)       setRelay(d.relay, true);   // 该充电
    else if (d.level >= d.high_th) setRelay(d.relay, false);  // 该断电
  }
  server.send(200, "application/json", "{\"ok\":true}");
}

// 测试充电 App：获取继电器数量与状态
void handleRelays() {
  if (!authOk()) return;
  JsonDocument doc;
  doc["ok"] = true;
  doc["count"] = RELAY_COUNT;
  JsonArray arr = doc["relays"].to<JsonArray>();
  for (int i = 0; i < RELAY_COUNT; i++) {
    JsonObject o = arr.add<JsonObject>();
    o["id"] = i;
    o["name"] = "通道 " + String(i);
    o["on"] = relayOn[i];
    o["manual"] = relayManual[i];
  }
  String out;
  serializeJson(doc, out);
  server.send(200, "application/json", out);
}

// 测试充电 App：手动开/关某一路
void handleRelay() {
  if (!authOk()) return;
  JsonDocument doc;
  if (deserializeJson(doc, server.arg("plain")) != DeserializationError::Ok) {
    server.send(400, "application/json", "{\"ok\":false,\"err\":\"bad_json\"}");
    return;
  }
  int id = doc["id"] | -1;
  if (id < 0 || id >= RELAY_COUNT || !doc["on"].is<bool>()) {
    server.send(400, "application/json", "{\"ok\":false,\"err\":\"bad_channel\"}");
    return;
  }
  bool on = doc["on"];
  relayManual[id] = true;
  setRelay(id, on);
  String out = "{\"ok\":true,\"id\":" + String(id) + ",\"on\":"
             + (on ? "true" : "false") + ",\"manual\":true}";
  server.send(200, "application/json", out);
}

void setup() {
  Serial.begin(115200);
  for (int i = 0; i < RELAY_COUNT; i++) {
    pinMode(RELAY_PINS[i], OUTPUT);
    setRelay(i, false);                      // 上电默认全断
  }
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  while (WiFi.status() != WL_CONNECTED) delay(300);
  Serial.print("IP: ");
  Serial.println(WiFi.localIP());            // 这个 IP 填到两个 App 里

  server.on("/battery", HTTP_POST, handleBattery);
  server.on("/relays",  HTTP_GET,  handleRelays);
  server.on("/relay",   HTTP_POST, handleRelay);
  server.begin();
}

void loop() {
  server.handleClient();
  // 失联熔断：超过 30 分钟没报文的设备，其通道强制断电
  for (auto &d : devices) {
    if (d.relay >= 0 && d.relay < RELAY_COUNT && d.lastSeenMs != 0
        && millis() - d.lastSeenMs > 30UL * 60UL * 1000UL) {
      setRelay(d.relay, false);
    }
  }
}
```

`WIFI_SSID` / `WIFI_PASS` / `AUTH_TOKEN` 放在 `include/config.h`（不要提交到仓库）。
