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
┌─────────────────┐    GET /devices      │ · 按 device_id 控制   │
│ 管理端 App       │ ◄─────────────────── │   对应继电器通道      │
│ (充电管理台)      │  POST /devices/config│                      │
└─────────────────┘  POST /devices/relay └──────────┬───────────┘
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

所有接口（`/battery`、`/devices`、`/devices/config`、`/devices/relay`）统一用请求头：

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
| `ts` | long | 手机侧 Unix 秒 |

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
  若 manual == true：跳过阈值判断
  否则：
    if level <= low_th  -> relay_on = true
    if level >= high_th -> relay_on = false
  按 relay 通道号驱动对应 GPIO

收到 /devices/config：
  更新该设备 low_th/high_th/relay，写 NVS
  立即按新阈值重算一次 relay_on 并动作

收到 /devices/relay：
  on/auto 逻辑见第七节
```

### 9.4 通道映射

| 通道 | 默认 GPIO（可改） |
|---|---|
| 0 | GPIO4 |
| 1 | GPIO5 |
| 2 | GPIO6 |
| 3 | GPIO7 |

- 避开 strapping 引脚 `GPIO0 / GPIO3 / GPIO45 / GPIO46`
- 同一通道被两台设备同时绑定时：后配置的生效，或直接拒绝并返回 `bad_channel`
  （推荐后者，避免两台手机抢同一路充电口）

### 9.5 安全兜底

1. **上电默认全断**（继电器 OFF），等第一条报文再决定
2. **失联熔断**：某设备超过 `OFFLINE_TIMEOUT_MS`（默认 30 分钟）没有报文，
   将其通道强制断电，避免满电无人管
3. **手动覆盖不自动过期**：`manual = true` 保留到收到 `auto:true` 或重启
4. **掉电恢复**：重启后从 NVS 恢复阈值配置，状态从零开始

---

## 十、管理端 App 的行为（当前为继电器测试版）

管理端 App（`manager/`，包名 `com.autophonez.manager`）**只做继电器开关测试**：

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
