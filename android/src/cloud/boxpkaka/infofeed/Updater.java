package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 应用内更新：向自建服务器（与推送同一地址）查询最新版本，下载 APK 后交给系统 PackageInstaller 安装。
 * 不直接访问 GitHub，手机在国内网络下连 GitHub 不稳定；服务器上的版本由 android/release.sh 发布。
 */
final class Updater {
    static final String ACTION_INSTALLED = "cloud.boxpkaka.infofeed.INSTALL_RESULT";
    private static final long CHECK_INTERVAL_MS = 6 * 3600_000L;

    static final class Release {
        final String version;
        final String apkUrl;

        Release(String version, String apkUrl) {
            this.version = version;
            this.apkUrl = apkUrl;
        }
    }

    private Updater() {}

    /** 距离上次自动检查是否已超过间隔，避免每次打开 App 都请求 GitHub。 */
    static boolean due(Context c) {
        return System.currentTimeMillis() - Store.prefs(c).getLong("updateChecked", 0) > CHECK_INTERVAL_MS;
    }

    static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** 网络请求，需在后台线程调用。有更新返回 Release，没有返回 null。 */
    static Release check(Context c) throws Exception {
        String server = Store.server(c);
        HttpURLConnection conn = open(server + "/app/version");
        try {
            int code = conn.getResponseCode();
            if (code == 404) return null; // 服务器上还没有发布过版本
            if (code != 200) throw new IOException("HTTP " + code);
            String version = new JSONObject(read(conn.getInputStream())).getString("version");
            Store.prefs(c).edit().putLong("updateChecked", System.currentTimeMillis()).apply();
            return newer(version, currentVersion(c)) ? new Release(version, server + "/app.apk") : null;
        } finally {
            conn.disconnect();
        }
    }

    /** 按数字逐段比较版本号，如 1.10.0 比 1.9.2 新；非数字部分视为 0。 */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    /**
     * 下载 APK 写入安装会话并提交，需在后台线程调用。
     * 系统需要用户确认时，会以 ACTION_INSTALLED 回调 MainActivity，由它弹出确认界面。
     */
    static void install(Activity a, Release r) throws Exception {
        PackageInstaller installer = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(a.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            HttpURLConnection conn = open(r.apkUrl);
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream();
                 OutputStream out = session.openWrite("base.apk", 0, conn.getContentLengthLong())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            } finally {
                conn.disconnect();
            }
            Intent result = new Intent(a, MainActivity.class).setAction(ACTION_INSTALLED)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getActivity(a, 0, result, flags).getIntentSender());
        } catch (Exception e) {
            installer.abandonSession(id);
            throw e;
        }
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        return c;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
