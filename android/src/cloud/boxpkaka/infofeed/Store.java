package cloud.boxpkaka.infofeed;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 本地保存最近的消息，以及断线续传用的 lastId。 */
final class Store {
    static final String DEFAULT_SERVER = "https://boxpkaka.cloud/feed";
    private static final int KEEP = 200;
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\((https?://[^)\\s]+)\\)");

    private static JSONArray cache; // 服务和界面在同一进程，共用内存副本，避免每次刷新都读文件

    private Store() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("feed", Context.MODE_PRIVATE);
    }

    static long lastId(Context c) {
        return prefs(c).getLong("lastId", 0);
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "messages.json");
    }

    /** 返回的数组视为只读快照：add() 总是生成新数组再替换，界面线程读到的旧快照不会被改动。 */
    static synchronized JSONArray load(Context c) {
        if (cache == null) {
            try {
                cache = new JSONArray(new String(Files.readAllBytes(file(c).toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                cache = new JSONArray();
            }
        }
        return cache;
    }

    static synchronized void add(Context c, JSONObject m) throws Exception {
        JSONArray all = load(c);
        JSONArray kept = new JSONArray();
        for (int i = Math.max(0, all.length() + 1 - KEEP); i < all.length(); i++) kept.put(all.get(i));
        kept.put(m);
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            out.write(kept.toString().getBytes(StandardCharsets.UTF_8));
        }
        cache = kept;
        prefs(c).edit().putLong("lastId", m.getLong("id")).apply();
    }

    /** 把推送里的 Markdown 转成适合通知和列表显示的纯文本。 */
    static String plain(String md) {
        return LINK.matcher(md.replace("**", "")).replaceAll("$1").replace("  \n", "\n");
    }

    /** 提取消息里的链接：[标题, URL] 对。 */
    static List<String[]> links(JSONObject m) {
        List<String[]> out = new ArrayList<>();
        Matcher x = LINK.matcher(m.optString("message").replace("**", ""));
        while (x.find()) out.add(new String[]{x.group(1), x.group(2)});
        String click = m.optString("click");
        if (out.isEmpty() && !click.isEmpty() && !"null".equals(click)) out.add(new String[]{click, click});
        return out;
    }
}
