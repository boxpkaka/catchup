package cloud.boxpkaka.infofeed;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 本地状态：设备令牌、事件流缓存（打开即显示，后台再刷新）、推送续传用的 lastId、上次看过的时间。
 * 推送消息本身不再保存：它只用来弹通知，内容以服务器的事件流为准。
 */
final class Store {
    static final String DEFAULT_SERVER = "https://43.139.27.91:8443"; // IP + 端口直连，见 res/xml/network_security_config.xml

    private Store() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("feed", Context.MODE_PRIVATE);
    }

    /** 服务器地址固定，不读旧版设置里保存的 server。 */
    static String server(Context c) {
        return DEFAULT_SERVER;
    }

    static String token(Context c) {
        return prefs(c).getString("deviceToken", "");
    }

    static void setToken(Context c, String token) {
        prefs(c).edit().putString("deviceToken", token).apply();
    }

    static long lastId(Context c) {
        return prefs(c).getLong("lastId", 0);
    }

    static void setLastId(Context c, long id) {
        if (id > lastId(c)) prefs(c).edit().putLong("lastId", id).apply();
    }

    /** 上次离开首页的时间；比它晚出现的事件算“新的”。0 表示从没看过。 */
    static long seenAt(Context c) {
        return prefs(c).getLong("seenAt", 0);
    }

    static void setSeenAt(Context c, long ms) {
        prefs(c).edit().putLong("seenAt", ms).apply();
    }

    private static File feedFile(Context c) {
        return new File(c.getFilesDir(), "feed.json");
    }

    static synchronized JSONObject loadFeed(Context c) {
        try {
            return new JSONObject(new String(Files.readAllBytes(feedFile(c).toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    static synchronized void saveFeed(Context c, JSONObject feed) {
        try (FileOutputStream out = new FileOutputStream(feedFile(c))) {
            out.write(feed.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Trace.log(c, "feed.cache.fail", e.toString());
        }
        new File(c.getFilesDir(), "messages.json").delete(); // 旧版的推送消息缓存，已不再使用
    }
}
