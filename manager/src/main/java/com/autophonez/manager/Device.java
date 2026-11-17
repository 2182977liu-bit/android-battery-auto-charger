package com.autophonez.manager;

/** 一台上报设备的状态 + 该设备的阈值/通道配置。 */
public class Device {
    public String id = "";
    public String model = "";
    public String androidVer = "";
    public String plugged = "";
    public String event = "";
    public String lastIp = "";
    public int level = -1;
    public int voltageMv = 0;
    public double tempC = 0;
    public long ageSec = -1;
    public long count = 0;
    public int lowTh = 30;
    public int highTh = 90;
    public int relay = 0;          // 绑定的继电器通道，-1 表示不控制
    public boolean charging = false;
    public boolean online = false;
    public boolean relayOn = false;
    public boolean manual = false; // 是否处于手动覆盖状态

    /** "刚刚 / 12 秒前 / 3 分钟前 / 未上报" */
    public String ageText() {
        if (ageSec < 0) {
            return "未上报";
        }
        if (ageSec < 5) {
            return "刚刚";
        }
        if (ageSec < 60) {
            return ageSec + " 秒前";
        }
        if (ageSec < 3600) {
            return (ageSec / 60) + " 分钟前";
        }
        return (ageSec / 3600) + " 小时前";
    }

    public String relayText() {
        if (relay < 0) {
            return "不控制";
        }
        return "通道 " + relay + " · " + (relayOn ? "ON" : "OFF") + (manual ? "（手动）" : "");
    }
}
