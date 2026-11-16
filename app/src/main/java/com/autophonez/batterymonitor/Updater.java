package com.autophonez.batterymonitor;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 自动更新：从 GitHub Releases 取最新版本，下载 APK，交给系统安装器。
 *
 * 仓库是 public，所以不需要任何 token；每次只发两个请求（版本 + APK）。
 * 安装走 PackageInstaller，系统会弹一次确认，这是普通 App 的上限——
 * 想完全静默需要 root 或 Device Owner。
 */
public final class Updater {

    private static final String API =
            "https://api.github.com/repos/2182977liu-bit/auto_phone_z/releases/latest";
    private static final int TIMEOUT_MS = 15000;

    public interface Progress {
        void onStatus(String msg);
    }

    private Updater() {
    }

    /**
     * 检查最新版本。
     *
     * @return null 表示已是最新；否则返回 {versionName, apkUrl, sizeBytes}
     */
    public static String[] checkLatest(String currentVersion) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "AutoPhoneZ-Updater");
            int code = c.getResponseCode();
            if (code == 404) {
                throw new Exception("仓库或 Release 不存在（HTTP 404）");
            }
            if (code >= 400) {
                throw new Exception("GitHub 返回 HTTP " + code);
            }
            JSONObject rel = new JSONObject(readAll(c.getInputStream()));
            String tag = rel.optString("tag_name", "").trim();
            if (tag.isEmpty()) {
                throw new Exception("Release 缺少 tag_name");
            }
            String ver = (tag.startsWith("v") || tag.startsWith("V")) ? tag.substring(1) : tag;

            String apkUrl = null;
            long size = 0;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.optString("name", "");
                    if (name.toLowerCase().endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", "");
                        size = a.optLong("size", 0);
                        break;
                    }
                }
            }
            if (apkUrl == null || apkUrl.isEmpty()) {
                throw new Exception("最新 Release 里没有 .apk 资产");
            }
            if (compareVersion(ver, currentVersion) <= 0) {
                return null;
            }
            return new String[]{ver, apkUrl, String.valueOf(size)};
        } finally {
            c.disconnect();
        }
    }

    /** 下载 APK 到应用缓存目录，返回本地文件。 */
    public static File download(Context ctx, String apkUrl, long expectSize, Progress p)
            throws Exception {
        File dir = new File(ctx.getCacheDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建缓存目录");
        }
        File out = new File(dir, "update.apk");
        if (out.exists() && !out.delete()) {
            throw new Exception("旧安装包无法覆盖（可能被占用）");
        }

        HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "AutoPhoneZ-Updater");
            int code = c.getResponseCode();
            if (code >= 400) {
                throw new Exception("下载失败 HTTP " + code);
            }
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream();
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                long got = 0;
                long lastMile = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    got += n;
                    if (p != null && got - lastMile >= 200 * 1024) {
                        lastMile = got;
                        p.onStatus("下载中 " + (total > 0
                                ? (got * 100 / total) + "%"
                                : (got / 1024) + " KB"));
                    }
                }
            }
        } finally {
            c.disconnect();
        }

        if (expectSize > 0 && out.length() != expectSize) {
            throw new Exception("安装包大小不符（" + out.length() + "/" + expectSize + "），可能下载不完整");
        }
        return out;
    }

    /** 用 PackageInstaller 安装，系统会弹安装确认页。 */
    public static void install(Context ctx, File apk) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ctx.getPackageManager().canRequestPackageInstalls()) {
            throw new Exception("请先授予本应用「安装未知应用」权限");
        }

        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());

        int sessionId = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(sessionId);
        try {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream os = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                session.fsync(os);
            }
            Intent target = new Intent(InstallReceiver.ACTION);
            target.setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            session.commit(PendingIntent
                    .getBroadcast(ctx, sessionId, target, flags)
                    .getIntentSender());
        } finally {
            session.close();
        }
    }

    // ------------------------------------------------------------ 工具

    private static String readAll(InputStream in) throws IOException {
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

    /** 语义版本比较：a>b 返回 1，a<b 返回 -1，相等 0。 */
    static int compareVersion(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? leadingInt(x[i]) : 0;
            int yi = i < y.length ? leadingInt(y[i]) : 0;
            if (xi != yi) {
                return xi > yi ? 1 : -1;
            }
        }
        return 0;
    }

    private static int leadingInt(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            } else {
                break;
            }
        }
        return sb.length() == 0 ? 0 : Integer.parseInt(sb.toString());
    }
}
