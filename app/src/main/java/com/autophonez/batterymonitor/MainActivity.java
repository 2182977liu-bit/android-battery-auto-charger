package com.autophonez.batterymonitor;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends Activity {

    private EditText etHost;
    private EditText etPort;
    private EditText etToken;
    private EditText etDeviceId;
    private EditText etLow;
    private EditText etHigh;
    private EditText etHeartbeat;
    private CheckBox cbBoot;
    private TextView tvStatus;
    private TextView tvLog;
    private TextView tvVersion;
    private ScrollView svLog;
    private String lastLogText = "";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            SharedPreferences p = Config.prefs(MainActivity.this);
            boolean running = p.getBoolean(Config.KEY_RUNNING, false);
            tvStatus.setText(running ? "● 服务运行中" : "○ 服务已停止");
            appendLogTail();
            ui.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etHost = findViewById(R.id.etHost);
        etPort = findViewById(R.id.etPort);
        etToken = findViewById(R.id.etToken);
        etDeviceId = findViewById(R.id.etDeviceId);
        etLow = findViewById(R.id.etLow);
        etHigh = findViewById(R.id.etHigh);
        etHeartbeat = findViewById(R.id.etHeartbeat);
        cbBoot = findViewById(R.id.cbBoot);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        tvVersion = findViewById(R.id.tvVersion);
        svLog = findViewById(R.id.svLog);

        loadConfigToUi();

        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnTest = findViewById(R.id.btnTest);
        Button btnBattery = findViewById(R.id.btnBattery);

        btnStart.setOnClickListener(v -> startMonitor());
        btnStop.setOnClickListener(v -> stopMonitor());
        btnTest.setOnClickListener(v -> testSend());
        btnBattery.setOnClickListener(v -> requestIgnoreBatteryOptimization());

        Button btnUpdate = findViewById(R.id.btnUpdate);
        btnUpdate.setOnClickListener(v -> checkUpdate(true));

        requestNotificationPermission();
        ui.post(refresher);

        tvVersion.setText("当前 v" + BuildConfig.VERSION_NAME
                + "（" + BuildConfig.VERSION_CODE + "）");
        // 打开界面后静默检查一次，失败只在日志里留一行，不弹窗打扰
        ui.postDelayed(() -> checkUpdate(false), 3000L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(refresher);
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    // ------------------------------------------------------------ 配置存取

    private void loadConfigToUi() {
        Config c = Config.load(this);
        etHost.setText(c.host);
        etPort.setText(String.valueOf(c.port));
        etToken.setText(c.token);
        etDeviceId.setText(c.deviceId);
        etLow.setText(String.valueOf(c.low));
        etHigh.setText(String.valueOf(c.high));
        etHeartbeat.setText(String.valueOf(c.heartbeatMin));
        cbBoot.setChecked(c.bootStart);
    }

    private boolean saveConfigFromUi() {
        String host = etHost.getText().toString().trim();
        String token = etToken.getText().toString().trim();

        if (host.isEmpty()) {
            toast("请填写 ESP32 的 IP");
            return false;
        }
        if (token.isEmpty()) {
            toast("请填写 Token（必须与 ESP32 端一致）");
            return false;
        }

        SharedPreferences.Editor e = Config.prefs(this).edit();
        e.putString(Config.KEY_HOST, host);
        e.putString(Config.KEY_PORT, etPort.getText().toString().trim());
        e.putString(Config.KEY_TOKEN, token);
        e.putString(Config.KEY_DEVICE_ID, etDeviceId.getText().toString().trim());
        e.putString(Config.KEY_LOW, etLow.getText().toString().trim());
        e.putString(Config.KEY_HIGH, etHigh.getText().toString().trim());
        e.putString(Config.KEY_HEARTBEAT_MIN, etHeartbeat.getText().toString().trim());
        e.putBoolean(Config.KEY_BOOT_START, cbBoot.isChecked());
        e.apply();

        Config c = Config.load(this);
        LogBuf.add("配置已保存 " + c.host + ":" + c.port + " 阈值 " + c.low + "/" + c.high
                + "% 心跳 " + c.heartbeatMin + "min");
        return true;
    }

    // ------------------------------------------------------------ 操作

    private void startMonitor() {
        if (!saveConfigFromUi()) {
            return;
        }
        Intent i = new Intent(this, BatteryMonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            toast("已启动，注意看通知栏");
        } catch (Exception e) {
            toast("启动失败：" + e);
        }
    }

    private void stopMonitor() {
        stopService(new Intent(this, BatteryMonitorService.class));
        toast("已停止");
    }

    /** 不改动电量也能验证链路，第一次配完务必点这个。 */
    private void testSend() {
        if (!saveConfigFromUi()) {
            return;
        }
        final Config cfg = Config.load(this);
        Intent sticky = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        final BatteryState st = BatteryState.fromIntent(sticky);

        LogBuf.add("test -> " + cfg.host + ":" + cfg.port);
        new Thread(() -> {
            String body = Payload.build(cfg, st, Payload.EV_TEST);
            int code = HttpPush.post(cfg.host, cfg.port, cfg.token, body);
            LogBuf.add(code >= 200 && code < 300
                    ? "test ok http=" + code
                    : "test fail code=" + code + "（检查 IP/端口/Token/WiFi）");
        }).start();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("已在电池优化白名单内");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                toast("请手动到系统设置里关闭电池优化");
            }
        }
    }

    // ------------------------------------------------------------ 自动更新

    /**
     * 检查 GitHub 最新版本。manual=false 时是启动静默检查，
     * 只写日志不弹提示；manual=true 是用户主动点「检查更新」。
     */
    private void checkUpdate(boolean manual) {
        if (manual) {
            toast("正在检查更新…");
        }
        LogBuf.add("检查更新 v" + BuildConfig.VERSION_NAME);

        new Thread(() -> {
            try {
                String[] r = Updater.checkLatest(BuildConfig.VERSION_NAME);
                if (r == null) {
                    LogBuf.add("已是最新版本");
                    if (manual) {
                        ui.post(() -> toast("已是最新版本"));
                    }
                    return;
                }
                final String ver = r[0];
                LogBuf.add("发现新版本 v" + ver + "，开始下载");
                File apk = Updater.download(MainActivity.this, r[1],
                        Long.parseLong(r[2]), msg -> LogBuf.add(msg));
                LogBuf.add("下载完成 " + apk.length() + " 字节，拉起安装器");
                Updater.install(MainActivity.this, apk);
                ui.post(() -> toast("已下载 v" + ver + "，请在安装页点「安装」"));
            } catch (Exception e) {
                final String m = e.getMessage() == null ? e.toString() : e.getMessage();
                LogBuf.add("更新失败：" + m);
                if (m.contains("安装未知应用")) {
                    ui.post(this::openUnknownSourcesSettings);
                }
                if (manual) {
                    ui.post(() -> toast("更新失败：" + m));
                }
            }
        }).start();
    }

    private void openUnknownSourcesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            toast("请手动到系统设置里允许「安装未知应用」");
        }
    }

    /**
     * 日志框高度固定（180dp），超出后在框内滚动，不再把整个页面撑长。
     * 有新内容时自动滚到底；用户正在往上翻历史就不打扰，
     * 等他回到底部附近再恢复跟随。
     */
    private void appendLogTail() {
        String text = LogBuf.dump();
        if (text.equals(lastLogText)) {
            return;
        }
        lastLogText = text;

        View child = svLog.getChildAt(0);
        boolean follow = child == null
                || child.getHeight() - svLog.getHeight() - svLog.getScrollY() < 40;
        tvLog.setText(text);
        if (follow) {
            svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
