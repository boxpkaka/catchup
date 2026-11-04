package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

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

    static final class Release {
        final String version;
        final String apkUrl;

        Release(String version, String apkUrl) {
            this.version = version;
            this.apkUrl = apkUrl;
        }
    }

    private Updater() {}

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
     * 系统需要用户确认时，会以 ACTION_INSTALLED 回调 MainActivity，由 Prompt 弹出确认界面。
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

    static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    /**
     * 首页的更新提示条：找到新版本就显示“已就绪”，点它下载、交给系统安装，失败了说明原因、可以重试。
     * 提示条的样子由首页摆好（notice 里放着 text 和 action），这里只管它什么时候出现、写什么。
     */
    static final class Prompt {
        private final Activity a;
        private final View notice;
        private final TextView text, action;
        private Release release;

        Prompt(Activity a, View notice, TextView text, TextView action) {
            this.a = a;
            this.notice = notice;
            this.text = text;
            this.action = action;
            notice.setOnClickListener(v -> install());
        }

        /**
         * 后台检查新版本，打开 App 和下拉刷新时自动查；manual 为 true 时把“已是最新”或失败原因也提示出来。
         * 已经找到新版本就不再自动查，免得下载、安装确认时回到首页又把提示条重置成“已就绪”。
         */
        void check(boolean manual) {
            if (!manual && release != null) return;
            new Thread(() -> {
                String msg;
                Release r = null;
                try {
                    r = Updater.check(a);
                    msg = r == null ? "已是最新版本" : null;
                } catch (Exception e) {
                    msg = "检查更新失败：" + e.getMessage();
                }
                Release found = r;
                String toastText = msg;
                a.runOnUiThread(() -> {
                    if (a.isDestroyed()) return;
                    if (found != null) {
                        release = found;
                        show("新版本 " + found.version + " 已就绪", true);
                    } else if (manual) {
                        toast(toastText);
                    }
                });
            }, "update-check").start();
        }

        private void install() {
            Release r = release;
            if (r == null || action.getVisibility() != View.VISIBLE) return;
            show("正在下载 " + r.version + "…", false);
            new Thread(() -> {
                try {
                    Updater.install(a, r);
                } catch (Exception e) {
                    a.runOnUiThread(() -> {
                        toast("下载更新失败：" + e.getMessage());
                        show("新版本 " + r.version + " 下载失败", true);
                    });
                }
            }, "update-install").start();
        }

        private void show(String s, boolean actionable) {
            text.setText(s);
            action.setText(actionable && s.contains("失败") ? "重试" : "更新");
            action.setVisibility(actionable ? View.VISIBLE : View.GONE);
            notice.setVisibility(View.VISIBLE);
        }

        /** PackageInstaller 的回调：需要用户确认时弹出系统安装界面，失败时提示原因。 */
        void onInstallResult(Intent intent) {
            if (intent == null || !ACTION_INSTALLED.equals(intent.getAction())) return;
            int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) a.startActivity(confirm);
            } else if (code != PackageInstaller.STATUS_SUCCESS) {
                toast("安装失败：" + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                if (release != null) show("新版本 " + release.version + " 安装失败", true);
            }
        }

        private void toast(String s) {
            Toast.makeText(a, s, Toast.LENGTH_LONG).show();
        }
    }
}
