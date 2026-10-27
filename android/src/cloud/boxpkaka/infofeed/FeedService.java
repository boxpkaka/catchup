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
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 前台服务：与服务器保持 SSE 长连接，收到消息就存下来并弹通知。 */
public class FeedService extends Service {
    static final String ACTION_UPDATE = "cloud.boxpkaka.infofeed.UPDATE";   // 连接状态变化
    static final String ACTION_PUSHED = "cloud.boxpkaka.infofeed.PUSHED";   // 收到新推送，首页应刷新事件流
    static final String CHANNEL_MESSAGES = "messages";
    static volatile String status = "未启动";
    static volatile boolean active; // 连接线程在跑；进程被系统杀掉后重新为 false，见 Watchdog

    private static final int FG_ID = 1;
    private static final String EVENT_LINK = "catchup://event/";
    private static final String THREAD_LINK = "catchup://thread/";
    // App 被系统关掉期间漏掉的推送，重连补到时这个时间内的照样弹通知；更早的只在首页标出
    private static final long NOTIFY_MAX_AGE_MS = 12 * 3600_000;

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
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_MESSAGES, "新消息", NotificationManager.IMPORTANCE_HIGH));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = statusNotification(status);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(FG_ID, n);
        }
        Watchdog.schedule(this);
        if (worker == null || !worker.isAlive()) { // 已在运行时什么都不做，不要打断现有连接
            Trace.log(this, "service.start", "v" + Updater.currentVersion(this) + " sdk=" + Build.VERSION.SDK_INT
                    + " model=" + Build.MODEL + " net=" + Trace.network(this) + " notify=" + notifyState()
                    + " battery=" + (getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())
                    ? "unrestricted" : "optimized"));
            running = true;
            active = true;
            worker = new Thread(this::loop, "feed-stream");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Trace.log(this, "service.stop", "");
        running = false;
        active = false;
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
                        Store.setLastId(this, m.optLong("id"));
                        notifyIfRecent(m, false);
                        sendBroadcast(new Intent(ACTION_PUSHED).setPackage(getPackageName()));
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
            for (JSONObject m : ms) notifyIfRecent(m, true);
            Store.setLastId(this, ms.get(ms.size() - 1).optLong("id"));
            Trace.log(this, "catchup", "n=" + ms.size() + " last=" + Store.lastId(this));
            sendBroadcast(new Intent(ACTION_PUSHED).setPackage(getPackageName()));
        } finally {
            c.disconnect();
        }
    }

    /** caughtUp：重连时补到的消息。正开着首页时不弹，首页会把离开期间推送过的事件标成新的。 */
    private void notifyIfRecent(JSONObject m, boolean caughtUp) throws Exception {
        long when = (long) (m.optDouble("time", 0) * 1000);
        if (System.currentTimeMillis() - when < NOTIFY_MAX_AGE_MS && !(caughtUp && MainActivity.visible)) {
            String body = m.optString("message");
            String click = m.optString("click");
            Intent open;
            if (click.startsWith(EVENT_LINK)) { // catchup 推送：点开直接进事件详情
                open = new Intent(this, EventActivity.class)
                        .putExtra(EventActivity.EXTRA_ID, Long.parseLong(click.substring(EVENT_LINK.length())));
            } else if (click.startsWith(THREAD_LINK)) { // 你问过的问题有答案了：回到原来的对话
                open = new Intent(this, ChatActivity.class)
                        .putExtra(ChatActivity.EXTRA_THREAD, Long.parseLong(click.substring(THREAD_LINK.length())));
            } else if (click.isEmpty() || "null".equals(click) || click.startsWith("catchup://")) {
                open = new Intent(this, MainActivity.class);
            } else {
                open = new Intent(Intent.ACTION_VIEW, Uri.parse(click));
            }
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int id = (int) (m.getLong("id") % 100000) + 1000;
            PendingIntent pi = PendingIntent.getActivity(this, id, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Trace.log(this, "notify", "id=" + m.optLong("id") + " " + notifyState());
            nm.notify(id, new Notification.Builder(this, CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setColor(0xFF3443F5)
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

    /** 通知是否会被系统直接丢掉：整个 App 的通知开关和“新消息”分类的重要程度（0 为已关闭），写进埋点便于排查。 */
    private String notifyState() {
        NotificationChannel ch = nm.getNotificationChannel(CHANNEL_MESSAGES);
        return "enabled=" + nm.areNotificationsEnabled() + " importance=" + (ch == null ? "-" : ch.getImportance());
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
