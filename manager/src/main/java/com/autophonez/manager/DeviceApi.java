package com.autophonez.manager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 与服务端（ESP32 或网页模拟端）通信。
 *
 * 接口契约见仓库根目录《ESP32接口说明.md》，三条：
 *   GET  /devices          拉全部设备状态
 *   POST /devices/config   下发某台设备的阈值和通道
 *   POST /devices/relay    手动覆盖某台设备的继电器
 * 全部用 Header X-Auth-Token 鉴权。
 */
public final class DeviceApi {

    private static final int TIMEOUT_MS = 8000;

    private DeviceApi() {
    }

    public static String baseUrl(String host, int port) {
        String h = host == null ? "" : host.trim();
        if (h.startsWith("https://")) {
            h = h.substring(8);
        } else if (h.startsWith("http://")) {
            h = h.substring(7);
        }
        while (h.endsWith("/")) {
            h = h.substring(0, h.length() - 1);
        }
        int p = port > 0 ? port : 8080;
        return "http://" + h + ":" + p;
    }

    /** 拉取设备列表；服务端未实现时抛异常。 */
    public static List<Device> list(String baseUrl, String token) throws Exception {
        String body = request("GET", baseUrl + "/devices", token, null);
        JSONObject root = new JSONObject(body);
        List<Device> out = new ArrayList<>();
        JSONArray arr = root.optJSONArray("devices");
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Device d = new Device();
            d.id = o.optString("device_id", o.optString("id", "unknown"));
            d.model = o.optString("model", "-");
            d.androidVer = o.optString("android", "-");
            d.plugged = o.optString("plugged", "-");
            d.event = o.optString("event", "-");
            d.lastIp = o.optString("last_ip", "-");
            d.level = o.optInt("level", -1);
            d.voltageMv = o.optInt("voltage_mv", o.optInt("volt", 0));
            d.tempC = o.optDouble("temp_c", o.optDouble("temp", 0));
            d.ageSec = o.optLong("age_sec", -1);
            d.count = o.optLong("count", 0);
            d.lowTh = o.optInt("low_th", 30);
            d.highTh = o.optInt("high_th", 90);
            d.relay = o.optInt("relay", 0);
            d.charging = o.optBoolean("charging", false);
            d.online = o.optBoolean("online", d.ageSec >= 0 && d.ageSec < 900);
            d.relayOn = o.optBoolean("relay_on", o.optBoolean("relay", false));
            d.manual = o.optBoolean("manual", false);
            out.add(d);
        }
        return out;
    }

    public static void setConfig(String baseUrl, String token, String deviceId,
                                 int lowTh, int highTh, int relay) throws Exception {
        JSONObject body = new JSONObject();
        body.put("device_id", deviceId);
        body.put("low_th", lowTh);
        body.put("high_th", highTh);
        body.put("relay", relay);
        request("POST", baseUrl + "/devices/config", token, body.toString());
    }

    public static void setRelay(String baseUrl, String token, String deviceId,
                                boolean on, boolean auto) throws Exception {
        JSONObject body = new JSONObject();
        body.put("device_id", deviceId);
        if (auto) {
            body.put("auto", true);
        } else {
            body.put("on", on);
        }
        request("POST", baseUrl + "/devices/relay", token, body.toString());
    }

    private static String request(String method, String url, String token, String body)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod(method);
            if (token != null && !token.isEmpty()) {
                c.setRequestProperty("X-Auth-Token", token);
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = readAll(in);
            if (code >= 400) {
                throw new Exception("HTTP " + code + (text.isEmpty() ? "" : " " + text));
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
