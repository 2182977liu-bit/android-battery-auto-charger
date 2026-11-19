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
 * 继电器测试接口，与 ESP32-S3 通信。
 *
 *   GET  /relays   -> {"ok":true,"count":4,"relays":[{"id":0,"name":"通道 0","on":false}, ...]}
 *   POST /relay    -> body {"id":0,"on":true}
 *
 * 只做手动开关测试，充放电阈值逻辑在 ESP32 固件里，本 App 不参与。
 */
public final class RelayApi {

    private static final int TIMEOUT_MS = 8000;

    private RelayApi() {
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

    /** 获取 ESP32 当前控制的全部继电器及状态。 */
    public static List<Relay> list(String baseUrl, String token) throws Exception {
        String body = request("GET", baseUrl + "/relays", token, null);
        JSONObject root = new JSONObject(body);
        List<Relay> out = new ArrayList<>();
        JSONArray arr = root.optJSONArray("relays");
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Relay r = new Relay();
            r.id = o.optInt("id", i);
            r.name = o.optString("name", "通道 " + r.id);
            r.on = o.optBoolean("on", false);
            r.manual = o.optBoolean("manual", false);
            out.add(r);
        }
        return out;
    }

    /** 手动开/关某一路。 */
    public static void set(String baseUrl, String token, int id, boolean on) throws Exception {
        JSONObject body = new JSONObject();
        body.put("id", id);
        body.put("on", on);
        request("POST", baseUrl + "/relay", token, body.toString());
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
