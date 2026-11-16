package com.autophonez.batterymonitor;

import android.content.Intent;
import android.os.BatteryManager;

/** 从 ACTION_BATTERY_CHANGED 里解出的电量快照，所有字段已归一化。 */
public final class BatteryState {

    public final int percent;
    public final int rawLevel;
    public final int scale;
    public final int plugged;
    public final int status;
    public final int voltageMv;
    public final double tempC;

    public BatteryState(int percent, int rawLevel, int scale, int plugged,
                        int status, int voltageMv, double tempC) {
        this.percent = percent;
        this.rawLevel = rawLevel;
        this.scale = scale;
        this.plugged = plugged;
        this.status = status;
        this.voltageMv = voltageMv;
        this.tempC = tempC;
    }

    public boolean isCharging() {
        return plugged != 0;
    }

    /**
     * EXTRA_PLUGGED 是位掩码：AC=1 / USB=2 / WIRELESS=4 / DOCK=8。
     * 这里只输出可读名称，不要把原始位掩码透给 ESP32。
     */
    public String pluggedName() {
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_AC:
                return "AC";
            case BatteryManager.BATTERY_PLUGGED_USB:
                return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                return "WIRELESS";
            default:
                if (plugged != 0) return "OTHER";
                return "UNPLUGGED";
        }
    }

    public static BatteryState fromIntent(Intent it) {
        if (it == null) {
            return new BatteryState(-1, -1, 100, 0, 0, 0, 0.0);
        }
        int raw = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = (raw >= 0 && scale > 0) ? (int) Math.round(raw * 100.0 / scale) : -1;
        int plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int status = it.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        int voltage = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int tenths = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        double temp = tenths / 10.0;
        return new BatteryState(pct, raw, scale, plugged, status, voltage, temp);
    }
}
