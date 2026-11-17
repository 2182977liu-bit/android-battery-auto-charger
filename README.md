# 手机自动充电控制器 · Android Battery Auto Charger

> 让手机**自己决定什么时候充电**：电量掉到 30% 自动通电，充到 90% 自动断电。
> Android App 上报电量事件 → 局域网 HTTP → ESP32-S3 继电器控制充电器。

**解决什么问题**：旧手机长期挂机（签到、跑脚本、当监控屏、当服务器）时，电池一直满电会鼓包、发烫、寿命骤降。
这个项目把充电这件事交给硬件按需控制，手机只负责说真话。

**不依赖什么**：不用云、不用公网、不用装服务器、不用 MQTT。断网照样跑，只要手机和控制器在同一个 WiFi 里。

`关键词`：安卓自动充电 · 电池保护 · 智能充电器 · ESP32-S3 继电器 · 电量监听 · 局域网 HTTP 上报 · 旧手机挂机

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

---

## 零、从哪开始

先判断你是哪种情况：

| 你的情况 | 走哪条路 | 时间 |
|---|---|---|
| 只想先看看数据，没有硬件 | 用**网页查看端**，电脑上跑一个 Python 文件 | 3 分钟 |
| 想让手机真的自动充电 | 需要 **ESP32-S3 + 继电器模块**（十几块） | 半小时 |
| 不想买硬件，但想手机自己控制 | 面板可跑在手机上的 Linux 容器（Podroid），见 [`手机端部署速查.md`](手机端部署速查.md) | 20 分钟 |
| 想统一管多台手机、给每台设阈值 | 装**管理端 App（充电管理台）**，接口见 [`ESP32接口说明.md`](ESP32接口说明.md) | 5 分钟 |

### 0.1 下载 App

| App | 装在哪 | 下载 |
|---|---|---|
| **电量哨兵**（上报端） | 每台要监控的手机 | [app-debug.apk（约 35 KB）](https://github.com/2182977liu-bit/android-battery-auto-charger/releases/latest/download/app-debug.apk) |
| **充电管理台**（管理端） | 你自己随身用的手机/平板 | [manager-debug.apk](https://github.com/2182977liu-bit/android-battery-auto-charger/releases/latest/download/manager-debug.apk) |

装好后在系统设置里给「电量哨兵」打开「安装未知应用」，之后 App 会自己检查更新，只需点一次「安装」。
管理端目前不做自动更新，有新版本重新下载安装即可。

### 0.2 三分钟跑通（不需要任何硬件）

```bash
# 1. 电脑装好 Python 3，把仓库里的 tools/web_dashboard.py 拷出来（单文件，零依赖）
python tools/web_dashboard.py

# 2. 终端会打印本机局域网 IP，比如 192.168.1.20
# 3. 手机 App 里填：IP=192.168.1.20  端口=8080  Token=test-token-123456  设备ID=phoneA
# 4. 点「保存并启动」，紧接着点一下「测试发送」
```

浏览器打开 `http://192.168.1.20:8080/`，应该立刻看到设备卡片和一条 `TEST` 事件。
看到就说明链路通了，接下来再决定要不要上硬件。

> Windows 首次运行会弹防火墙提示，**必须允许「专用网络」**，否则手机连不进来。

### 0.3 想真的自动充电

| 需要买 | 说明 |
|---|---|
| ESP32-S3 开发板 | 任意型号，带 USB 即可 |
| 单路继电器模块 | 3.3V 可驱动的，几块钱 |

接线和烧录见 [五、烧录 ESP32-S3](#五烧录-esp32-s3)。核心就一句：**继电器常开触点串在充电器的供电线上**，
手机零改造，不需要拆机改电池。

逻辑：`≤30%` 继电器闭合开始充电 → `≥90%` 断开 → 掉到 30% 再来一轮。带 5% 回滞，阈值附近抖动不会反复触发。

---

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

## 三·五、App 自动更新

仓库是 public，App 打开 3 秒后静默查一次，界面上的「检查更新」也可以手动触发：

1. 读 `releases/latest/download/version.txt`（内容就是版本号，如 `1.5`），与自身 `versionName` 比较
2. 有新版就下载 `releases/latest/download/app-debug.apk`（34 KB 上下，秒下）
3. 交给 `PackageInstaller`，系统弹安装确认页，点「安装」

**两个 URL 都不走 `api.github.com`**：GitHub 未鉴权 API 只有 **60 次/小时/IP** 配额，超了直接返回 **403**（不是 429），
而且按出口 IP 共享——运营商 NAT 后面的人多就更容易见底。
`releases/latest/download/<资产名>` 是 302 跳到静态 CDN，**零配额、无限次**，所以更新链路只走这条路。

**最后一步必须手点**——普通 App 没有静默安装权限，这是 Android 的硬限制，不是没做完。全自动需要手机 root 或 Device Owner 模式。

几个前提：

- 首次仍需手动装一次：v1.0 里没有更新代码，装上之后才具备自动更新能力
- 要在系统设置里给本应用打开「安装未知应用」，否则 `PackageInstaller` 直接失败（App 会提示并跳到设置页）
- Release 里必须同时有 `version.txt` 和 `app-debug.apk` 两个资产，CI 会自动传

CI 自动发版：改完 `versionName` 直接 push，workflow 会读 `app/build.gradle.kts`，建 `v<versionName>` 的 Release，
上传 APK 与 `version.txt`，最后再回读这两条直链自检。

完整实现细节（两侧数据契约、CI 片段、`PackageInstaller` 用法、踩坑清单、迁移到别的项目要改哪几处）
见 [`App自动更新实现说明.md`](App自动更新实现说明.md)。

多台设备 + 管理端 App 的完整接口契约（`GET /devices`、`POST /devices/config`、`POST /devices/relay`，
含字段表、错误码、ESP32-S3 实现要点与 NVS 结构）见 [`ESP32接口说明.md`](ESP32接口说明.md)。

## 三·六、App 界面

零第三方依赖，全部用系统控件 + 4 个 drawable XML，APK 约 34 KB：

| 区域 | 说明 |
|---|---|
| 顶部 | 标题、运行状态（运行绿 / 停止灰）、当前版本号 |
| 上报目标卡片 | IP、端口、设备 ID、Token |
| 阈值与心跳卡片 | 低/高电量阈值、心跳间隔、开机自启 |
| 操作区 | 主按钮「保存并启动」青绿实心，其余灰底圆角 |
| 运行日志 | 黑底圆角框，**固定 180dp**，超出后框内滚动，不再撑长页面 |

日志框行为：

- 新内容追加在**底部**，自动跟随滚动（类似 `tail -f`）
- 用户上翻历史时暂停跟随，回到底部附近再恢复
- 日志框嵌在页面的 ScrollView 里，已做触摸分发处理：按下时禁止父容器拦截，
  到顶继续下拉、到底继续上推时把滑动交回页面
- 只保留最近 40 条，无新内容时每秒刷新会跳过重绘，避免滚动位置抖动

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
| 事件流 | 最近 40 条**多设备混合**，带设备列、事件类型上色、来源 IP；固定高度滚动窗口，最新在底部、自动跟随，上翻即暂停 |
| 底部 | 手机该填的 IP/端口/Token、阈值，以及「模拟 phoneA/B」「清空数据」按钮 |

页面每秒自动刷新。

**多设备怎么区分**：靠 `device_id`。每台手机在 App 的「设备 ID」里填不同的值（`phoneA` / `phoneB` / …），
接收端按 `device_id` 各存一份状态、各自一条曲线、各自一套统计，互不覆盖。
**如果两台都留默认 `phoneA`，就会被当成同一台互相覆盖。**

设备卡片的排序按最近上报时间，最新的排最前；超过 15 分钟没数据的卡片会淡化。

`WebServer` 的接口与固件完全一致：`POST /battery`（带 `X-Auth-Token`）、`GET /status`。


> Windows 首次运行会弹防火墙提示，**必须勾选「专用网络」并允许**，否则手机连不进来。
> 这个页面只用于局域网测试，不要映射到公网——Token 是明文显示在页面上的。

#### 网页端在线改 Token

页面顶部有「上报 Token」输入框，保存后立即生效并写入 `AUTOZ_TOKEN_FILE` 指定的文件，重启服务不丢。

注意优先级：**token.txt 存在时，文件内容优先于命令行传入的 Token**。
所以走命令行改启动参数却不生效时，先看是不是文件里还存着旧值。
改完记得把手机 App 里的 Token 同步改掉，否则上报会 401。

另外可用 `AUTOZ_PUBLIC_IP` / `AUTOZ_PUBLIC_PORT` 覆盖页面提示里的对外地址——
容器里 `local_ip()` 只能拿到 NAT 内部地址，直接显示出来会误导。

#### 部署到手机（Podroid）

面板可以跑在手机的 Linux 容器里，让同一局域网设备访问。完整步骤见仓库根的
[`手机端部署速查.md`](手机端部署速查.md)：OpenRC 服务 `autoz` 开机自启，
nginx 把 `/battery` 反代到面板进程，页面在 `/phone/`。

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
| 更新失败 `HTTP 403` | 走的是 `api.github.com`，未鉴权配额 60 次/小时/IP 被打满。v1.3 起已改走静态直链，不受配额影响 |
| 日志框拖不动 | 页面 ScrollView 与日志 ScrollView 嵌套导致父容器拦截滑动，v1.5 起已做触摸分发处理 |

## 九、设计取舍（为什么这么做）

- **HTTP POST 而不是 MQTT**：只为单向上报，HTTP 能让 Android 端零第三方依赖，Gradle 不会因依赖下载失败把 CI 搞挂；ESP32 端用内置 `WebServer` 也零额外库。
- **事件驱动而不是轮询**：`ACTION_BATTERY_CHANGED` 是系统广播，电量不变时不消耗任何 CPU，比 `sleep 30` 轮询省电得多，插拔充电器还能秒级上报。
- **连通性判定用 `ts` 新鲜度 + 心跳**，不用 LWT/retain：手机端不是常驻连接，MQTT 的遗嘱消息在这种实现下会误报。
- **阈值判断放在 ESP32**：手机端只报真相，控制逻辑在硬件侧，两端实现可以独立替换。

## 十、已验证项（更新至 2026-11-17）

CI 与协议层面已跑通并核对：

| 项 | 结果 |
|---|---|
| `build-android` | ✅ 通过；v1.0 23.9 KB → v1.1 31.1 KB → v1.3 31.4 KB → v1.4 34.8 KB |
| 自动更新链路 | ✅ v1.3 起走 `releases/latest/download/version.txt` 与 `app-debug.apk` 静态直链，免鉴权、不消耗 API 配额 |
| CI 自动发版 | ✅ 读 `versionName` 建 Release，同时上传 APK 与 `version.txt`，并回读直链自检 |
| 直链一致性 | ✅ `latest/download/app-debug.apk` 与对应版本资产 md5 一致 |
| APK 签名 | ✅ 含 APK Signing Block（AGP 8 对 minSdk 24 默认只签 v2/v3，`META-INF` 里没有 CERT.RSA 属正常） |
| APK 内容 | ✅ `classes2.dex` 内含 `BatteryMonitorService` / `MainActivity` / `HttpPush` / `Payload` / `BootReceiver` / `Updater` / `InstallReceiver` |
| `build-esp32` | ✅ 通过，产出 `firmware.bin`（约 490 KB） |
| 两端字段一致性 | ✅ ESP32 读取的 10 个字段手机端全部发送，无缺失 |
| 端到端协议（模拟接收端） | ✅ 28% → 继电器 ON；55% → 保持 ON；91% → OFF；错误 Token → 401 |
| 网页端 Token 在线修改 | ✅ 新 Token 200 / 旧 Token 401 / 非法值 400，写入 token.txt 持久化 |
| 真机上报（OPPO PGFM10，Android 16） | ✅ `TEST` / `PLUGGED` / `HEARTBEAT` 均正常入面板 |

**尚未验证（必须真机做）**：

- ESP32-S3 烧录后的实际接收与继电器动作
- 手机 ROM 的后台存活（不同厂商差异很大，需挂机 72 小时）
- 真实充电器的通断时序

## 十一、版本记录

| 版本 | 内容 |
|---|---|
| v1.0 | 首版：阈值/充断电/机型上报 |
| v1.1 | 自动更新（初版走 GitHub API） |
| v1.2 | 日志改为固定高度滚动窗口 |
| v1.3 | 自动更新改走静态直链，修复 403 限流 |
| v1.4 | UI 重做：卡片分组、青绿主色、去 ActionBar |
| v1.5 | 修复日志框嵌套滚动冲突，可正常上下滑动 |

## 十二、License

个人项目，随取随用。
