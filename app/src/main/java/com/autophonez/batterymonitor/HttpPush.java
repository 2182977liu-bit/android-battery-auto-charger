package com.autophonez.batterymonitor;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 定向 HTTP POST 到 ESP32-S3。用 HttpURLConnection，不引第三方库，
 * 避免 Gradle 依赖下载失败把构建搞挂。
 */
public final class HttpPush {

    private static final int TIMEOUT_MS = 3000;

    private HttpPush() {
    }

    /** @return HTTP 状态码，失败返回 -1 */
    public static int post(String host, int port, String token, String json) {
        if (json == null) return -1;
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + host + ":" + port + "/battery");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-Auth-Token", token);
            byte[] body = json.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(body.length);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
            return conn.getResponseCode();
        } catch (Exception e) {
            LogBuf.add("http err " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
