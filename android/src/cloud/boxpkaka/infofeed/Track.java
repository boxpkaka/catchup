package cloud.boxpkaka.infofeed;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 行为埋点：信息流里显示过哪些事件、打开详情从哪进来、停留多久、读到多深。先攒在本地，
 * 离开页面时批量报给服务器（POST /v1/track），服务器据此加强或减弱记忆。
 * 和 Trace（排查连接问题的调试日志，传到 hub）分开：这里只记和内容有关的行为。
 */
final class Track {
    private static final String KEY = "track";
    private static final int KEEP = 500;   // 一直连不上时最多攒这么多条，旧的丢掉
    private static final int BATCH = 200;  // 服务器一次最多收这么多
    private static final AtomicBoolean sending = new AtomicBoolean();
    private static final Set<Long> seenToday = new HashSet<>();
    private static String seenDay = "";

    private Track() {}

    /** 在列表里显示过；同一件事一天只记一次。 */
    static synchronized void seen(Context c, long eventId, String from) {
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        if (!day.equals(seenDay)) {
            seenDay = day;
            seenToday.clear();
        }
        if (eventId <= 0 || !seenToday.add(eventId)) return;
        add(c, eventId, "seen", from, null);
    }

    /** 离开详情页时记一次：从哪进来、看了几秒、读到页面的百分之几。 */
    static void read(Context c, long eventId, String from, int secs, int depth) {
        if (eventId <= 0) return;
        try {
            add(c, eventId, "read", from, new JSONObject().put("secs", secs).put("depth", depth));
        } catch (Exception ignored) {
            // put 只在键为空时抛异常
        }
    }

    private static synchronized void add(Context c, long eventId, String kind, String from, JSONObject extra) {
        try {
            JSONObject item = extra != null ? extra : new JSONObject();
            item.put("event", eventId).put("kind", kind).put("from", from == null ? "" : from).put("at", now());
            JSONArray q = queue(c);
            q.put(item);
            while (q.length() > KEEP) q.remove(0);
            save(c, q);
        } catch (Exception e) {
            Trace.log(c, "track.add.fail", e.toString()); // 埋点失败不影响主流程
        }
    }

    /** 后台把攒下的报给服务器；成功后从本地删掉已报的那些，失败的留到下次。只读设备会被拒绝（403），直接丢掉。 */
    static void flush(Context c) {
        Context app = c.getApplicationContext();
        if (!sending.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                JSONArray batch = new JSONArray();
                synchronized (Track.class) {
                    JSONArray q = queue(app);
                    for (int i = 0; i < Math.min(q.length(), BATCH); i++) batch.put(q.get(i));
                }
                if (batch.length() == 0) return;
                boolean drop = false;
                try {
                    Api.post(app, "/v1/track", new JSONObject().put("items", batch));
                    drop = true;
                } catch (Api.Failure f) {
                    drop = f.code >= 400 && f.code < 500;
                    Trace.log(app, "track.fail", "code=" + f.code);
                }
                if (drop) {
                    synchronized (Track.class) {
                        JSONArray q = queue(app);
                        for (int i = 0; i < batch.length() && q.length() > 0; i++) q.remove(0);
                        save(app, q);
                    }
                }
            } catch (Exception e) {
                Trace.log(app, "track.fail", e.toString());
            } finally {
                sending.set(false);
            }
        }, "track").start();
    }

    private static JSONArray queue(Context c) {
        try {
            return new JSONArray(Store.prefs(c).getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void save(Context c, JSONArray q) {
        SharedPreferences.Editor e = Store.prefs(c).edit();
        e.putString(KEY, q.toString()).apply();
    }

    private static String now() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }
}
