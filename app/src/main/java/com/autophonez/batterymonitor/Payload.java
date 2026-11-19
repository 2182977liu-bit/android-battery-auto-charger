package com.autophonez.batterymonitor;

import android.os.Build;

import org.json.JSONObject;

/** 统一的报文构造：字段一旦确定就不要再改，ESP32 端按这份契约解析。 */
public final class Payload {

    public static final String EV_HELLO = "HELLO";
    public static final String EV_LOW = "LOW_BATTERY";
    public static final String EV_HIGH = "HIGH_BATTERY";
    public static final String EV_PLUGGED = "PLUGGED";
    public static final String EV_UNPLUGGED = "UNPLUGGED";
    public static final String EV_HEARTBEAT = "HEARTBEAT";
    public static final String EV_TEST = "TEST";

    private Payload() {
    }

    public static String build(Config cfg, BatteryState st, String event) {
        try {
            JSONObject o = new JSONObject();
            o.put("device_id", cfg.deviceId);
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("model", Build.MODEL);
            o.put("brand", Build.BRAND);
            o.put("android", Build.VERSION.RELEASE);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("event", event);
            o.put("level", st == null ? -1 : st.percent);
            o.put("charging", st != null && st.isCharging());
            o.put("plugged", st == null ? "UNKNOWN" : st.pluggedName());
            o.put("voltage_mv", st == null ? 0 : st.voltageMv);
            o.put("temp_c", st == null ? 0.0 : round1(st.tempC));
            o.put("low_th", cfg.low);
            o.put("high_th", cfg.high);
            o.put("relay", cfg.relay);
            o.put("ts", System.currentTimeMillis() / 1000L);
            return o.toString();
        } catch (Exception e) {
            LogBuf.add("payload error: " + e);
            return null;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
