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
    private static final Pattern LINK = Pattern.compile("\\[((?:[^\\[\\]]|\\[[^\\]]*\\])*)\\]\\((https?://[^)\\s]+)\\)"); // 标题里允许一层方括号，如 [[开源] xx](url)

    private static JSONArray cache; // 服务和界面在同一进程，共用内存副本，避免每次刷新都读文件

    private Store() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("feed", Context.MODE_PRIVATE);
    }

    /** 服务器地址固定，不再读旧版设置里保存的 server：旧值填错时用户已无处修改。 */
    static String server(Context c) {
        return DEFAULT_SERVER;
    }

    /** 上次离开界面时看到的最新消息 id，比它新的消息在列表里标未读点。 */
    static long seenId(Context c) {
        return prefs(c).getLong("seenId", -1);
    }

    static void markSeen(Context c, long id) {
        prefs(c).edit().putLong("seenId", id).apply();
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

    /**
     * 列表展示用的拆分。推送正文由一条或多条 "**[标题](链接)**  \n来源 · 摘要" 组成，空行分隔；
     * 消息标题是 "话题 · N 条新内容"。不符合这个格式的消息原样显示。
     */
    static final class Entry {
        String topic = "", headline = "", source = "", snippet = "";
        int count = 1;
    }

    static Entry entry(JSONObject m) {
        Entry v = new Entry();
        String title = m.optString("title");
        String message = m.optString("message").replace("**", "");
        int sep = title.indexOf(" · ");
        Matcher link = LINK.matcher(message);
        if (sep < 0 || !link.find()) {
            v.headline = title;
            v.snippet = plain(message).trim();
            return v;
        }
        v.topic = title.substring(0, sep);
        v.headline = link.group(1);
        String rest = message.substring(link.end()).split("\n\n", 2)[0].replace("  \n", "\n").trim();
        int dot = rest.indexOf(" · ");
        v.source = dot > 0 ? rest.substring(0, dot) : "";
        v.snippet = dot > 0 ? rest.substring(dot + 3) : rest;
        v.count = Math.max(1, message.split("\n\n").length);
        return v;
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
