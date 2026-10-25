package cloud.boxpkaka.infofeed;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * 埋点日志：关键事件追加到本地文件，连上服务器时把未上传的部分批量 POST 到 hub 的 /log，
 * 用于远程排查手机上的连接问题。网络不通时先留在本地，下次连上再补传。
 */
final class Trace {
    private static final long KEEP_BYTES = 256 * 1024; // 已上传部分超过这个大小就清掉
    private static final int MAX_UPLOAD = 60 * 1024;   // hub 单次上限 64KB
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA);

    private Trace() {}

    static synchronized void log(Context c, String event, String detail) {
        String line = FMT.format(new Date()) + " " + event
                + (detail == null || detail.isEmpty() ? "" : " " + detail.replace('\n', ' ')) + "\n";
        Log.i("Catchup", line.trim());
        try (FileOutputStream out = new FileOutputStream(file(c), true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 埋点失败不影响主流程
        }
    }

    /** 本机标识：机型 + 首次运行时生成的随机串，只用于在服务器上区分日志文件。 */
    static String device(Context c) {
        SharedPreferences p = Store.prefs(c);
        String id = p.getString("deviceId", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString().substring(0, 8);
            p.edit().putString("deviceId", id).apply();
        }
        return (Build.MODEL + "-" + id).replaceAll("[^A-Za-z0-9_-]", "");
    }

    /** 当前网络：wifi / cellular / vpn 等，逗号分隔；没有网络时为 none。 */
    static String network(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        if (caps == null) return "none";
        StringBuilder s = new StringBuilder();
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s.append("wifi,");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) s.append("cellular,");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) s.append("vpn,");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) s.append("ethernet,");
        return s.length() == 0 ? "other" : s.substring(0, s.length() - 1);
    }

    /** 手机把服务器域名解析成了哪些 IP；代理软件的假 IP（198.18.x）或被劫持的解析会在这里暴露。 */
    static String resolve(String url) {
        try {
            String host = new URL(url).getHost();
            StringBuilder s = new StringBuilder();
            for (java.net.InetAddress a : java.net.InetAddress.getAllByName(host)) s.append(a.getHostAddress()).append(',');
            return s.length() == 0 ? "-" : s.substring(0, s.length() - 1);
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    /** 最近的日志尾部，供“诊断信息”页面显示；网络不通、无法上传时靠它排查。 */
    static synchronized String tail(Context c, int maxBytes) {
        File f = file(c);
        if (!f.exists()) return "（还没有日志）";
        long start = Math.max(0, f.length() - maxBytes);
        byte[] b = new byte[(int) (f.length() - start)];
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            in.seek(start);
            in.readFully(b);
        } catch (IOException e) {
            return "读取日志失败：" + e.getMessage();
        }
        String s = new String(b, StandardCharsets.UTF_8);
        int nl = start > 0 ? s.indexOf('\n') : -1; // 去掉被截断的第一行
        return nl >= 0 ? s.substring(nl + 1) : s;
    }

    /** 上传尚未上传的日志，需在后台线程调用；失败只记一行，下次再传。 */
    static void upload(Context c, String server) {
        byte[] pending;
        long end;
        synchronized (Trace.class) {
            File f = file(c);
            long sent = Store.prefs(c).getLong("traceSent", 0);
            if (sent > f.length()) sent = 0;
            end = Math.min(f.length(), sent + MAX_UPLOAD);
            if (end <= sent) return;
            pending = new byte[(int) (end - sent)];
            try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
                in.seek(sent);
                in.readFully(pending);
            } catch (IOException e) {
                return;
            }
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(server + "/log").openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(pending.length);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            conn.setRequestProperty("X-Device", device(c));
            try (OutputStream out = conn.getOutputStream()) {
                out.write(pending);
            }
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code != 200) throw new IOException("HTTP " + code);
        } catch (Exception e) {
            log(c, "trace.upload.fail", e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        synchronized (Trace.class) {
            Store.prefs(c).edit().putLong("traceSent", end).apply();
            trimIfLarge(c, end);
        }
    }

    /** 已上传部分过大时，只保留尚未上传的尾部。 */
    private static void trimIfLarge(Context c, long sent) {
        File f = file(c);
        if (sent < KEEP_BYTES) return;
        try {
            byte[] rest = new byte[(int) (f.length() - sent)];
            try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
                in.seek(sent);
                in.readFully(rest);
            }
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write(rest);
            }
            Store.prefs(c).edit().putLong("traceSent", 0).apply();
        } catch (IOException ignored) {
            // 下次上传时再试
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "trace.log");
    }
}
