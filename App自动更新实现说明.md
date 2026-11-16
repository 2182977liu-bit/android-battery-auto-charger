# App 自动更新实现说明

本文记录 AutoPhoneZ（`com.autophonez.batterymonitor`）里自动更新是怎么做出来的，
包括踩过的坑、验证方法和迁移到别的项目要改哪几处。

一句话概括：**App 读一个固定 URL 的 `version.txt` 比版本号，有新版本就下载固定 URL 的 APK，交给系统安装器让用户点一下「安装」。**

---

## 1. 能做什么，不能做什么

先把边界划清，避免做一半发现方向错了。

| 能力 | 是否可行 | 说明 |
|---|---|---|
| 检查新版本、自动下载 | ✅ | 纯网络请求，任何 App 都能做 |
| 拉起系统安装页，用户点一次 | ✅ | `PackageInstaller` + `PendingIntent`，本文采用 |
| 完全静默安装（无任何 UI） | ❌ 普通 App 不行 | 需要手机 root，或 Device Owner 模式，或系统签名 |
| 后台无人值守升级 | ❌ | 同上，最后一步必须用户确认 |
| 增量更新（只下差异） | ❌ | 需要 Play 的 `App Bundle` / 分渠道能力，自建分发做不了 |

结论：自建分发能做到「**自动检查 + 自动下载 + 一键确认**」，这已经是普通 App 的上限。

---

## 2. 整体链路

```
┌─────────────┐
│  手机 App    │
│             │  ① GET .../releases/latest/download/version.txt
│  Updater    │      ← 302 到静态 CDN，纯文本 "1.5"
│             │  ② 与 BuildConfig.VERSION_NAME 比较
│             │  ③ 有大版本：GET .../releases/latest/download/app-debug.apk
│             │  ④ PackageInstaller 建 session，写入 APK
│             │  ⑤ commit 后系统弹安装确认页
└─────────────┘
        │
        ▼
┌──────────────────────────────────────────────┐
│ GitHub Release（CI 每次构建自动创建/更新）        │
│   v1.5                                        │
│     ├── app-debug.apk   （固定文件名）           │
│     └── version.txt     （内容 "1.5"）          │
└──────────────────────────────────────────────┘
```

关键点：**App 只认「资产文件名」，不认版本号路径**。`releases/latest/download/<文件名>` 由 GitHub 自己解析成「最新 Release 里的这个文件」，所以 App 里没有硬编码任何版本号。

---

## 3. 为什么不用 `api.github.com`

初版是请求 `releases/latest` 拿 `tag_name` 和 `assets[].browser_download_url`，实测用户端直接失败：

```
更新失败：GitHub 返回 HTTP 403
```

抓响应头确认，不是网络也不是权限，是**未鉴权请求的配额**：

```
HTTP/2 403
x-ratelimit-limit: 60
x-ratelimit-remaining: 0
x-ratelimit-used: 60

{"message":"API rate limit exceeded for 101.47.179.157 ..."}
```

三个要点：

1. 未鉴权 `api.github.com` 只有 **60 次/小时**，超了返回 **403**（不是 429，容易误判成权限问题）
2. 配额**按出口 IP 共享**，运营商 NAT 后面的人一起用，很容易见底
3. 用户每点一次「检查更新」就消耗一次，越点越失败

而静态直链完全不经过 API：

```
GET https://github.com/<owner>/<repo>/releases/latest/download/version.txt
  302 → release-assets.githubusercontent.com/...
  200 → 内容
```

实测多次无任何配额限制，响应头里也没有 ratelimit 字段。

> 如果项目必须用 API（比如需要读取更多元数据），替代方案是给 App 内置只读 token，
> 但 token 会随 APK 反编译泄露，能读整个私有仓库，一般不值得。

---

## 4. 两侧的数据契约

自动更新能跑起来，全靠两条固定约定。

### 4.1 资产命名固定

| 资产名 | 内容 | 用途 |
|---|---|---|
| `app-debug.apk` | 构建产物 | 下载安装 |
| `version.txt` | 版本号纯文本，如 `1.5`，末尾一个换行 | 比较版本 |

文件名一旦定下就不能随意改，App 里写死了：

```java
private static final String BASE =
        "https://github.com/<owner>/<repo>/releases/latest/download/";
private static final String VERSION_FILE = "version.txt";
private static final String APK_FILE = "app-debug.apk";
```

### 4.2 版本号来源唯一

`version.txt` 的内容必须来自 `app/build.gradle.kts` 的 `versionName`，CI 读同一份配置生成，
不要手动写死，否则 App 里的 `BuildConfig.VERSION_NAME` 与实际发版号会分叉。

规则：**`versionName` 涨了才发版，且必须同时大于 App 内的 `versionName`。**

---

## 5. CI 侧：把发版做成自动的

在 `build-android.yml` 里加三步。前置条件是工作流要有写权限：

```yaml
permissions:
  contents: write
```

### 5.1 从构建配置里读版本

```yaml
      - name: Read version
        id: ver
        shell: bash
        run: |
          VN=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)
          VC=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts | head -1)
          echo "versionName=$VN" >> "$GITHUB_OUTPUT"
          echo "versionCode=$VC" >> "$GITHUB_OUTPUT"
```

### 5.2 建 Release 并同时传两个资产

```yaml
      - name: Publish Release
        if: github.ref == 'refs/heads/main'
        shell: bash
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          VN="${{ steps.ver.outputs.versionName }}"
          TAG="v$VN"
          APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
          printf '%s\n' "$VN" > version.txt
          if gh release view "$TAG" >/dev/null 2>&1; then
            gh release upload "$TAG" "$APK" version.txt --clobber
          else
            gh release create "$TAG" "$APK" version.txt \
              --title "AutoPhoneZ v$VN" \
              --notes "versionCode ${{ steps.ver.outputs.versionCode }} 自动构建"
          fi
```

注意 `--clobber`：同一个版本重复构建（修 CI 脚本、重跑）时能直接覆盖资产，否则会报资产已存在。

### 5.3 发完自检直链

这一步能在 CI 里就发现「资产没传上去」「latest 指向错版本」这类问题，比等手机上点更新才发现强：

```yaml
      - name: Verify direct links
        if: github.ref == 'refs/heads/main'
        shell: bash
        run: |
          BASE="https://github.com/${{ github.repository }}/releases/latest/download"
          VN=$(curl -sL "$BASE/version.txt" | tr -d '\r\n ')
          echo "version.txt -> [$VN]"
          curl -sL -o /tmp/check.apk "$BASE/app-debug.apk"
          ls -l /tmp/check.apk
```

### 5.4 签名必须稳定

CI 每次都要用同一个签名，否则覆盖安装会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

本项目把 debug keystore 直接提交进仓库（`keystore/debug.keystore`，密码 `android`，公开调试密钥），
所以每次产出的 APK 签名一致，`adb install -r` 和自动更新都能覆盖。

正式发布给别人的话要换成自己的 release keystore，用 GitHub Secrets 传入，不要提交进仓库。

---

## 6. App 侧：检查、下载、安装

三个动作拆成 `Updater` 静态工具类，`MainActivity` 只负责触发和展示。

### 6.1 版本检查

```java
public static String checkLatest(String currentVersion) throws Exception {
    String body = fetchText(BASE + VERSION_FILE);
    String ver = firstLine(body);              // 取第一行非空内容
    if (ver.isEmpty()) throw new Exception("version.txt 内容为空");
    if (compareVersion(ver, currentVersion) <= 0) return null;   // null = 已是最新
    return ver;
}
```

请求层要点：

- `setInstanceFollowRedirects(true)`：GitHub 这里有两跳（`github.com` → `release-assets.githubusercontent.com`）
- 设置 `User-Agent`，GitHub 对无 UA 的请求不友好
- 超时必设（连接 15s / 读 15s），否则弱网下长时间挂起
- `code >= 400` 时抛异常，错误信息里带上人话提示（403 提示限流、404 提示资产没上传）

版本比较用语义分段比较，不要直接字符串比大小（`"1.10" < "1.9"` 会错）：

```java
static int compareVersion(String a, String b) {
    String[] x = a.split("\\.");
    String[] y = b.split("\\.");
    int n = Math.max(x.length, y.length);
    for (int i = 0; i < n; i++) {
        int xi = i < x.length ? leadingInt(x[i]) : 0;
        int yi = i < y.length ? leadingInt(y[i]) : 0;
        if (xi != yi) return xi > yi ? 1 : -1;
    }
    return 0;
}
```

### 6.2 下载

存到 `ctx.getCacheDir()/update/update.apk`，不占用户存储、不需要任何存储权限。

```java
File dir = new File(ctx.getCacheDir(), "update");
File out = new File(dir, "update.apk");
if (out.exists() && !out.delete()) throw new Exception("旧安装包无法覆盖");
// 流式写入 + 每 200KB 回调一次进度
```

下载完成后按 `Content-Length` 校验大小，防止半包安装失败：

```java
if (expectSize > 0 && out.length() != expectSize) {
    throw new Exception("安装包大小不符，可能下载不完整");
}
```

### 6.3 安装（核心）

用 `PackageInstaller`，不需要 FileProvider、不需要 `content://`、不需要 `WRITE_EXTERNAL_STORAGE`：

```java
PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
PackageInstaller.SessionParams params =
        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
params.setAppPackageName(ctx.getPackageName());

int sessionId = pi.createSession(params);
PackageInstaller.Session session = pi.openSession(sessionId);
try {
    try (InputStream in = new FileInputStream(apk);
         OutputStream os = session.openWrite("base.apk", 0, apk.length())) {
        // 流式拷入
        session.fsync(os);
    }

    Intent target = new Intent(InstallReceiver.ACTION);
    target.setPackage(ctx.getPackageName());   // 显式包名，避免广播外泄
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        flags |= PendingIntent.FLAG_MUTABLE;   // API 31+ 必须显式声明可变性
    }
    session.commit(PendingIntent.getBroadcast(ctx, sessionId, target, flags).getIntentSender());
} finally {
    session.close();
}
```

三个容易忘的点：

1. `FLAG_MUTABLE`：API 31 起不声明可变性直接抛 `IllegalArgumentException`
2. `target.setPackage(...)`：广播带包名，防止被别的应用收走
3. `session.close()` 放 `finally`，否则安装失败时 session 泄漏，下次 `createSession` 可能报错

### 6.4 接收安装结果

`commit` 之后系统会通过 `PendingIntent` 把结果发到指定广播。注册一个静态 receiver：

```xml
<receiver
    android:name=".InstallReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="com.autophonez.batterymonitor.INSTALL_RESULT" />
    </intent-filter>
</receiver>
```

```java
@Override
public void onReceive(Context context, Intent intent) {
    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
    switch (status) {
        case PackageInstaller.STATUS_PENDING_USER_ACTION:
            // 系统要求确认，把安装页拉起来
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            break;
        case PackageInstaller.STATUS_SUCCESS:
            LogBuf.add("安装完成，重启应用后生效");
            break;
        default:
            LogBuf.add("安装失败 status=" + status);
            break;
    }
}
```

`STATUS_PENDING_USER_ACTION` 是「系统想弹确认但需要有个界面」的情况，**必须自己 `startActivity` 把 `EXTRA_INTENT` 拉起来**，否则用户看不到任何反应，会以为卡住了。

### 6.5 权限与首次引导

清单里声明：

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

安装前检查是否已授予「安装未知应用」，没给就跳到设置页：

```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        && !ctx.getPackageManager().canRequestPackageInstalls()) {
    throw new Exception("请先授予本应用「安装未知应用」权限");
}
```

```java
Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:" + getPackageName()));
startActivity(i);
```

不检查的话 `commit` 会静默失败，日志里只有一个 `status=6`，很难查。

### 6.6 触发时机

- 打开界面 3 秒后静默检查一次（只写日志，不弹窗打扰）
- 界面上的「检查更新」按钮手动触发，给 toast 反馈
- 不要在 `onResume` 里反复检查，会浪费请求

---

## 7. 踩过的坑

| 坑 | 现象 | 解法 |
|---|---|---|
| AGP 8 默认关 `buildConfig` | `BuildConfig.VERSION_NAME` 报 `cannot find symbol` | `android { buildFeatures { buildConfig = true } }` |
| 忘 `import android.app.PendingIntent` | CI 报 3 处 `cannot find symbol: variable PendingIntent` | 补 import |
| 用了 `api.github.com` | 更新失败 `HTTP 403` | 改走 `releases/latest/download/` 静态直链 |
| 403 被误判成权限/网络 | 反复重试仍失败 | 看响应头 `x-ratelimit-remaining: 0` 才是限流 |
| `latest` 指针切换有延迟 | 刚发的版本，直链下到的还是旧包（大小对不上） | 等几十秒再验；CI 里自检会暴露 |
| 版本号字符串直接比大小 | `1.10` 被判成小于 `1.9` | 按 `.` 分段比较 |
| 忘了签名一致性 | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 固定 keystore，CI 与本地一致 |
| 没声明 `FLAG_MUTABLE` | API 31+ 抛异常 | 条件加 flag |

---

## 8. 验证清单

改完不要只看 CI 绿灯，直接验链路（把 `<owner>/<repo>` 换成自己的）：

```bash
# 1. version.txt 内容是否为期望版本
curl -sL https://github.com/<owner>/<repo>/releases/latest/download/version.txt

# 2. APK 能否下载、大小是否与 Release 里一致
curl -sL -o /tmp/a.apk https://github.com/<owner>/<repo>/releases/latest/download/app-debug.apk
ls -l /tmp/a.apk

# 3. latest 指向的资产与指定版本是否同一份（md5 应完全一致）
curl -sL -o /tmp/b.apk https://github.com/<owner>/<repo>/releases/download/v1.5/app-debug.apk
md5sum /tmp/a.apk /tmp/b.apk

# 4. 不消耗 API 配额（同一请求连发几十次，不应出现 403）
for i in $(seq 1 30); do
  curl -s -o /dev/null -w "%{http_code}\n" -L \
    https://github.com/<owner>/<repo>/releases/latest/download/version.txt
done | sort | uniq -c
```

手机上验证：

1. 版本号比远端小 → 点「检查更新」应显示「发现新版本 vX」，日志出现下载进度
2. 下载完应弹出系统安装页，而不是毫无反应
3. 若弹不出来，先看系统设置里有没有给「安装未知应用」权限
4. 装完杀进程重开，界面版本号应变成新版本

---

## 9. 迁移到别的项目要改哪几处

只有 4 处，都是常量级：

| 位置 | 要改的东西 |
|---|---|
| `Updater.BASE` | `https://github.com/<owner>/<repo>/releases/latest/download/` |
| `Updater.VERSION_FILE` / `APK_FILE` | 资产文件名，与 CI 上传的名字一致 |
| `app/build.gradle.kts` | `versionCode` 每次 +1、`versionName` 递增 |
| workflow 的 `Publish Release` | 上传的两个文件名，与上面一致 |

另外两处视情况：

- 包名（`applicationId`）和 receiver 的 action 字符串，避免与别的应用撞车
- 安装结果要显示在哪（本项目写进进程内日志，你也可以用 Toast 或通知）

---

## 10. 成本

| 项 | 数值 |
|---|---|
| App 侧新增代码 | 约 260 行（`Updater` + `InstallReceiver`） |
| APK 体积增量 | 约 7 KB（v1.0 的 23.9 KB → 含更新的 v1.1 的 31.1 KB） |
| 新增权限 | 1 个（`REQUEST_INSTALL_PACKAGES`） |
| 新增依赖 | 0 个（JSON 都不需要，纯文本契约） |
| 网络请求 | 每次检查 1 次；有新版再 1 次下载（约 35 KB） |
| 配额消耗 | 0（全走静态直链） |

---

## 附：与本项目的关系

- 完整源码：`app/src/main/java/com/autophonez/batterymonitor/Updater.java`、`InstallReceiver.java`
- 版本行为：v1.1 首次接入（走 API）→ v1.3 改静态直链修 403 → v1.5 当前实现
- 发版流程：改 `versionName` → push → CI 构建并自动建 Release → 手机点「检查更新」
