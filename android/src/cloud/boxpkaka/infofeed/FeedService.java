package cloud.boxpkaka.infofeed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

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
        if (Store.prefs(c).getString("token", "").isEmpty()) return;
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
        if (worker == null || !worker.isAlive()) {
            running = true;
            worker = new Thread(this::loop, "feed-stream");
            worker.start();
        } else {
            reconnectNow(); // 设置改了，用新配置立刻重连
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
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
            SharedPreferences p = Store.prefs(this);
            String server = p.getString("server", Store.DEFAULT_SERVER).replaceAll("/+$", "");
            try {
                setStatus("连接中…");
                HttpURLConnection c = (HttpURLConnection) new URL(server + "/stream?since=" + Store.lastId(this)).openConnection();
                conn = c;
                c.setConnectTimeout(15000);
                c.setReadTimeout(70000); // 服务端每 25 秒发一次心跳
                c.setRequestProperty("Authorization", "Bearer " + p.getString("token", ""));
                c.setRequestProperty("Accept", "text/event-stream");
                int code = c.getResponseCode();
                if (code == 401) {
                    setStatus("令牌错误，请在设置里检查");
                    pause(60000);
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                setStatus("已连接");
                backoff = 2000;
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder data = new StringBuilder();
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        data.append(line.substring(5).trim());
                    } else if (line.isEmpty() && data.length() > 0) {
                        onMessage(new JSONObject(data.toString()));
                        data.setLength(0);
                    }
                }
                throw new IOException("连接被关闭");
            } catch (Exception e) {
                if (!running) break;
                if (Thread.interrupted()) continue;
                setStatus("已断开，" + backoff / 1000 + " 秒后重连（" + e.getMessage() + "）");
                pause(backoff);
                backoff = Math.min(backoff * 2, 60000);
            } finally {
                HttpURLConnection c = conn;
                if (c != null) c.disconnect();
            }
        }
    }

    private void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void onMessage(JSONObject m) throws Exception {
        Store.add(this, m);
        long when = (long) (m.optDouble("time", 0) * 1000);
        if (System.currentTimeMillis() - when < NOTIFY_MAX_AGE_MS) {
            String body = Store.plain(m.optString("message"));
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
                    .setContentTitle(m.optString("title"))
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setWhen(when)
                    .setShowWhen(true)
                    .build());
        }
        sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName()));
    }

    private void setStatus(String s) {
        status = s;
        if (running) nm.notify(FG_ID, statusNotification(s));
        sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName()));
    }

    private Notification statusNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "status")
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("信息流")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }
}
