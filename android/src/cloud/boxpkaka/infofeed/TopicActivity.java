package cloud.boxpkaka.infofeed;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
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
import android.view.animation.DecelerateInterpolator;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * 一个专题。标题栏是专题卡片同一张雾面金属（Flow），从卡片的位置揭开，看起来像卡片长大了；整页的底是这个专题颜色的雾，
 * 列表的行是磨砂的，时间、来源这些小字用专题色的灰，颜色从标题栏一直延续到底。
 * 话题专题按天列这个话题的事件（和信息流的时间线一样），下面是“榜单上的”这个话题的条目；
 * 榜单专题按名次列全部条目，几个榜之间左右滑切换（也可以点标签），可以只看和你相关的。HF 模型都进模型页，
 * 别的条目有事件的进事件详情，没有的弹出简介和“问一问”。论文专题和榜单一样左右滑，三个列表是为你挑的（写着和你的关系）、
 * 社区热门和最新，点开进论文详情，右上角的“问一问”问这一周的论文。右滑返回：榜单和论文专题在第一页上再右滑。
 * 数据见服务器的 /v1/topics/<key>。
 */
public class TopicActivity extends Activity implements Card.Hero {
    static final String EXTRA_KEY = "key";
    static final String EXTRA_TITLE = "title";

    private Theme t;
    private String key;
    private int[] z, fc; // 专题色（Theme.zone）；标题栏的金属配色（Theme.field）
    private int subInk; // 小字：专题色的灰
    private LinearLayout head, body;
    private FrameLayout deck; // 榜单专题：几个榜横向排开，放在 body 里
    private final SparseArray<View> channelPages = new SparseArray<>();
    private Swipe.Tabs tabs;
    private Swipe.Pager pager;
    private Nav nav;
    private Elastic scroll;
    private HorizontalScrollView tabScroll;
    private TextView sub;
    private FrameLayout root;
    private Flow mist, pearl; // 专题色的雾；首页的珍珠银雾（卡片展开时先盖住首页的列表，见 morph）
    private TextView title;
    private float morphed = 1; // 上一次 morph 到的位置：停在整页以后（右滑缩小时也一直是 1）不再动列表
    private boolean waiting; // 数据在卡片长的途中到了：落定以后再建列表，见 morph
    private ValueAnimator rising; // 落定以后建好的列表一行一行浮上来
    private LinearLayout placeholder; // 还在取数据时的几行空磨砂，见 loading
    private FrameLayout bar; // 标题栏：和专题卡片同一张流动的雾面金属
    private int barTop, barHeight; // 标题栏里内容的上边距、最小高度（随状态栏高度定）
    private int pulled; // 到顶往下拉了多少，标题栏就高出多少
    private JSONObject data;
    private int channel;
    private boolean related;
    private final Map<View, String[]> unseen = new HashMap<>(); // 还没露出来过的事件行 → {事件 id, 哪个列表}，见 noteSeen
    private final Rect visible = new Rect();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        t = new Theme(this);
        t.applyWindow(this);
        key = getIntent().getStringExtra(EXTRA_KEY);
        if (key == null) key = "";
        z = t.zone(key);
        fc = t.field(key);
        subInk = (z[0] == t.sheet ? t.sub : z[1]) & 0x00FFFFFF | (t.night ? 0xB3000000 : 0x9E000000);
        setContentView(build(state == null));
        load();
        Trace.log(this, "ui.topic", key);
    }

    @Override
    public void finish() {
        nav.finish(super::finish); // 先收回卡片或滑出去再关
    }

    /**
     * 从专题页打开的 App 里的页面（事件详情、HF 模型、对话）都带上这个专题，按它的颜色画整页的雾、问一问和点缀色，
     * 不换成那件事自己的话题色（见 EventActivity.EXTRA_ART）；它们再往下点开的页面接着带。startActivity 最后都走到这里。
     */
    @Override
    public void startActivityForResult(Intent i, int request, Bundle options) {
        if (i.getComponent() != null && getPackageName().equals(i.getComponent().getPackageName())) {
            i.putExtra(EventActivity.EXTRA_ART, key);
        }
        super.startActivityForResult(i, request, options);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Track.flush(this);
    }

    /** 专题页的行是一次建好的，建出来不等于看到了：登记下来，露出一半以上时才记一次曝光。 */
    private void watch(View row, long eventId, String from) {
        if (eventId <= 0) return;
        unseen.put(row, new String[]{String.valueOf(eventId), from});
    }

    /** 滚动、布局变化、榜单翻页停下时检查：露出一半以上的事件行记曝光（Track 同一件事一天只记一次）。 */
    private void noteSeen() {
        for (Iterator<Map.Entry<View, String[]>> it = unseen.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<View, String[]> e = it.next();
            View v = e.getKey();
            if (!v.isAttachedToWindow()) { // 重新加载后换掉的行
                it.remove();
            } else if (v.isShown() && v.getGlobalVisibleRect(visible) && visible.height() * 2 >= v.getHeight()
                    && visible.width() * 2 >= v.getWidth()) {
                Track.seen(this, Long.parseLong(e.getValue()[0]), e.getValue()[1]);
                it.remove();
            }
        }
    }

    /** fresh（不是转屏之类的重建）时从点开的卡片展开，或者从右边推进来。 */
    private View build(boolean fresh) {
        root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);
        mist = new Flow(this);
        mist.mist(t, key, Flow.seedOf(key));
        root.addView(mist, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        scroll = new Elastic(this);
        scroll.setClipToPadding(false);
        scroll.setOnPull(this::pull);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.getViewTreeObserver().addOnScrollChangedListener(this::noteSeen);
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(this::noteSeen);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, 0, 0, t.dp(48));

        // 标题栏：和专题卡片同一张流动的雾面金属，不放徽记
        bar = new FrameLayout(this);
        bar.setBackground(t.round(fc[0], 0, 0, Theme.R_2XL, Theme.R_2XL));
        barTop = t.dp(10);
        barHeight = t.dp(178);
        bar.setMinimumHeight(barHeight);
        Flow flow = flow();
        flow.corners(bar);
        bar.addView(flow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(t.dp(16), t.dp(10), t.dp(22), t.dp(22));
        TextView back = t.backButton(this);
        back.setTextColor(fc[2]);
        back.setBackground(t.frostRipple((fc[2] & 0x00FFFFFF) | 0x1F000000, 99));
        head.addView(back, new LinearLayout.LayoutParams(t.dp(48), t.dp(48)));
        title = t.text(this, getIntent().getStringExtra(EXTRA_TITLE), 30, fc[2], 900);
        title.setLineSpacing(t.dp(2), 1f);
        title.setPadding(t.dp(6), t.dp(18), 0, 0);
        head.addView(title);
        sub = t.text(this, "", 13, fc[2], 700);
        sub.setAlpha(0.85f);
        sub.setPadding(t.dp(6), t.dp(8), 0, 0);
        head.addView(sub);
        bar.addView(head);
        page.addView(bar);

        // 榜单专题：几个榜之间切换，右边是“只看相关”
        tabs = new Swipe.Tabs(this, z[2], fc[2], subInk);
        tabs.finish(t.metal(fc[0], 99), null); // 选中的那一格是专题色的金属
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
                barTop = bars.top + t.dp(10);
                barHeight = bars.top + t.dp(178);
                bar.setMinimumHeight(barHeight + pulled);
                head.setPadding(t.dp(16), barTop + pulled, t.dp(22), t.dp(22));
                page.setPadding(0, 0, 0, bars.bottom + t.dp(48));
                return WindowInsets.CONSUMED;
            });
        }
        nav = Nav.of(this, root, t.bg);
        pager = new Swipe.Pager(this, board, true);
        pager.back(nav); // 第一个榜（不是榜单就是整页）再往右滑是返回
        pager.scrollsWith(scroll, () -> tabScroll.getTop());
        pager.frame.addView(root);
        if (fresh) nav.enter();
        return pager.frame;
    }

    // ---------- 卡片长成标题栏（见 Card.Hero） ----------

    @Override
    public View header() {
        return bar;
    }

    @Override
    public TextView title() {
        return title;
    }

    /** 和专题卡片一样的流动金属：同一张抽象封面、一样慢、一样的时钟，卡片长成标题栏时颜色接得上。 */
    @Override
    public Flow flow() {
        Flow f = new Flow(this);
        f.gradient(fc[0], fc[1], Color.luminance(fc[2]) > 0.5f, true, Flow.seedOf(key));
        f.topic(t, key, Flow.seedOf(key));
        f.slow(2);
        return f;
    }

    /**
     * 卡片展开到 q 时页面自己的部分：先铺一层和首页一模一样的珍珠银雾（同一张图、同一个时钟），首页的列表在前 35% 里
     * 消失；专题色的雾在前 55% 里盖上来（盖满以后珍珠银不再画）；列表一行一行从下面浮上来（每行晚一点，最晚的也在 q 到 1
     * 时到位）。收回时倒着走。数据在长的途中到了的，等落定（q 正好是 1）再建列表、再浮上来：长的途中建几十行会卡一下。
     */
    @Override
    public void morph(float q) {
        if (pearl == null && q < 1) {
            pearl = new Flow(this);
            pearl.mist(t, Theme.PEARL, 7);
            root.addView(pearl, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (pearl != null) {
            pearl.setAlpha(clamp(q / 0.35f));
            pearl.setVisibility(q < 0.55f ? View.VISIBLE : View.INVISIBLE);
        }
        mist.setAlpha(clamp(q / 0.55f));
        if (q < 1 && rising != null) rising.cancel(); // 又往回收了
        if (q == 1 && waiting) {
            waiting = false;
            morphed = 1;
            reveal();
            return;
        }
        if (q == morphed && q >= 1) return;
        morphed = q;
        rise(q);
    }

    /** 列表停在卡片展开到 q 时的位置（见 morph）。 */
    private void rise(float q) {
        int i = 0;
        for (View row : rows()) {
            float start = 0.36f + 0.03f * Math.min(i++, 8), p = clamp((q - start) / 0.4f);
            row.setAlpha(p);
            row.setTranslationY((1 - p) * t.dp(40));
        }
    }

    /** 落定以后建好的列表：照展开后半段的样子一行一行浮上来。 */
    private void rise() {
        rise(0.36f);
        rising = ValueAnimator.ofFloat(0.36f, 1f);
        rising.setDuration(460);
        rising.setInterpolator(new DecelerateInterpolator(1.6f));
        rising.addUpdateListener(a -> rise((float) a.getAnimatedValue()));
        rising.start();
    }

    /** 标题栏下面依次浮上来的：榜单的标签、每个小标题和每一行（分段列表拆开成行）。 */
    private List<View> rows() {
        List<View> out = new ArrayList<>();
        if (tabScroll.getVisibility() == View.VISIBLE) out.add(tabScroll);
        for (int i = 0; i < body.getChildCount(); i++) {
            View v = body.getChildAt(i);
            if (v instanceof LinearLayout && ((LinearLayout) v).getOrientation() == LinearLayout.VERTICAL) {
                LinearLayout list = (LinearLayout) v;
                for (int j = 0; j < list.getChildCount(); j++) out.add(list.getChildAt(j));
            } else {
                out.add(v);
            }
        }
        return out;
    }

    private static float clamp(float x) {
        return Math.max(0, Math.min(1, x));
    }

    /**
     * 到顶往下拉（见 Elastic）：标题栏原地变高，顶边一直贴着屏幕顶，金属在更高的面上照常流动；标题、返回按钮和下面的
     * 列表一起往下移。到底往上拉是内容整块平移，这里不管。
     */
    private boolean pull(float offset) {
        int p = Math.round(Math.max(0, offset));
        if (p != pulled) {
            pulled = p;
            bar.setMinimumHeight(barHeight + p);
            head.setPadding(t.dp(16), barTop + p, t.dp(22), t.dp(22));
        }
        return offset > 0;
    }

    /** 榜单专题的几个榜：横向排开，左右滑或点标签切换。不是榜单时没有页，只能右滑返回。 */
    private final Swipe.Pager.Host board = new Swipe.Pager.Host() {
        @Override
        public int count() {
            if (data == null || !paged()) return 0;
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
            noteSeen(); // 翻到的这个榜露出来了
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
                if (morphed < 1) { // 卡片还在长：落定以后再建，见 morph
                    waiting = true;
                    return;
                }
                reveal();
            });
        }, "topic-load").start();
    }

    private void render() {
        if (paged()) renderBoard();
        else renderTopic();
    }

    /** 榜单和论文专题：几个列表横向排开，左右滑切换。 */
    private boolean paged() {
        String kind = data == null ? "" : data.optString("kind");
        return "board".equals(kind) || "papers".equals(kind);
    }

    private boolean papers() {
        return data != null && "papers".equals(data.optString("kind"));
    }

    /**
     * 还在取数据：几行空的磨砂占位，样子和真的列表一样，一行接一行慢慢地明暗起伏，像光从磨砂玻璃后面缓缓移过；
     * 不转圈，也不占住视线（参考 PS5 的加载）。数据到了，占位淡出，真的列表一行一行浮上来（见 reveal）。
     */
    private void loading() {
        body.removeAllViews();
        LinearLayout list = placeholder = segmentList(body);
        list.setPadding(0, t.dp(16), 0, 0);
        for (int i = 0; i < 6; i++) {
            list.addView(new View(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(84)));
        }
        t.segments(list);
        Shapes.breathe(list);
    }

    /** 数据到了：占位淡出，换上真的列表，一行一行浮上来。 */
    private void reveal() {
        LinearLayout holder = placeholder;
        placeholder = null;
        if (holder == null || holder.getParent() != body) {
            render();
            rise();
            return;
        }
        holder.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            if (isDestroyed()) return;
            render();
            rise();
        }).start();
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
        TextView time = t.text(this, MainActivity.clock(Theme.millis(e.optString("time"))), 13, subInk, 800);
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
            TextView summary = t.text(this, e.optString("summary"), 13.5f, subInk, 400);
            summary.setSingleLine();
            summary.setEllipsize(TextUtils.TruncateAt.END);
            summary.setPadding(0, t.dp(3), 0, 0);
            col.addView(summary);
        }
        col.addView(meta(sources(e)));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> openEvent(v, e.optLong("id"), e));
        watch(row, e.optLong("id"), "topic");
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
        if (papers()) {
            JSONArray mine = n > 0 ? channels.optJSONObject(0).optJSONArray("items") : null;
            sub.setText("近 " + data.optInt("days") + " 天为你挑了 " + (mine == null ? 0 : mine.length()) + " 篇 · " + updated);
        } else {
            sub.setText(n > 1 ? n + " 个" + ("hf_models".equals(key) ? "分类" : "榜") + " · " + updated : updated);
        }
        tabs.removeAllViews();
        for (int i = 0; i < n && n > 1; i++) {
            int index = i;
            TextView tab = t.text(this, channels.optJSONObject(i).optString("name"), 14, subInk, 800);
            tab.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(8));
            tab.setBackground(t.ripple(Color.TRANSPARENT, 99));
            tab.setOnClickListener(v -> pager.go(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = t.dp(4);
            tabs.addView(tab, lp);
        }
        TextView only = t.text(this, papers() ? "问一问" : "只看相关", 13.5f, related || papers() ? t.onInv : t.ink, 800);
        only.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(8));
        if (papers()) { // 论文专题不用筛，换成问这一周的论文
            t.askButton(only, t.ask(key), 99);
            only.setOnClickListener(v -> askPapers());
        } else {
            GradientDrawable outline = t.round(related ? t.inv : Color.TRANSPARENT, 99);
            if (!related) outline.setStroke(t.dp(1.5f), t.line);
            only.setBackground(outline);
            only.setOnClickListener(v -> {
                related = !related;
                renderBoard();
            });
        }
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
        boolean paper = papers();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject x = items.optJSONObject(i);
            if (paper) rows.addView(paperRow(x));
            else if (!related || !x.optString("topic").isEmpty()) rows.addView(itemRow(x, false));
        }
        if (rows.getChildCount() == 0) {
            p.removeView(rows);
            note(p, paper ? (index == 0 ? "这几天还没有和你有关的论文" : "这几天还没有论文")
                    : related ? "这个榜现在没有和你相关的" : "这个榜还没有数据");
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
                    .format(new java.util.Date(Theme.millis(x.optString("time")))), 13, subInk, 800);
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
            s.append(name.substring(0, slash) + " / ", new ForegroundColorSpan(subInk), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new TypefaceSpan(Theme.weight(400)), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        s.append(name.substring(slash + 1));
        TextView nm = t.text(this, s, 15.5f, t.ink, 800);
        nm.setLineSpacing(t.dp(3), 1f);
        col.addView(nm);
        String summary = x.optString("summary");
        if (!summary.isEmpty()) {
            TextView ds = t.text(this, summary, 13.5f, subInk, 400);
            ds.setLineSpacing(t.dp(4), 1f);
            ds.setMaxLines(2);
            ds.setEllipsize(TextUtils.TruncateAt.END);
            ds.setPadding(0, t.dp(4), 0, 0);
            col.addView(ds);
        }
        col.addView(meta(itemMeta(x, inTopic)));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> {
            if (x.optString("url").startsWith(ModelActivity.HF)) {
                Card.open(this, v, new Intent(this, ModelActivity.class).putExtra(ModelActivity.EXTRA_JSON, x.toString()));
            } else if (!x.isNull("event_id")) {
                openEvent(v, x.optLong("event_id"), null);
            } else {
                intro(x);
            }
        });
        if (!x.isNull("event_id")) watch(row, x.optLong("event_id"), "board");
        return row;
    }

    // ---------- 论文专题 ----------

    /**
     * 一篇论文：左边是日期；中文标题，下面一行是和你的关系（没有的写概要），最后一行是作者、HF Papers 的点赞数，
     * 按你的记忆搜到的也写上。正好对上你在做的事（5 分）标“精读”，重要性 5 的标“要闻”。点开是论文详情。
     */
    private View paperRow(JSONObject x) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(t.dp(14), t.dp(14), t.dp(18), t.dp(14));
        TextView date = t.text(this, new java.text.SimpleDateFormat("MM-dd", Locale.CHINA)
                .format(new java.util.Date(Theme.millis(x.optString("time")))), 13, subInk, 800);
        date.setTypeface(Theme.flex(this, 120, 800));
        date.setPadding(t.dp(4), t.dp(2), 0, 0);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(t.dp(42), ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.rightMargin = t.dp(10);
        row.addView(date, dl);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        boolean news = x.optInt("importance") >= 5, close = x.optInt("relevance") >= 5;
        if (news || close) {
            LinearLayout tagRow = new LinearLayout(this);
            if (news) tagRow.addView(tag("要闻", t.inv, t.onInv, Theme.R_XS), gap(6));
            if (close) tagRow.addView(tag("精读", t.chip, t.ink, Theme.R_XS)); // 列表行不用专题色
            col.addView(tagRow, tagLp());
        }
        TextView title = t.text(this, x.optString("title"), 15.5f, t.ink, 800);
        title.setLineSpacing(t.dp(3), 1f);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);
        String why = x.optString("why");
        TextView line = t.text(this, why.isEmpty() ? x.optString("summary") : why, 13.5f, why.isEmpty() ? subInk : t.ink,
                why.isEmpty() ? 400 : 600);
        line.setLineSpacing(t.dp(4), 1f);
        line.setMaxLines(2);
        line.setEllipsize(TextUtils.TruncateAt.END);
        line.setPadding(0, t.dp(4), 0, 0);
        if (line.getText().length() > 0) col.addView(line);
        List<CharSequence> parts = new ArrayList<>();
        if (!x.optString("authors").isEmpty()) parts.add(x.optString("authors"));
        if (x.optInt("upvotes") > 0) parts.add("♥ " + count(x.optLong("upvotes")));
        if (!x.optString("chased").isEmpty()) parts.add(ink("按你的记忆搜到", false));
        SpannableStringBuilder m = new SpannableStringBuilder();
        for (CharSequence part : parts) {
            if (m.length() > 0) m.append(" · ");
            m.append(part);
        }
        col.addView(meta(m));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        long id = x.optLong("event_id");
        row.setOnClickListener(v -> openEvent(v, id, null));
        watch(row, id, "papers");
        return row;
    }

    /** 问这一周的论文：服务器在最近两周的事件里找，和你有关的论文没写简报也算。 */
    private void askPapers() {
        Push.open(this, new Intent(this, ChatActivity.class).putExtra(ChatActivity.EXTRA_QUESTIONS, new String[]{
                "这周和我有关的论文里，哪几篇最值得读？", "这些论文里有哪些新方法值得留意？"}));
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

    /** 还没有对应事件的 GitHub 仓库和 Product Hunt 产品：弹出简介，可以打开原页面或问一问。 */
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
        t.press(open, t.frostButton, 99);
        open.setOnClickListener(v -> {
            d.dismiss();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(x.optString("url"))));
            } catch (Exception e) {
                Trace.log(this, "topic.open.fail", e.toString());
            }
        });
        buttons.addView(open, new LinearLayout.LayoutParams(0, t.dp(52), 1));
        TextView ask = t.text(this, "问一问", 15, 0, 800);
        ask.setGravity(Gravity.CENTER);
        t.askButton(ask, t.ask(key), 99);
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

    /**
     * 这一行原地展开成事件详情；话题专题里的事件带上列表数据，详情页一开始就有内容。详情页跟着这个专题的颜色
     * （见 startActivityForResult）：从论文专题点开的还是纸张的暖灰。
     */
    private void openEvent(View from, long id, JSONObject e) {
        Intent i = new Intent(this, EventActivity.class).putExtra(EventActivity.EXTRA_ID, id)
                .putExtra(EventActivity.EXTRA_FROM, papers() ? "papers" : "topic");
        if (e != null) i.putExtra(EventActivity.EXTRA_JSON, e.toString());
        Card.open(this, from, i);
    }

    private TextView heading(CharSequence s) {
        TextView v = t.text(this, s, 26, t.ink, 900);
        v.setPadding(t.dp(10), t.dp(26), t.dp(10), t.dp(10));
        return v;
    }

    private TextView label(String s) {
        TextView v = t.text(this, s, 13, subInk, 700);
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
        TextView v = t.text(this, s, 12, subInk, 600);
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
        TextView v = t.text(this, s, 14.5f, subInk, 500);
        v.setGravity(Gravity.CENTER);
        v.setPadding(t.dp(20), t.dp(48), t.dp(20), t.dp(48));
        into.addView(v);
        return v;
    }
}
