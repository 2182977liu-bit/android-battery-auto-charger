# AutoPhoneZ · 电量哨兵

手机电量事件 → 局域网定向上报 → ESP32-S3 接收并控制继电器通断。
不做广播、不走公网，只有知道 `IP:端口 + Token` 的一方才能收到数据。

```
┌──────────────┐   POST http://ESP32_IP:8080/battery
│  Android App │   Header: X-Auth-Token: <你的Token>
│ 前台服务      │   Body:   JSON
└──────────────┘ ─────────────────────────────► ┌──────────────┐
                                                │  ESP32-S3    │
   事件驱动，不轮询                              │  WebServer   │
   （系统电量广播）                              │  继电器控制   │──► 充电器通电/断电
                                                └──────────────┘
```

## 一、触发规则

| event | 触发时机 |
|---|---|
| `HELLO` | 服务启动后的第一帧（含当前电量、充电状态、机型） |
| `LOW_BATTERY` | 电量降到 **≤ 30%**（带 5% 回滞，30% 附近抖动不会反复触发） |
| `HIGH_BATTERY` | 电量升到 **≥ 90%**（带 5% 回滞） |
| `PLUGGED` | 插上充电器（`EXTRA_PLUGGED` 由 0 变为非 0） |
| `UNPLUGGED` | 拔掉充电器 |
| `HEARTBEAT` | 定时心跳，默认 5 分钟（可关） |
| `TEST` | App 里「测试发送」按钮，用于验证链路 |

阈值在 App 里可改；ESP32 端的 `LOW_THRESHOLD` / `HIGH_THRESHOLD` 要与 App 一致。

## 二、报文格式（双方共同遵守的契约）

```json
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

- `level` 已归一化为 0~100 整数，不用再除 `EXTRA_SCALE`
- `plugged` 是字符串（`AC` / `USB` / `WIRELESS` / `UNPLUGGED`），不是位掩码
- `ts` 是手机侧的 Unix 秒，ESP32 用自己的接收时间另算
- **ESP32 侧不依赖 event 类型做控制**，只按 `level` 判断（≤30 开、≥90 断），这样即使漏收一条事件也不会跑偏

## 三、构建（GitHub Actions，本地不用装 Android Studio）

两个 workflow，push 到 `main` 或手动 `Run workflow` 触发：

| workflow | 产物 | 说明 |
|---|---|---|
| `build-android` | `AutoPhoneZ-debug-apk` | 用仓库内固定 keystore 签名，可覆盖安装 |
| `build-esp32` | `AutoPhoneZ-esp32s3-firmware` | 用 `config_example.h` 占位编译，**只是验证代码能过编译，不要直接烧这个 bin** |

下载路径：仓库 → Actions → 选一次成功的 run → 页面底部 **Artifacts**。

签名固定这件事很关键：debug keystore 提交在 `keystore/debug.keystore`（密码 `android`，公开的调试密钥），所以每次 CI 产出的 APK 签名一致，`adb install -r` 可以直接覆盖，不会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

## 四、装到手机

```bash
# 1. 下载 artifact 后解压
adb install -r app-debug.apk

# 2. Android 13+ 通知权限（不进前台通知栏，服务照样跑但你看不到状态）
adb shell pm grant com.autophonez.batterymonitor android.permission.POST_NOTIFICATIONS

# 3. 加入 Doze 白名单（也可在 App 里点「电池优化白名单」按钮）
adb shell dumpsys deviceidle whitelist +com.autophonez.batterymonitor

# 4. 看日志
adb logcat -s PhoneSensor:D
```

App 内配置：ESP32 的 IP、端口、Token、设备 ID，然后点「保存并启动」。
启动后通知栏会有一条常驻通知，显示 `xx% 充电中 | IP:端口`。

**国产 ROM 额外设置**（漏了会静默掉线）：设置里找到本应用 →
- 自启动 / 允许后台活动：打开
- 电池优化：无限制
- 后台弹出界面：允许
- 任务列表里给 App 加锁

## 五、烧录 ESP32-S3

```bash
cp esp32/include/config_example.h esp32/include/config.h
# 改 config.h：WIFI_SSID / WIFI_PASS / AUTH_TOKEN / RELAY_PIN

cd esp32
pio run -t upload
pio device monitor
```

串口正常输出：

```
=== AutoPhoneZ / ESP32-S3 电量接收端 ===
[RELAY] OFF 充电断开  (boot safe state)
[WiFi] connected  ip=192.168.1.100  rssi=-52 dBm
[WiFi] 手机 App 里填这个 IP，端口 8080
[HTTP] listening on port 8080  (POST /battery, GET /status)
```

收到手机报文：

```
[EVT] HELLO        dev=phoneA model=Xiaomi 23127PN0CC level=28% chg=0 plug=UNPLUGGED ...
[RELAY] ON  充电接通  (level<=LOW)
```

### 接线

| ESP32-S3 | 继电器模块 |
|---|---|
| `GPIO4`（`RELAY_PIN` 可改） | IN |
| `3V3` 或 `5V`（看模块） | VCC |
| `GND` | GND |

继电器常开触点串在**充电器 220V 供电**上（推荐，手机零改造），或串在 USB 供电线上。
`RELAY_ACTIVE_HIGH` 按模块是高电平还是低电平触发来设。

> 避开 ESP32-S3 的 strapping 引脚：`GPIO0 / GPIO3 / GPIO45 / GPIO46` 不要用作输出。

## 六、没有硬件时先验证链路

有两条路，推荐先用**网页版**，更直观。

### 6.1 网页查看端（推荐，可视化）

在局域网内任意一台电脑（Windows/macOS/Linux 都行）上跑，单文件、零依赖：

```bash
python tools/web_dashboard.py                 # 端口 8080，Token test-token-123456
python tools/web_dashboard.py 9000 mytoken    # 自定义端口和 Token
```

启动后终端会打印本机局域网 IP，**手机 App 里填这个 IP + 端口 + 同一个 Token**。

浏览器打开 `http://<电脑IP>:8080/` 能看到（**支持多设备**）：

| 区域 | 内容 |
|---|---|
| 顶部汇总 | 设备台数、在线台数、累计报文数 |
| 设备卡片（自动平铺） | 每台手机一张：设备 ID、机型+Android 版本、电量数字与进度条（≤30% 橙、≥90% 绿）、充电状态、温度、电压、供电方式、最后事件、最后上报时间、报文数、低/高电量触发次数、插拔次数、**继电器模拟状态**、迷你电量曲线 |
| 在线徽章 | 每台独立判定：<6.5 分钟「在线」、>6.5 分钟「延迟」、>15 分钟「离线」 |
| 事件流 | 最近 40 条**多设备混合**，带设备列、事件类型上色、来源 IP |
| 底部 | 手机该填的 IP/端口/Token、阈值，以及「模拟 phoneA/B」「清空数据」按钮 |

页面每秒自动刷新。

**多设备怎么区分**：靠 `device_id`。每台手机在 App 的「设备 ID」里填不同的值（`phoneA` / `phoneB` / …），
接收端按 `device_id` 各存一份状态、各自一条曲线、各自一套统计，互不覆盖。
**如果两台都留默认 `phoneA`，就会被当成同一台互相覆盖。**

设备卡片的排序按最近上报时间，最新的排最前；超过 15 分钟没数据的卡片会淡化。

`WebServer` 的接口与固件完全一致：`POST /battery`（带 `X-Auth-Token`）、`GET /status`。


> Windows 首次运行会弹防火墙提示，**必须勾选「专用网络」并允许**，否则手机连不进来。
> 这个页面只用于局域网测试，不要映射到公网——Token 是明文显示在页面上的。

#### 怎么用它判断「软件到底能不能用」

按这个顺序看，每一步都能定位问题：

1. App 点「保存并启动」后，网页应**立刻**出现一条 `HELLO` → 链路通了，Token 和 IP 都对
2. 点 App 的「测试发送」，网页出现 `TEST` → 双向可达
3. `adb shell dumpsys battery set level 20` → 网页出现 `LOW_BATTERY`，圆环变橙，模拟继电器变 ON
4. `dumpsys battery set ac 1` → 出现 `PLUGGED`；`unplug` → `UNPLUGGED`
5. `dumpsys battery set level 91` → 出现 `HIGH_BATTERY`，模拟继电器变 OFF
6. 什么都不做等 5 分钟 → 应出现 `HEARTBEAT`，说明**后台没被系统杀掉**
7. 如果超过 6.5 分钟没有任何报文 → 顶部徽章变黄（`数据延迟`），超过 15 分钟变红（`离线`）
   —— 这就是 ROM 后台清理的典型症状，去按第四节那四项设置

### 6.2 终端版模拟接收端

```bash
python3 tools/sim_server.py          # 默认 8080 / test-token-123456
python3 tools/sim_server.py 9000 mytoken   # 指定端口和 token
```

只在终端打日志，适合在服务器上跑着看。功能与网页版一致（同样的协议、同样的继电器模拟逻辑）。

## 七、验证闭环（不用真的等电量掉下去）

用 adb 直接改系统上报的电量，30 秒内跑完整个流程：

```bash
adb shell dumpsys battery set level 20      # 假装掉到 20%
adb shell dumpsys battery set ac 1          # 假装插上充电
adb shell dumpsys battery set level 85      # 假装充到 85%
adb shell dumpsys battery unplug            # 假装拔掉
adb shell dumpsys battery reset             # 恢复真实值（务必执行）
```

预期：`20%` → 收到 `LOW_BATTERY`，继电器 ON；`85%` → 断不到（阈值 90）；
`90% 以上` → `HIGH_BATTERY`，继电器 OFF。

同时可以直接查 ESP32 状态：

```bash
curl http://192.168.1.100:8080/status
```

## 八、排错

| 现象 | 原因 / 处理 |
|---|---|
| App 日志 `fail code=-1` | IP 不通：确认手机与 ESP32 同一网段、ESP32 串口打印的 IP 是否正确 |
| `fail code=401` | Token 不一致，两端逐字符核对 |
| `fail code=400` | 报文被拒绝，通常是 ESP32 端 JSON 解析失败（ArduinoJson 版本问题） |
| 通知栏没有但日志在跑 | Android 13+ 没给 `POST_NOTIFICATIONS` 权限 |
| 熄屏后突然不再上报 | ROM 后台清理，按第四节把四项设置打开 |
| WiFi 连不上 | ESP32-S3 **只支持 2.4GHz**，确认路由器有 2.4G 频段且与手机同频段 |
| 长时间无数据但继电器还吸着 | 固件有 30 分钟失联熔断，会强制断电；可改 `OFFLINE_TIMEOUT_MS` |

## 九、设计取舍（为什么这么做）

- **HTTP POST 而不是 MQTT**：只为单向上报，HTTP 能让 Android 端零第三方依赖，Gradle 不会因依赖下载失败把 CI 搞挂；ESP32 端用内置 `WebServer` 也零额外库。
- **事件驱动而不是轮询**：`ACTION_BATTERY_CHANGED` 是系统广播，电量不变时不消耗任何 CPU，比 `sleep 30` 轮询省电得多，插拔充电器还能秒级上报。
- **连通性判定用 `ts` 新鲜度 + 心跳**，不用 LWT/retain：手机端不是常驻连接，MQTT 的遗嘱消息在这种实现下会误报。
- **阈值判断放在 ESP32**：手机端只报真相，控制逻辑在硬件侧，两端实现可以独立替换。

## 十、已验证项（2026-11-16）

CI 与协议层面已跑通并核对：

| 项 | 结果 |
|---|---|
| `build-android` | ✅ 通过，产出 23.9 KB APK |
| APK 签名 | ✅ 含 APK Signing Block（AGP 8 对 minSdk 24 默认只签 v2/v3，`META-INF` 里没有 CERT.RSA 属正常） |
| APK 内容 | ✅ `classes2.dex` 内含 `BatteryMonitorService` / `MainActivity` / `HttpPush` / `Payload` / `BootReceiver` |
| `build-esp32` | ✅ 通过，产出 `firmware.bin`（约 490 KB） |
| 两端字段一致性 | ✅ ESP32 读取的 10 个字段手机端全部发送，无缺失 |
| 端到端协议（模拟接收端） | ✅ 28% → 继电器 ON；55% → 保持 ON；91% → OFF；错误 Token → 401 |

**尚未验证（必须真机做）**：

- ESP32-S3 烧录后的实际接收与继电器动作
- 手机 ROM 的后台存活（不同厂商差异很大，需挂机 72 小时）
- 真实充电器的通断时序

## 十一、License

个人项目，随取随用。
