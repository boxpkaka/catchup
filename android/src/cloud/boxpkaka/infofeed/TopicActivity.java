package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.SparseArray;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一个专题。标题栏是专题卡片的颜色和放大的徽记，从卡片的位置揭开，看起来像卡片长大了。
 * 话题专题按天列这个话题的事件（和信息流的时间线一样），下面是“榜单上的”这个话题的条目；
 * 榜单专题按名次列全部条目，几个榜之间左右滑切换（也可以点标签），可以只看和你相关的。点有事件的条目进事件详情，
 * 没有的弹出简介和“问一问”。右滑返回：榜单专题在第一个榜上再右滑。
 * 数据见服务器的 /v1/topics/<key>。
 */
public class TopicActivity extends Activity {
    static final String EXTRA_KEY = "key";
    static final String EXTRA_TITLE = "title";

    private Theme t;
    private String key;
    private int[] z;
    private LinearLayout head, body;
    private FrameLayout deck; // 榜单专题：几个榜横向排开，放在 body 里
    private final SparseArray<View> channelPages = new SparseArray<>();
    private Swipe.Tabs tabs;
    private Swipe.Pager pager;
    private Push push;
    private ScrollView scroll;
    private HorizontalScrollView tabScroll;
    private TextView sub;
    private JSONObject data;
    private int channel;
    private boolean related;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        t = new Theme(this);
        t.applyWindow(this);
        key = getIntent().getStringExtra(EXTRA_KEY);
        if (key == null) key = "";
        z = t.zone(key);
        setContentView(build(state == null));
        load();
        Trace.log(this, "ui.topic", key);
    }

    @Override
    public void finish() {
        push.finish(super::finish); // 先滑出去再关
    }

    /** fresh（不是转屏之类的重建）时从右边推进来。 */
    private View build(boolean fresh) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);
        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, 0, 0, t.dp(48));

        // 标题栏：专题色铺满，右上角是放大的徽记，露出一半
        FrameLayout bar = new FrameLayout(this);
        bar.setBackground(t.round(z[0], 0, 0, Theme.R_2XL, Theme.R_2XL));
        bar.setMinimumHeight(t.dp(178)); // 徽记整个落在标题栏里，不伸进底部的圆角缺口
        Shapes.Emblem emblem = TopicsView.emblem(this, t, key);
        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(t.dp(124), t.dp(124), Gravity.END | Gravity.TOP);
        el.setMargins(0, t.dp(38), -t.dp(24), 0);
        bar.addView(emblem, el);
        head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(t.dp(16), t.dp(10), t.dp(112), t.dp(22));
        TextView back = t.backButton(this);
        back.setTextColor(z[1]);
        back.setBackground(t.ripple((z[1] & 0x00FFFFFF) | 0x1A000000, 99));
        head.addView(back, new LinearLayout.LayoutParams(t.dp(48), t.dp(48)));
        TextView title = t.text(this, getIntent().getStringExtra(EXTRA_TITLE), 30, z[1], 900);
        title.setLineSpacing(t.dp(2), 1f);
        title.setPadding(t.dp(6), t.dp(18), 0, 0);
        head.addView(title);
        sub = t.text(this, "", 13, z[1], 700);
        sub.setAlpha(0.85f);
        sub.setPadding(t.dp(6), t.dp(8), 0, 0);
        head.addView(sub);
        bar.addView(head);
        page.addView(bar);

        // 榜单专题：几个榜之间切换，右边是“只看相关”
        tabs = new Swipe.Tabs(this, z[2], t.night ? z[0] : Color.WHITE, t.sub);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(t.dp(12), t.dp(14), t.dp(12), t.dp(6));
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabs);
        tabScroll.setVisibility(View.GONE);
        page.addView(tabScroll);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(t.dp(12), t.dp(4), t.dp(12), 0);
        body.setClipChildren(false); // 相邻的榜从屏幕边上进来，不在内容边距处被切掉
        page.addView(body);
        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        loading();

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                head.setPadding(t.dp(16), bars.top + t.dp(10), t.dp(112), t.dp(22));
                el.topMargin = bars.top + t.dp(38);
                emblem.setLayoutParams(el);
                bar.setMinimumHeight(bars.top + t.dp(178));
                page.setPadding(0, 0, 0, bars.bottom + t.dp(48));
                return WindowInsets.CONSUMED;
            });
        }
        push = new Push(this, root, t.bg);
        pager = new Swipe.Pager(this, board, true);
        pager.back(push); // 第一个榜（不是榜单就是整页）再往右滑是返回
        pager.scrollsWith(scroll, () -> tabScroll.getTop());
        pager.frame.addView(root);
        if (fresh) push.enter();
        return pager.frame;
    }

    /** 榜单专题的几个榜：横向排开，左右滑或点标签切换。不是榜单时没有页，只能右滑返回。 */
    private final Swipe.Pager.Host board = new Swipe.Pager.Host() {
        @Override
        public int count() {
            if (data == null || !"board".equals(data.optString("kind"))) return 0;
            JSONArray channels = data.optJSONArray("channels");
            return Math.max(1, channels == null ? 0 : channels.length());
        }

        @Override
        public View page(int index) {
            View v = channelPages.get(index);
            if (v == null) {
                v = channelPage(index);
                channelPages.put(index, v);
                deck.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return v;
        }

        @Override
        public void moved(float position) {
            tabs.at(position);
        }

        @Override
        public void settled(int index) {
            channel = index;
        }
    };

    private void load() {
        new Thread(() -> {
            JSONObject r = null;
            Exception err = null;
            try {
                r = Api.get(this, "/v1/topics/" + URLEncoder.encode(key, StandardCharsets.UTF_8.name()).replace("+", "%20"));
            } catch (Exception e) {
                err = e;
                Trace.log(this, "topic.fail", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            JSONObject got = r;
            Exception error = err;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (got == null) {
                    body.removeAllViews();
                    TextView v = note(body, "加载失败：" + MainActivity.reason(error) + "\n点这里重试");
                    v.setOnClickListener(x -> {
                        loading();
                        load();
                    });
                    return;
                }
                data = got;
                if ("board".equals(got.optString("kind"))) renderBoard();
                else renderTopic();
            });
        }, "topic-load").start();
    }

    private void loading() {
        body.removeAllViews();
        Shapes.Loader loader = new Shapes.Loader(this, z[2]);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(t.dp(48), t.dp(48));
        ll.gravity = Gravity.CENTER_HORIZONTAL;
        ll.topMargin = t.dp(80);
        body.addView(loader, ll);
        loader.start();
    }

    // ---------- 话题专题 ----------

    /** 按天列这个话题的事件，样式和信息流的时间线一样；下面是榜单上的这个话题。 */
    private void renderTopic() {
        body.removeAllViews();
        JSONArray events = data.optJSONArray("events");
        int today = 0, yesterday = 0;
        String day = null;
        LinearLayout list = null;
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            long ms = Theme.millis(e.optString("time"));
            String d = MainActivity.dayLabel(ms);
            if (d.equals("今天")) today++;
            if (d.equals("昨天")) yesterday++;
            if (!d.equals(day)) {
                if (list != null) t.segments(list);
                day = d;
                body.addView(heading(MainActivity.dayTitle(this, t, ms)));
                list = segmentList(body);
            }
            list.addView(eventRow(e));
        }
        if (list != null) t.segments(list);
        sub.setText("今天 " + today + " 件 · 昨天 " + yesterday + " 件");

        JSONArray boards = data.optJSONArray("boards");
        if (boards != null && boards.length() > 0) {
            body.addView(heading("榜单上的"));
            for (int i = 0; i < boards.length(); i++) {
                JSONObject b = boards.optJSONObject(i);
                String label = b.optString("title") + ("hf_models".equals(b.optString("key")) ? " · 各分类前两名" : "");
                body.addView(label(label));
                LinearLayout rows = segmentList(body);
                JSONArray items = b.optJSONArray("items");
                for (int j = 0; items != null && j < items.length(); j++) rows.addView(itemRow(items.optJSONObject(j), true));
                t.segments(rows);
            }
        }
        if (body.getChildCount() == 0) note(body, "这个专题三天里还没有内容");
    }

    /** 和信息流的时间线一样：左边是时间，重要性 4 以上的标题加粗、带一行概要；话题就是这个专题，不再写。 */
    private View eventRow(JSONObject e) {
        boolean important = e.optInt("importance") >= 4;
        LinearLayout row = new LinearLayout(this);
        row.setPadding(t.dp(18), t.dp(14), t.dp(18), t.dp(14));
        TextView time = t.text(this, MainActivity.clock(Theme.millis(e.optString("time"))), 13, t.sub, 800);
        time.setTypeface(Theme.flex(this, 120, 800));
        time.setPadding(0, t.dp(2), 0, 0);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(t.dp(42), ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.rightMargin = t.dp(14);
        row.addView(time, tl);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        if (e.optInt("importance") >= 5) col.addView(tag("要闻", t.inv, t.onInv, Theme.R_XS), tagLp());
        TextView headline = t.text(this, e.optString("headline"), important ? 15.5f : 15, t.ink, important ? 800 : 500);
        headline.setLineSpacing(t.dp(4), 1f);
        headline.setMaxLines(2);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(headline);
        if (important && !e.optString("summary").isEmpty()) {
            TextView summary = t.text(this, e.optString("summary"), 13.5f, t.sub, 400);
            summary.setSingleLine();
            summary.setEllipsize(TextUtils.TruncateAt.END);
            summary.setPadding(0, t.dp(3), 0, 0);
            col.addView(summary);
        }
        col.addView(meta(sources(e)));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> openEvent(e.optLong("id"), e));
        return row;
    }

    /** “IT之家 等 3 家”：和信息流一样。 */
    private static String sources(JSONObject e) {
        JSONArray s = e.optJSONArray("sources");
        String first = s != null && s.length() > 0 ? s.optString(0) : "";
        int n = e.optInt("source_count");
        return n > 1 ? first + " 等 " + n + " 家" : first;
    }

    // ---------- 榜单专题 ----------

    private void renderBoard() {
        JSONArray channels = data.optJSONArray("channels");
        String updated = data.isNull("updated") ? "" : MainActivity.clock(Theme.millis(data.optString("updated"))) + " 更新";
        int n = channels == null ? 0 : channels.length();
        sub.setText(n > 1 ? n + " 个" + ("hf_models".equals(key) ? "分类" : "榜") + " · " + updated : updated);
        tabs.removeAllViews();
        for (int i = 0; i < n && n > 1; i++) {
            int index = i;
            TextView tab = t.text(this, channels.optJSONObject(i).optString("name"), 14, t.sub, 800);
            tab.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(8));
            tab.setBackground(t.ripple(Color.TRANSPARENT, 99));
            tab.setOnClickListener(v -> pager.go(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = t.dp(4);
            tabs.addView(tab, lp);
        }
        TextView only = t.text(this, "只看相关", 13.5f, related ? t.onInv : t.ink, 800);
        only.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(8));
        GradientDrawable outline = t.round(related ? t.inv : Color.TRANSPARENT, 99);
        if (!related) outline.setStroke(t.dp(1.5f), t.line);
        only.setBackground(outline);
        only.setOnClickListener(v -> {
            related = !related;
            renderBoard();
        });
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ol.leftMargin = t.dp(n > 1 ? 8 : 0);
        tabs.addView(only, ol);
        tabs.setCount(n > 1 ? n : 0);
        tabScroll.setVisibility(View.VISIBLE);

        body.removeAllViews();
        deck = new FrameLayout(this);
        body.addView(deck);
        channelPages.clear();
        pager.reset(Math.min(channel, board.count() - 1));
    }

    /** 第 index 个榜的内容，翻到它旁边时才建。 */
    private View channelPage(int index) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        JSONArray channels = data.optJSONArray("channels");
        JSONArray items = channels == null || index >= channels.length() ? null
                : channels.optJSONObject(index).optJSONArray("items");
        LinearLayout rows = segmentList(p);
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject x = items.optJSONObject(i);
            if (!related || !x.optString("topic").isEmpty()) rows.addView(itemRow(x, false));
        }
        if (rows.getChildCount() == 0) {
            p.removeView(rows);
            note(p, related ? "这个榜现在没有和你相关的" : "这个榜还没有数据");
        } else {
            t.segments(rows);
        }
        return p;
    }

    /**
     * 榜单的一条：左边是名次（前三名用专题色），Product Hunt 没有名次写上架日期；“作者 / 名字”，简介，
     * 最后一行是话题和热度（GitHub 今天新增的星数，HF 点赞和下载数）。inTopic 时在话题专题里，写上来自哪个分类。
     */
    private View itemRow(JSONObject x, boolean inTopic) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(t.dp(14), t.dp(14), t.dp(18), t.dp(14));
        boolean ranked = !x.isNull("rank");
        int rank = x.optInt("rank");
        TextView lead;
        if (ranked) {
            lead = t.text(this, String.valueOf(rank), 21, rank <= 3 && !inTopic ? z[2] : t.ink, 800);
            lead.setTypeface(Theme.flex(this, 120, 800));
            lead.setGravity(Gravity.CENTER_HORIZONTAL);
        } else {
            lead = t.text(this, new java.text.SimpleDateFormat("MM-dd", Locale.CHINA)
                    .format(new java.util.Date(Theme.millis(x.optString("time")))), 13, t.sub, 800);
            lead.setTypeface(Theme.flex(this, 120, 800));
            lead.setPadding(t.dp(4), t.dp(2), 0, 0);
        }
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(t.dp(42), ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.rightMargin = t.dp(10);
        row.addView(lead, ll);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        boolean news = x.optInt("importance") >= 5, fresh = x.optBoolean("new");
        if (news || fresh) {
            LinearLayout tagRow = new LinearLayout(this);
            if (news) tagRow.addView(tag("要闻", t.inv, t.onInv, Theme.R_XS), gap(6));
            if (fresh) tagRow.addView(tag("新", t.pri, t.onPri, 99));
            col.addView(tagRow, tagLp());
        }
        String name = x.optString("name");
        int slash = name.indexOf('/');
        SpannableStringBuilder s = new SpannableStringBuilder();
        if (slash > 0) {
            s.append(name.substring(0, slash) + " / ", new ForegroundColorSpan(t.sub), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new TypefaceSpan(Theme.weight(400)), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        s.append(name.substring(slash + 1));
        TextView nm = t.text(this, s, 15.5f, t.ink, 800);
        nm.setLineSpacing(t.dp(3), 1f);
        col.addView(nm);
        String summary = x.optString("summary");
        if (!summary.isEmpty()) {
            TextView ds = t.text(this, summary, 13.5f, t.sub, 400);
            ds.setLineSpacing(t.dp(4), 1f);
            ds.setMaxLines(2);
            ds.setEllipsize(TextUtils.TruncateAt.END);
            ds.setPadding(0, t.dp(4), 0, 0);
            col.addView(ds);
        }
        col.addView(meta(itemMeta(x, inTopic)));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> {
            if (!x.isNull("event_id")) openEvent(x.optLong("event_id"), null);
            else intro(x);
        });
        return row;
    }

    /** “Agent · TypeScript · ★ 5.4k · 今天 +1,068 · 第 3 天”：话题用墨色，今天新增的星数加粗。 */
    private CharSequence itemMeta(JSONObject x, boolean inTopic) {
        List<CharSequence> parts = new ArrayList<>();
        String topic = x.optString("topic");
        if (!topic.isEmpty() && !topic.equals(key)) parts.add(ink(topic, false));
        if (!x.optString("task").isEmpty()) parts.add(x.optString("task"));
        JSONObject extra = x.optJSONObject("extra");
        if (extra == null) extra = new JSONObject();
        if (!extra.optString("language").isEmpty()) parts.add(extra.optString("language"));
        if (extra.has("stars")) parts.add("★ " + count(extra.optLong("stars")));
        if (extra.has("stars_today")) {
            SpannableStringBuilder today = new SpannableStringBuilder("今天 ");
            parts.add(today.append(ink(String.format(Locale.US, "+%,d", extra.optLong("stars_today")), true)));
        }
        if (extra.has("likes")) parts.add("♥ " + count(extra.optLong("likes")));
        if (extra.has("downloads")) parts.add("下载 " + count(extra.optLong("downloads")));
        if (x.optInt("days") >= 2) parts.add("第 " + x.optInt("days") + " 天");
        if (inTopic && "product_hunt".equals(x.optString("board"))) parts.add("Product Hunt");
        SpannableStringBuilder s = new SpannableStringBuilder();
        for (CharSequence p : parts) {
            if (s.length() > 0) s.append(" · ");
            s.append(p);
        }
        return s;
    }

    private CharSequence ink(String s, boolean bold) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        b.append(s, new ForegroundColorSpan(t.ink), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) b.setSpan(new TypefaceSpan(Theme.flex(this, 100, 800)), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    /** 5,367 / 11.4k / 308k / 1.4M */
    private static String count(long n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1e6).replace(".0M", "M");
        if (n >= 100_000) return String.format(Locale.US, "%.0fk", n / 1e3);
        if (n >= 10_000) return String.format(Locale.US, "%.1fk", n / 1e3).replace(".0k", "k");
        return String.format(Locale.US, "%,d", n);
    }

    /** 还没有对应事件的条目（多是早就发布、刚上榜的模型）：弹出简介，可以打开原页面或问一问。 */
    private void intro(JSONObject x) {
        String name = x.optString("name"), shortName = TopicsView.shortName(name);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(t.dp(22), 0, t.dp(22), 0);
        TextView nm = t.text(this, name, 19, t.ink, 800);
        nm.setLineSpacing(t.dp(3), 1f);
        box.addView(nm);
        if (!x.optString("summary").isEmpty()) {
            TextView ds = t.text(this, x.optString("summary"), 15, t.ink, 400);
            ds.setLineSpacing(t.dp(5), 1f);
            ds.setPadding(0, t.dp(10), 0, 0);
            box.addView(ds);
        }
        TextView m = meta(itemMeta(x, false));
        m.setSingleLine(false);
        m.setPadding(0, t.dp(10), 0, 0);
        box.addView(m);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, t.dp(20), 0, 0);
        Dialog d = t.sheet(this, box);
        TextView open = t.text(this, "打开页面", 15, t.ink, 800);
        open.setGravity(Gravity.CENTER);
        t.press(open, t.sheet, 99);
        open.setOnClickListener(v -> {
            d.dismiss();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(x.optString("url"))));
            } catch (Exception e) {
                Trace.log(this, "topic.open.fail", e.toString());
            }
        });
        buttons.addView(open, new LinearLayout.LayoutParams(0, t.dp(52), 1));
        TextView ask = t.text(this, "问一问", 15, t.onLime, 800);
        ask.setGravity(Gravity.CENTER);
        t.press(ask, t.lime, 99);
        ask.setOnClickListener(v -> {
            d.dismiss();
            Push.open(this, new Intent(this, ChatActivity.class).putExtra(ChatActivity.EXTRA_QUESTIONS,
                    new String[]{shortName + " 是做什么的？有什么特别之处？", shortName + " 和同类相比怎么样？"}));
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(0, t.dp(52), 1);
        al.leftMargin = t.dp(10);
        buttons.addView(ask, al);
        box.addView(buttons);
        d.show();
        Trace.log(this, "ui.topic.intro", name);
    }

    // ---------- 公用 ----------

    /** 打开事件详情；话题专题里的事件带上列表数据，详情页先显示出来。 */
    private void openEvent(long id, JSONObject e) {
        Intent i = new Intent(this, EventActivity.class).putExtra(EventActivity.EXTRA_ID, id);
        if (e != null) i.putExtra(EventActivity.EXTRA_JSON, e.toString());
        Push.open(this, i);
    }

    private TextView heading(CharSequence s) {
        TextView v = t.text(this, s, 26, t.ink, 900);
        v.setPadding(t.dp(10), t.dp(26), t.dp(10), t.dp(10));
        return v;
    }

    private TextView label(String s) {
        TextView v = t.text(this, s, 13, t.sub, 700);
        v.setPadding(t.dp(12), t.dp(6), t.dp(12), t.dp(8));
        return v;
    }

    private LinearLayout segmentList(LinearLayout into) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        into.addView(list);
        return list;
    }

    private TextView meta(CharSequence s) {
        TextView v = t.text(this, s, 12, t.sub, 600);
        v.setTypeface(Theme.flex(this, 92, 600));
        v.setPadding(0, t.dp(6), 0, 0);
        v.setSingleLine();
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    private TextView tag(String text, int bg, int fg, float radius) {
        TextView v = t.text(this, text, 11.5f, fg, 800);
        v.setPadding(t.dp(8), t.dp(3), t.dp(8), t.dp(3));
        v.setBackground(t.round(bg, radius));
        return v;
    }

    private LinearLayout.LayoutParams tagLp() {
        LinearLayout.LayoutParams lp = gap(0);
        lp.bottomMargin = t.dp(6);
        return lp;
    }

    private LinearLayout.LayoutParams gap(int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = t.dp(rightDp);
        return lp;
    }

    private TextView note(LinearLayout into, String s) {
        TextView v = t.text(this, s, 14.5f, t.sub, 500);
        v.setGravity(Gravity.CENTER);
        v.setPadding(t.dp(20), t.dp(48), t.dp(20), t.dp(48));
        into.addView(v);
        return v;
    }
}
