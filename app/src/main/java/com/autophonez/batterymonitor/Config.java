package com.autophonez.batterymonitor;

import android.content.Context;
import android.content.SharedPreferences;

/** 配置读写，全部落在 SharedPreferences，Service 与 Activity 共用一份。 */
public final class Config {

    private static final String PREF = "autophonez_cfg";

    public static final String KEY_HOST = "host";
    public static final String KEY_PORT = "port";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_DEVICE_ID = "device_id";
    public static final String KEY_LOW = "low";
    public static final String KEY_HIGH = "high";
    public static final String KEY_HEARTBEAT_MIN = "hb_min";
    public static final String KEY_BOOT_START = "boot_start";
    public static final String KEY_RUNNING = "running";

    public final String host;
    public final int port;
    public final String token;
    public final String deviceId;
    public final int low;
    public final int high;
    public final int heartbeatMin;
    public final boolean bootStart;

    private Config(String host, int port, String token, String deviceId,
                   int low, int high, int heartbeatMin, boolean bootStart) {
        this.host = host;
        this.port = port;
        this.token = token;
        this.deviceId = deviceId;
        this.low = low;
        this.high = high;
        this.heartbeatMin = heartbeatMin;
        this.bootStart = bootStart;
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static Config load(Context c) {
        SharedPreferences p = prefs(c);
        String host = trim(p.getString(KEY_HOST, ""));
        int port = clamp(parseInt(p.getString(KEY_PORT, "8080"), 8080), 1, 65535);
        String token = trim(p.getString(KEY_TOKEN, ""));
        String deviceId = trim(p.getString(KEY_DEVICE_ID, "phoneA"));
        if (deviceId.isEmpty()) deviceId = "phoneA";
        int low = clamp(parseInt(p.getString(KEY_LOW, "30"), 30), 5, 80);
        int high = clamp(parseInt(p.getString(KEY_HIGH, "90"), 90), low + 5, 100);
        int hb = clamp(parseInt(p.getString(KEY_HEARTBEAT_MIN, "5"), 5), 0, 180);
        boolean boot = p.getBoolean(KEY_BOOT_START, true);
        return new Config(host, port, token, deviceId, low, high, hb, boot);
    }

    /** host / token 都必须填，否则拒绝发送，避免把数据发到错误的地方。 */
    public boolean isValid() {
        return !host.isEmpty() && port > 0 && port < 65536 && !token.isEmpty();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(trim(s));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
