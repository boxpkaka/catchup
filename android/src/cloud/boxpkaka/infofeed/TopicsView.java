package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.graphics.Color;
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
 * 首页的“专题”页：话题专题（配置里的兴趣：AI 语音、大模型、Agent）、论文专题（这一周和你有关的论文）和榜单专题
 * （GitHub Trending、HF 模型热榜、Product Hunt）各一张卡片。信息流只放够重要的，专题放一个方向、一个榜单的全部；
 * 点卡片进 TopicActivity。
 * 专题是固定的几个入口，每个专题有自己的颜色（Theme.zone），帮你认路：卡片是这个颜色的雾面金属（Flow，和专题页标题栏、
 * 信息流里没有封面的“最值得看”卡片同一种），不放徽记；一屏七张，流动各自错开、慢一半。底下是首页的珍珠银雾。
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
        List<JSONObject> topics = new ArrayList<>(), papers = new ArrayList<>(), boards = new ArrayList<>();
        JSONArray all = r.optJSONArray("topics");
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject x = all.optJSONObject(i);
            String kind = x.optString("kind");
            ("board".equals(kind) ? boards : "papers".equals(kind) ? papers : topics).add(x);
        }
        group("话题", topics);
        group("论文", papers);
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

    /**
     * 专题卡片：底下是这个专题的雾面金属，左上大数字，下面是名字、最新的一两条（论文是最值得读的）和一行小字。
     * 整行的卡片多露一条。
     */
    private View tile(JSONObject x, boolean wide) {
        String key = x.optString("key");
        int[] fc = t.field(key), z = {fc[0], fc[2], fc[3]}; // 底色、文字色、点缀色
        FrameLayout frame = new FrameLayout(a);
        t.press(frame, fc[0], Theme.R_XL);
        frame.setOnClickListener(v -> onOpen.open(v, x));
        Flow flow = new Flow(a);
        flow.gradient(fc[0], fc[1], Color.luminance(fc[2]) > 0.5f, true, Flow.seedOf(key));
        flow.topic(t, key, Flow.seedOf(key));
        flow.slow(2);
        flow.corners(frame);
        frame.addView(flow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout tile = new LinearLayout(a);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(t.dp(18), t.dp(16), t.dp(18), t.dp(16));
        frame.addView(tile, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(a);
        boolean topic = "topic".equals(x.optString("kind")), paper = "papers".equals(x.optString("kind"));
        LinearLayout number = new LinearLayout(a);
        number.setOrientation(LinearLayout.VERTICAL);
        TextView big = t.text(a, String.valueOf(x.optInt(topic ? "today" : "count")), wide ? 40 : 34, z[1], 900);
        big.setTypeface(Theme.flex(a, wide ? 140 : 125, 900)); // 半张卡片放得下三位数
        big.setSingleLine();
        number.addView(big);
        TextView unit = t.text(a, topic ? "今天" : paper ? "篇值得读" : unit(key), 11.5f, z[1], 700);
        unit.setAlpha(0.78f);
        unit.setPadding(0, t.dp(6), 0, 0);
        number.addView(unit);
        top.addView(number);
        tile.addView(top);

        TextView name = t.text(a, x.optString("title"), wide ? 22 : 19, z[1], 900);
        name.setLineSpacing(t.dp(2), 1f);
        name.setPadding(0, t.dp(10), 0, t.dp(6));
        tile.addView(name);
        if (topic || paper) {
            JSONArray latest = x.optJSONArray("latest");
            for (int i = 0; latest != null && i < Math.min(latest.length(), wide ? 2 : 1); i++) {
                JSONObject e = latest.optJSONObject(i);
                String lead = paper ? "" : MainActivity.clock(Theme.millis(e.optString("time")));
                tile.addView(line(lead, e.optString("headline"), z));
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
        return frame;
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
        if ("papers".equals(x.optString("kind"))) return "近 " + x.optInt("days") + " 天 " + x.optInt("total") + " 篇 · " + updated;
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
