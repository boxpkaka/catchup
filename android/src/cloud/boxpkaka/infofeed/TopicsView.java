package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 首页的“专题”页：话题专题（配置里的兴趣：AI 语音、大模型、Agent）和榜单专题（GitHub Trending、HF 模型热榜、
 * Product Hunt）各一张卡片。信息流只放够重要的，专题放一个方向、一个榜单的全部；点卡片进 TopicActivity。
 * 专题是固定的几个入口，所以和信息流不同，每个专题有自己的颜色和徽记（Theme.zone、emblem），帮你认路。
 * 卡片大小搭配：每组第一张占整行，其余两两一行，落单的占整行。数据见服务器的 /v1/topics。
 */
final class TopicsView extends FrameLayout {
    interface OnOpen {
        void open(View from, JSONObject topic);
    }

    private static final long STALE_MS = 60_000;

    private final Activity a;
    private final Theme t;
    private final OnOpen onOpen;
    private final ScrollView scroll;
    private final LinearLayout body;
    private JSONObject shown;
    private long loadedAt;
    private boolean loading;

    TopicsView(Activity a, Theme t, OnOpen onOpen) {
        super(a);
        this.a = a;
        this.t = t;
        this.onOpen = onOpen;
        setBackgroundColor(t.bg);
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(t.dp(12), 0, t.dp(12), 0);
        scroll = new ScrollView(a);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, 0, 0, t.dp(96));
        scroll.addView(body);
        addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void setBottomInset(int px) {
        scroll.setPadding(0, 0, 0, px);
    }

    /** 专题的徽记：每个专题一种形状和图形，形状不占用已有的含义（太阳是重要性 5、四叶是重要性 4 等）。 */
    static Shapes.Emblem emblem(Context c, Theme t, String key) {
        int[] z = t.zone(key);
        float[] form;
        int glyph;
        switch (key) {
            case "AI 语音":
                form = new float[]{5, 0.14f};
                glyph = Shapes.Emblem.WAVE;
                break;
            case "大模型":
                form = new float[]{7, 0.1f};
                glyph = Shapes.Emblem.SPARK;
                break;
            case "Agent":
                form = new float[]{3, 0.17f};
                glyph = Shapes.Emblem.ARROW;
                break;
            case "github_trending":
                form = new float[]{10, 0.07f};
                glyph = Shapes.Emblem.TREND;
                break;
            case "hf_models":
                form = new float[]{9, 0.12f};
                glyph = Shapes.Emblem.HEART;
                break;
            case "product_hunt":
                form = new float[]{2, 0.09f};
                glyph = Shapes.Emblem.UP;
                break;
            default:
                form = new float[]{11, 0.06f};
                glyph = Shapes.Emblem.SPARK;
        }
        return new Shapes.Emblem(c, form, glyph, z[2], z[0]);
    }

    /** 切到这一页时调用：没加载过或超过一分钟就重新取，旧的内容先留着。 */
    void refreshIfStale() {
        if (loading || System.currentTimeMillis() - loadedAt < STALE_MS) return;
        loading = true;
        if (shown == null) {
            body.removeAllViews();
            Shapes.Loader loader = new Shapes.Loader(a, t.pri);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(t.dp(48), t.dp(48));
            ll.gravity = Gravity.CENTER_HORIZONTAL;
            ll.topMargin = t.dp(120);
            body.addView(loader, ll);
            loader.start();
        }
        Trace.log(a, "ui.topics", shown == null ? "first" : "refresh");
        new Thread(() -> {
            JSONObject r = null;
            Exception err = null;
            try {
                r = Api.get(a, "/v1/topics");
            } catch (Exception e) {
                err = e;
                Trace.log(a, "topics.fail", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            JSONObject got = r;
            Exception error = err;
            a.runOnUiThread(() -> {
                if (a.isDestroyed()) return;
                loading = false;
                if (got != null) {
                    loadedAt = System.currentTimeMillis();
                    render(got);
                } else if (shown == null) {
                    failed(error);
                }
            });
        }, "topics-load").start();
    }

    private void render(JSONObject r) {
        shown = r;
        body.removeAllViews();
        List<JSONObject> topics = new ArrayList<>(), boards = new ArrayList<>();
        JSONArray all = r.optJSONArray("topics");
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject x = all.optJSONObject(i);
            ("board".equals(x.optString("kind")) ? boards : topics).add(x);
        }
        group("话题", topics);
        group("榜单", boards);
    }

    private void group(String label, List<JSONObject> list) {
        if (list.isEmpty()) return;
        TextView v = t.text(a, label, 13, t.sub, 700);
        v.setPadding(t.dp(12), t.dp(body.getChildCount() == 0 ? 8 : 22), 0, t.dp(8));
        body.addView(v);
        addRow(tile(list.get(0), true), null);
        int i = 1;
        for (; i + 1 < list.size(); i += 2) addRow(tile(list.get(i), false), tile(list.get(i + 1), false));
        if (i < list.size()) addRow(tile(list.get(i), true), null);
    }

    /** 一行一张或两张；两张时一样高，底部的小字对齐。 */
    private void addRow(View left, View right) {
        LinearLayout row = new LinearLayout(a);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        if (right != null) {
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            rl.leftMargin = t.dp(8);
            row.addView(right, rl);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = t.dp(8);
        body.addView(row, lp);
    }

    /** 专题卡片：左上徽记，右上大数字，下面是名字、最新的一两条和一行小字。整行的卡片多露一条。 */
    private View tile(JSONObject x, boolean wide) {
        String key = x.optString("key");
        int[] z = t.zone(key);
        LinearLayout tile = new LinearLayout(a);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(t.dp(18), t.dp(16), t.dp(18), t.dp(16));
        t.press(tile, z[0], Theme.R_XL);
        tile.setOnClickListener(v -> onOpen.open(v, x));

        LinearLayout top = new LinearLayout(a);
        int size = t.dp(wide ? 54 : 46);
        top.addView(emblem(a, t, key), new LinearLayout.LayoutParams(size, size));
        top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        boolean topic = "topic".equals(x.optString("kind"));
        LinearLayout number = new LinearLayout(a);
        number.setOrientation(LinearLayout.VERTICAL);
        number.setGravity(Gravity.END);
        TextView big = t.text(a, String.valueOf(x.optInt(topic ? "today" : "count")), wide ? 40 : 34, z[1], 900);
        big.setTypeface(Theme.flex(a, wide ? 140 : 125, 900)); // 半张卡片放得下三位数
        big.setSingleLine();
        number.addView(big);
        TextView unit = t.text(a, topic ? "今天" : unit(key), 11.5f, z[1], 700);
        unit.setAlpha(0.78f);
        unit.setPadding(0, t.dp(6), 0, 0);
        number.addView(unit);
        top.addView(number);
        tile.addView(top);

        TextView name = t.text(a, x.optString("title"), wide ? 22 : 19, z[1], 900);
        name.setLineSpacing(t.dp(2), 1f);
        name.setPadding(0, t.dp(10), 0, t.dp(6));
        tile.addView(name);
        if (topic) {
            JSONArray latest = x.optJSONArray("latest");
            for (int i = 0; latest != null && i < Math.min(latest.length(), wide ? 2 : 1); i++) {
                JSONObject e = latest.optJSONObject(i);
                tile.addView(line(MainActivity.clock(Theme.millis(e.optString("time"))), e.optString("headline"), z));
            }
        } else {
            JSONArray top3 = x.optJSONArray("top");
            int n = top3 == null ? 0 : Math.min(top3.length(), "github_trending".equals(key) ? 3 : 1);
            for (int i = 0; i < n; i++) {
                JSONObject item = top3.optJSONObject(i);
                String rank = item.isNull("rank") ? "" : String.format(Locale.ROOT, "%02d", item.optInt("rank"));
                String today = item.optJSONObject("extra") == null ? "" : stars(item.optJSONObject("extra"));
                tile.addView(n > 1 ? mini(rank, shortName(item.optString("name")), today, z)
                        : line(rank.isEmpty() ? "" : "#" + item.optInt("rank"), shortName(item.optString("name")), z));
            }
        }
        tile.addView(new View(a), new LinearLayout.LayoutParams(1, 0, 1)); // 两张并排时把小字推到底部
        String foot = footer(key, x);
        if (!foot.isEmpty()) {
            TextView f = t.text(a, foot, 12, z[1], 700);
            f.setAlpha(0.82f);
            f.setSingleLine();
            f.setEllipsize(TextUtils.TruncateAt.END);
            f.setPadding(0, t.dp(10), 0, 0);
            tile.addView(f);
        }
        return tile;
    }

    private static String unit(String key) {
        switch (key) {
            case "github_trending":
                return "个仓库";
            case "hf_models":
                return "个模型";
            case "product_hunt":
                return "近 3 天";
            default:
                return "条";
        }
    }

    private static String footer(String key, JSONObject x) {
        if ("topic".equals(x.optString("kind"))) {
            int n = x.optInt("on_boards");
            return n > 0 ? "榜单上还有 " + n + " 个" : "";
        }
        String updated = x.isNull("updated") ? "" : MainActivity.clock(Theme.millis(x.optString("updated"))) + " 更新";
        if ("github_trending".equals(key)) return "和你相关 " + x.optInt("related") + " 个 · " + updated;
        if (x.optInt("channels") > 1) return x.optInt("channels") + " 个分类 · " + updated;
        return updated;
    }

    /** GitHub 今天新增的星数：“+1,068”；别的榜没有时为空。 */
    private static String stars(JSONObject extra) {
        return extra.has("stars_today") ? String.format(Locale.US, "+%,d", extra.optInt("stars_today")) : "";
    }

    static String shortName(String name) {
        return name.substring(name.indexOf('/') + 1);
    }

    /** “14:30 标题”：前面的时间或名次用加宽数字，最多两行。 */
    private TextView line(String lead, String text, int[] z) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        if (!lead.isEmpty()) {
            s.append(lead, new TypefaceSpan(Theme.flex(a, 115, 800)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE).append("  ");
        }
        s.append(text);
        TextView v = t.text(a, s, 13.5f, z[1], 650);
        v.setLineSpacing(t.dp(3), 1f);
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(0, t.dp(4), 0, 0);
        return v;
    }

    /** 榜单卡片里的一名：编号、名字、今天新增的星数。 */
    private View mini(String rank, String name, String today, int[] z) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, t.dp(4), 0, 0);
        TextView r = t.text(a, rank, 14, z[1], 900);
        r.setTypeface(Theme.flex(a, 130, 900));
        r.setAlpha(0.7f);
        row.addView(r, new LinearLayout.LayoutParams(t.dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView n = t.text(a, name, 13.5f, z[1], 700);
        n.setSingleLine();
        n.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView up = t.text(a, today, 12.5f, z[1], 800);
        up.setTypeface(Theme.flex(a, 100, 800));
        up.setPadding(t.dp(6), 0, 0, 0);
        row.addView(up);
        return row;
    }

    private void failed(Exception e) {
        body.removeAllViews();
        TextView v = t.text(a, "加载失败：" + MainActivity.reason(e) + "\n点这里重试", 14.5f, t.sub, 500);
        v.setGravity(Gravity.CENTER);
        v.setPadding(t.dp(20), t.dp(96), t.dp(20), t.dp(48));
        v.setOnClickListener(x -> refreshIfStale());
        body.addView(v);
    }
}
