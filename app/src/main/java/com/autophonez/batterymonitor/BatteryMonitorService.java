package com.autophonez.batterymonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前台服务：监听 ACTION_BATTERY_CHANGED（事件驱动，不是定时轮询），
 * 在跨阈值 / 插拔 / 首次启动 / 心跳 时向 ESP32-S3 定向 POST。
 */
public class BatteryMonitorService extends Service {

    public static final String TAG = "PhoneSensor";
    private static final String CHANNEL_ID = "battery_push";
    private static final int NOTIF_ID = 1001;

    private final ExecutorService netPool = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Config cfg;
    private BatteryState current;
    private BatteryState last;

    /** 带 re-arm 的阈值闸门，避免 30% 附近反复抖动触发。 */
    private boolean lowArmed = true;
    private boolean highArmed = true;

    private boolean receiverRegistered = false;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                return;
            }
            BatteryState st = BatteryState.fromIntent(intent);
            current = st;
            handleState(st);
            refreshNotification();
        }
    };

    private final Runnable heartbeatTask = new Runnable() {
        @Override
        public void run() {
            Config c = Config.load(BatteryMonitorService.this);
            if (c.isValid() && current != null && c.heartbeatMin > 0) {
                push(Payload.EV_HEARTBEAT, current);
            }
            long delay = Math.max(1, c.heartbeatMin) * 60_000L;
            mainHandler.postDelayed(this, delay);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        cfg = Config.load(this);
        ensureChannel();
        startAsForeground(buildNotification("正在启动…"));
        registerBatteryReceiver();
        mainHandler.postDelayed(heartbeatTask, 60_000L);
        Config.prefs(this).edit().putBoolean(Config.KEY_RUNNING, true).apply();
        LogBuf.add("service started id=" + cfg.deviceId + " -> " + cfg.host + ":" + cfg.port);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        cfg = Config.load(this);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Exception ignored) {
                // 已经注销过，忽略
            }
            receiverRegistered = false;
        }
        Config.prefs(this).edit().putBoolean(Config.KEY_RUNNING, false).apply();
        LogBuf.add("service stopped");
        netPool.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 状态机

    private void handleState(BatteryState st) {
        // 每次广播都重读配置：App 里改了阈值/绑定通道，不用重启服务就生效
        cfg = Config.load(this);
        if (last == null) {
            // 首次（注册 sticky 广播时立即回吐当前值）：只发 HELLO，
            // 但要把闸门摆到正确位置，避免下一次重复触发。
            if (st.percent > cfg.low + 5) {
                lowArmed = true;
            } else if (st.percent >= 0 && st.percent <= cfg.low) {
                lowArmed = false;
            }
            if (st.percent < cfg.high - 5) {
                highArmed = true;
            } else if (st.percent >= cfg.high) {
                highArmed = false;
            }
            LogBuf.add("first state " + st.percent + "% charging=" + st.isCharging());
            push(Payload.EV_HELLO, st);
            last = st;
            return;
        }

        if (st.percent >= 0) {
            // re-arm：回落/回升到远离阈值的区间才重新武装
            if (st.percent > cfg.low + 5) {
                lowArmed = true;
            }
            if (st.percent < cfg.high - 5) {
                highArmed = true;
            }

            if (st.percent <= cfg.low && lowArmed) {
                lowArmed = false;
                push(Payload.EV_LOW, st);
            }
            if (st.percent >= cfg.high && highArmed) {
                highArmed = false;
                push(Payload.EV_HIGH, st);
            }
        }

        if (last.isCharging() != st.isCharging()) {
            push(st.isCharging() ? Payload.EV_PLUGGED : Payload.EV_UNPLUGGED, st);
        }

        last = st;
    }

    // ---------------------------------------------------------------- 发送

    private void push(final String event, final BatteryState st) {
        final Config c = Config.load(this);
        if (!c.isValid()) {
            LogBuf.add("skip " + event + ": 未配置服务器或 Token");
            return;
        }
        final String body = Payload.build(c, st, event);
        if (body == null) {
            return;
        }
        LogBuf.add("send " + event + (st == null ? "" : " " + st.percent + "%"));

        netPool.execute(new Runnable() {
            @Override
            public void run() {
                int code = -1;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    code = HttpPush.post(c.host, c.port, c.token, body);
                    if (code >= 200 && code < 300) {
                        LogBuf.add("ok " + event + " http=" + code);
                        return;
                    }
                    if (attempt < 3) {
                        try {
                            Thread.sleep(800L * attempt);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                LogBuf.add("fail " + event + " code=" + code);
            }
        });
    }

    // ---------------------------------------------------------------- 通知

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "电量上报", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void startAsForeground(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentTitle("电量哨兵")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        return b.build();
    }

    private void refreshNotification() {
        BatteryState st = current;
        String text;
        if (st == null) {
            text = "等待电量数据 | " + cfg.host + ":" + cfg.port;
        } else {
            text = st.percent + "% " + (st.isCharging() ? "充电中" : "未充电")
                    + " | " + cfg.host + ":" + cfg.port;
        }
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(text));
        }
    }

    // ---------------------------------------------------------------- 接收器

    private void registerBatteryReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(batteryReceiver, filter);
        }
        receiverRegistered = true;
        LogBuf.add("battery receiver registered");
    }
}
