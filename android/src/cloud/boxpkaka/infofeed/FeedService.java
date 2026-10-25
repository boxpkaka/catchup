package cloud.boxpkaka.infofeed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 前台服务：与服务器保持 SSE 长连接，收到消息就存下来并弹通知。 */
public class FeedService extends Service {
    static final String ACTION_UPDATE = "cloud.boxpkaka.infofeed.UPDATE";
    static volatile String status = "未启动";

    private static final int FG_ID = 1;
    private static final long NOTIFY_MAX_AGE_MS = 3600_000; // 补发的旧消息只入列表，不弹通知

    private volatile boolean running;
    private volatile HttpURLConnection conn;
    private Thread worker;
    private NotificationManager nm;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, FeedService.class));
    }

    @Override
    public void onCreate() {
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("status", "连接状态", NotificationManager.IMPORTANCE_MIN));
        nm.createNotificationChannel(new NotificationChannel("messages", "新消息", NotificationManager.IMPORTANCE_HIGH));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = statusNotification(status);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(FG_ID, n);
        }
        if (worker == null || !worker.isAlive()) { // 已在运行时什么都不做，不要打断现有连接
            Trace.log(this, "service.start", "v" + Updater.currentVersion(this) + " sdk=" + Build.VERSION.SDK_INT
                    + " model=" + Build.MODEL + " net=" + Trace.network(this));
            running = true;
            worker = new Thread(this::loop, "feed-stream");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Trace.log(this, "service.stop", "");
        running = false;
        reconnectNow();
        setStatus("已停止");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void reconnectNow() {
        if (worker != null) worker.interrupt();
        HttpURLConnection c = conn;
        if (c != null) c.disconnect();
    }

    private void loop() {
        long backoff = 2000;
        while (running) {
            String server = Store.server(this);
            long opened = 0, lastUpload = 0;
            int events = 0;
            try {
                setStatus("连接中…");
                Trace.log(this, "connect", "since=" + Store.lastId(this) + " net=" + Trace.network(this));
                catchUp(server);
                Trace.upload(this, server);
                lastUpload = System.currentTimeMillis();
                HttpURLConnection c = (HttpURLConnection) new URL(server + "/stream?since=" + Store.lastId(this)).openConnection();
                conn = c;
                c.setConnectTimeout(15000);
                c.setReadTimeout(70000); // 服务端每 25 秒发一次心跳
                c.setRequestProperty("Accept", "text/event-stream");
                long t0 = System.currentTimeMillis();
                int code = c.getResponseCode();
                Trace.log(this, "stream.open", "code=" + code + " ms=" + (System.currentTimeMillis() - t0));
                if (code != 200) throw new IOException("HTTP " + code);
                opened = System.currentTimeMillis();
                setStatus("已连接");
                backoff = 2000;
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder data = new StringBuilder();
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.startsWith(":") && System.currentTimeMillis() - lastUpload > 60_000) {
                        Trace.upload(this, server); // 借心跳顺带上传埋点，最多每分钟一次
                        lastUpload = System.currentTimeMillis();
                    }
                    if (line.startsWith("data:")) {
                        data.append(line.substring(5).trim());
                    } else if (line.isEmpty() && data.length() > 0) {
                        JSONObject m = new JSONObject(data.toString());
                        Store.addAll(this, Collections.singletonList(m));
                        notifyIfRecent(m);
                        sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName()));
                        Trace.log(this, "stream.message", "id=" + m.optLong("id"));
                        events++;
                        data.setLength(0);
                    }
                }
                throw new IOException("连接被关闭");
            } catch (Exception e) {
                Trace.log(this, "stream.end", "after=" + (opened == 0 ? "-" : (System.currentTimeMillis() - opened) / 1000 + "s")
                        + " events=" + events + " error=" + e.getClass().getName() + ": " + e.getMessage()
                        + " net=" + Trace.network(this) + " dns=" + Trace.resolve(server) + " running=" + running);
                if (!running) break;
                if (Thread.interrupted()) continue;
                setStatus("连接断开，" + backoff / 1000 + " 秒后重试（" + reason(e) + "）");
                pause(backoff);
                backoff = Math.min(backoff * 2, 60000);
            } finally {
                HttpURLConnection c = conn;
                if (c != null) c.disconnect();
            }
        }
    }

    /** 把常见网络异常翻译成用户能看懂的原因。 */
    private static String reason(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "无法解析服务器地址，请检查网络或 App 联网权限";
        if (e instanceof java.net.SocketTimeoutException) return "连接超时";
        if (e instanceof java.net.ConnectException) return "无法访问服务器";
        if (e instanceof javax.net.ssl.SSLException) return "安全连接失败";
        return String.valueOf(e.getMessage());
    }

    private void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * 先用普通请求补齐离线期间的消息再连长连接。它一次返回、带长度，
     * 部分手机网络的代理会掐断长连接，至少补发不受影响，每次重连都能把缺的消息拿全。
     */
    private void catchUp(String server) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server + "/messages?since=" + Store.lastId(this)).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            JSONArray rows = new JSONArray(Updater.read(c.getInputStream()));
            List<JSONObject> ms = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) ms.add(rows.getJSONObject(i));
            if (ms.isEmpty()) return;
            Store.addAll(this, ms);
            for (JSONObject m : ms) notifyIfRecent(m);
            Trace.log(this, "catchup", "n=" + ms.size() + " last=" + Store.lastId(this));
            sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName()));
        } finally {
            c.disconnect();
        }
    }

    private void notifyIfRecent(JSONObject m) throws Exception {
        long when = (long) (m.optDouble("time", 0) * 1000);
        if (System.currentTimeMillis() - when < NOTIFY_MAX_AGE_MS) {
            Store.Entry e = Store.entry(m);
            String body = e.snippet.isEmpty() ? e.headline : e.headline + "\n" + e.snippet;
            String click = m.optString("click");
            Intent open = click.isEmpty() || "null".equals(click)
                    ? new Intent(this, MainActivity.class)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(click));
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int id = (int) (m.getLong("id") % 100000) + 1000;
            PendingIntent pi = PendingIntent.getActivity(this, id, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(id, new Notification.Builder(this, "messages")
                    .setSmallIcon(R.drawable.ic_notify)
                    .setColor(0xFF1A1A1D)
                    .setContentTitle(m.optString("title"))
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setWhen(when)
                    .setShowWhen(true)
                    .build());
        }
    }

    private void setStatus(String s) {
        Trace.log(this, "status", s);
        status = s;
        if (running) nm.notify(FG_ID, statusNotification(s));
        sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName()));
    }

    private Notification statusNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "status")
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Catchup")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }
}
