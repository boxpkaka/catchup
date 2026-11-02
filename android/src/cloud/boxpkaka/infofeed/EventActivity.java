package cloud.boxpkaka.infofeed;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 事件详情：卡片色的头部（有封面时放在最上面，标题、来源数、看原文），下面是概要和要点、进展、问一问、全部来源。
 * 从首页最值得看的彩色卡片点开时，头部接着用那张卡片的渐变底色和文字色，封面铺满头部顶上、融进底色，和卡片一个样子。
 * 底部悬浮工具栏：有用 / 不需要，旁边是酸橙色的问一问。
 * 从列表进入时先用列表里的数据立即显示，再从服务器取完整来源列表。
 */
public class EventActivity extends Activity {
    static final String EXTRA_ID = "id";
    static final String EXTRA_JSON = "json";
    static final String EXTRA_DISMISSED = "dismissed";
    static final String EXTRA_FROM = "from"; // 从哪打开的（feed / push / topic …），埋点用，见 Track
    static final String EXTRA_TONE = "tone"; // 彩色卡片的颜色 {上, 下, 文字色, 点缀色}，见 MainActivity.tone
    static final String EXTRA_COVER = "cover"; // 那张彩色卡片有没有封面

    private static final int FOLDED_SOURCES = 4;

    private Theme t;
    private Nav nav;
    private long id;
    private JSONObject event;
    private LinearLayout page, head, body, sources, actions;
    private FrameLayout headBox; // 头部的底色和铺满顶上的封面，head 叠在上面
    private RoundImage headCover;
    private int[] tone; // 从彩色卡片点开时卡片的颜色，其余为 null
    private boolean iconsLight;
    private LinearLayout points, insight, updates, ask; // 正文里会随服务器数据更新的几块，原地更新、不整页重建
    private TextView useful, follow, summary, topicChip, badge, headline, meta, original;
    private String shownPoints, shownInsight, shownUpdates, shownAsk, shownSignals;
    private int topInset;
    private String feedback;
    private boolean following;
    private ScrollView scroll;
    private String from;
    private long resumedAt, readMs; // 停留时间只算页面在前台的部分
    private int depth;              // 读到页面的百分之几

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        t = new Theme(this);
        t.applyWindow(this);
        id = getIntent().getLongExtra(EXTRA_ID, -1);
        from = getIntent().getStringExtra(EXTRA_FROM);
        if (from == null) from = "other";
        tone = getIntent().getIntArrayExtra(EXTRA_TONE);
        nav = Nav.of(this, build(), t.bg);
        setContentView(nav.wrap(state == null)); // 从点开的卡片展开或从右边推进来，右滑返回
        String cached = getIntent().getStringExtra(EXTRA_JSON);
        if (cached != null) {
            try {
                render(new JSONObject(cached));
            } catch (Exception ignored) {
                // 列表数据坏了就等服务器的
            }
        }
        load();
        Trace.log(this, "ui.event", "id=" + id);
    }

    @Override
    public void finish() {
        nav.finish(super::finish); // 先收回卡片或滑出去再关
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumedAt = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onPause() {
        super.onPause();
        readMs += SystemClock.elapsedRealtime() - resumedAt;
        noteDepth();
        if (isFinishing()) {
            Track.read(this, id, from, (int) (readMs / 1000), depth);
            Track.flush(this);
        }
    }

    private void noteDepth() {
        int total = page.getHeight();
        if (total > 0) depth = Math.max(depth, Math.min(100, (scroll.getScrollY() + scroll.getHeight()) * 100 / total));
    }

    @Override
    public void onBackPressed() {
        if (isTaskRoot()) startActivity(new Intent(this, MainActivity.class)); // 从通知直接进来时，返回到首页
        super.onBackPressed();
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);
        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, 0, 0, t.dp(120));
        headBox = new FrameLayout(this);
        head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        headBox.addView(head, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(headBox);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(t.dp(22), t.dp(4), t.dp(22), 0);
        page.addView(body);
        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // 按钮下面垫渐隐底，不用阴影：顶部的等页头滚出去才出现，免得盖住页头
        View topFade = t.fade(this, false);
        topFade.setAlpha(0f);
        FrameLayout.LayoutParams tfl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(80));
        root.addView(topFade, tfl);
        View bottomFade = t.fade(this, true);
        FrameLayout.LayoutParams bfl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(132),
                Gravity.BOTTOM);
        root.addView(bottomFade, bfl);
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            topFade.setAlpha(Math.max(0f, Math.min(1f, (y - head.getHeight() + topFade.getHeight()) / (float) t.dp(32))));
            statusIcons(y);
            noteDepth();
        });

        TextView back = t.backButton(this);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(t.dp(48), t.dp(48));
        bl.setMargins(t.dp(12), t.dp(10), 0, 0);
        root.addView(back, bl);

        follow = t.text(this, "", 14, t.ink, 800);
        follow.setGravity(Gravity.CENTER);
        follow.setPadding(t.dp(20), 0, t.dp(20), 0);
        t.press(follow, t.sheet, 99);
        follow.setOnClickListener(v -> toggleFollow());
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.dp(48),
                Gravity.END | Gravity.TOP);
        fl.setMargins(0, t.dp(10), t.dp(12), 0);
        root.addView(follow, fl);

        // 悬浮工具栏：反色胶囊里放“想多看 / 不需要”，问一问单独做成酸橙色的大按钮
        actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(t.dp(6), t.dp(6), t.dp(6), t.dp(6));
        bar.setBackground(t.round(t.inv, 99));
        useful = action("想多看", v -> toggleWant());
        bar.addView(useful, weight());
        LinearLayout.LayoutParams gap = weight();
        gap.leftMargin = t.dp(4);
        bar.addView(action("不需要", v -> notNeeded()), gap);
        actions.addView(bar, weight());
        TextView ask = t.text(this, "问一问", 15, t.onLime, 800);
        ask.setGravity(Gravity.CENTER);
        t.press(ask, t.lime, Theme.R_L);
        ask.setOnClickListener(v -> openChat(null));
        LinearLayout.LayoutParams askLp = new LinearLayout.LayoutParams(t.dp(104), t.dp(64));
        askLp.leftMargin = t.dp(10);
        actions.addView(ask, askLp);
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        al.setMargins(t.dp(12), 0, t.dp(12), t.dp(16));
        root.addView(actions, al);

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                topInset = bars.top;
                bl.topMargin = bars.top + t.dp(10);
                back.setLayoutParams(bl);
                fl.topMargin = bars.top + t.dp(10);
                follow.setLayoutParams(fl);
                padHead();
                tfl.height = bars.top + t.dp(72);
                topFade.setLayoutParams(tfl);
                bfl.height = bars.bottom + t.dp(132);
                bottomFade.setLayoutParams(bfl);
                page.setPadding(0, 0, 0, bars.bottom + t.dp(120));
                al.bottomMargin = bars.bottom + t.dp(16);
                actions.setLayoutParams(al);
                return WindowInsets.CONSUMED;
            });
        }
        return root;
    }

    private void load() {
        new Thread(() -> {
            JSONObject e = null;
            Exception err = null;
            try {
                e = Api.get(this, "/v1/events/" + id);
            } catch (Exception x) {
                err = x;
            }
            JSONObject got = e;
            Exception error = err;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (got != null) render(got);
                else if (event == null) toast("加载失败：" + MainActivity.reason(error));
            });
        }, "event-load").start();
    }

    /**
     * 列表里的数据先画一次，服务器的完整数据回来再画一次：第二次只改变了的部分，新出现的淡入、下面的平滑让位，
     * 不整页清空重建，免得卡片刚展开内容就跳一下。
     */
    private void render(JSONObject e) {
        event = e;
        feedback = e.isNull("feedback") ? null : e.optString("feedback");
        renderHead(e);
        if (summary == null) buildBody();

        String s = e.optString("summary");
        if (!s.contentEquals(summary.getText())) summary.setText(s);
        summary.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);

        JSONArray ps = e.optJSONArray("points"); // 简报要点：原文里的具体内容，详情接口才有
        if (!String.valueOf(ps).equals(shownPoints)) {
            shownPoints = String.valueOf(ps);
            points.removeAllViews();
            for (int i = 0; ps != null && i < ps.length(); i++) points.addView(pointRow(ps.optString(i), i == 0));
        }

        String in = e.optString("insight"); // AI 解读：模型怎么看这件事，详情接口才有
        if (!in.equals(shownInsight)) {
            shownInsight = in;
            insight.removeAllViews();
            if (!in.isEmpty()) insight.addView(insightCard(in));
        }

        JSONArray us = e.optJSONArray("updates");
        if (!String.valueOf(us).equals(shownUpdates)) {
            shownUpdates = String.valueOf(us);
            updates.removeAllViews();
            if (us != null && us.length() > 0) {
                updates.addView(sectionLabel("进展"));
                for (int i = 0; i < us.length(); i++) {
                    updates.addView(updateRow(us.optJSONObject(i), i == 0, i == us.length() - 1));
                }
            }
        }

        String a = String.valueOf(e.optJSONObject("thread")) + e.optJSONArray("questions");
        if (!a.equals(shownAsk)) {
            shownAsk = a;
            ask.removeAllViews();
            askSection(e);
        }

        JSONArray signals = e.optJSONArray("signals");
        if (!String.valueOf(signals).equals(shownSignals)) {
            shownSignals = String.valueOf(signals);
            if (signals == null) {
                sources.removeAllViews();
                TextView wait = t.text(this, "正在加载来源…", 14, t.sub, 400);
                wait.setPadding(t.dp(16), t.dp(14), t.dp(16), t.dp(14));
                sources.addView(wait);
            } else {
                showSources(signals, signals.length() <= FOLDED_SOURCES + 1);
            }
            t.segments(sources);
        }
        following = e.optBoolean("following");
        updateActions();
    }

    /** 正文的骨架：概要、要点、AI 解读、进展、问一问、来源，各块只建一次。 */
    private void buildBody() {
        summary = t.text(this, "", 17, t.ink, 400);
        summary.setLineSpacing(t.dp(9), 1f);
        summary.setPadding(0, t.dp(22), 0, 0);
        body.addView(summary);
        points = column();
        insight = column();
        updates = column();
        body.addView(sectionLabel("问一问"));
        ask = column();
        body.addView(sectionLabel("来源"));
        sources = column();
        Theme.smooth(body, points, insight, updates, ask, sources);
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        body.addView(v);
        return v;
    }

    private View pointRow(String text, boolean first) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, t.dp(first ? 14 : 10), 0, 0);
        View dot = new View(this);
        dot.setBackground(new Shapes.Blob(Shapes.SOFT, t.pri));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(t.dp(14), t.dp(14));
        dl.topMargin = t.dp(5);
        row.addView(dot, dl);
        TextView p = t.text(this, text, 15.5f, t.ink, 400);
        p.setLineSpacing(t.dp(7), 1f);
        p.setPadding(t.dp(12), 0, 0, 0);
        row.addView(p, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    /** AI 解读和只写事实的概要、要点分开：单独一张浅色卡片，标明是 AI 的看法。 */
    private View insightCard(String text) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(t.dp(18), t.dp(16), t.dp(18), t.dp(18));
        card.setBackground(t.round(t.pc, Theme.R_L));
        card.addView(t.text(this, "AI 解读", 13, t.onPc, 800));
        TextView p = t.text(this, text, 15.5f, t.onPc, 400);
        p.setLineSpacing(t.dp(7), 1f);
        p.setPadding(0, t.dp(8), 0, 0);
        card.addView(p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = t.dp(24);
        card.setLayoutParams(lp);
        return card;
    }

    /** 头部：卡片色延伸到状态栏下面，底部两个大圆角；从首页点进来时颜色和列表项一样，不跳变。控件只建一次，之后只改字。 */
    private void renderHead(JSONObject e) {
        if (headline == null) buildHead();
        String topic = Theme.topicOf(e);
        topicChip.setText(topic); // 主话题，中性的标签，不上色
        topicChip.setVisibility(topic.isEmpty() ? View.GONE : View.VISIBLE);
        badge.setVisibility(e.optInt("importance") >= 5 ? View.VISIBLE : View.GONE);
        if (!e.optString("headline").contentEquals(headline.getText())) headline.setText(e.optString("headline"));
        meta.setText(e.optInt("source_count") + " 个来源   最近 " + Theme.ago(Theme.millis(e.optString("time"))));
        original.setOnClickListener(v -> openUrl(e.optString("url")));
    }

    /** 头部：底色是卡片色，从彩色卡片点开时是那张卡片的渐变；有封面的话封面铺满顶上、下半截融进底色。 */
    private void buildHead() {
        int[] z = tone != null ? tone : new int[]{t.sheet, t.sheet, t.ink, t.ink};
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{z[0], z[1]});
        float r = t.dp(44);
        bg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        headBox.setBackground(bg);
        String coverPath = "/v1/events/" + id + "/cover";
        if (tone != null && getIntent().getBooleanExtra(EXTRA_COVER, false)) {
            headCover = new RoundImage(this, 0);
            headCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            headCover.fadeBottom(0.4f);
            headBox.addView(headCover, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)); // 高度见 padHead
            Bitmap ready = Images.cached(coverPath); // 卡片上刚显示过，多半还在内存里，第一帧就有图
            if (ready != null) headCover.setImageBitmap(ready);
            else Images.load(this, coverPath, b -> {
                headCover.setImageAlpha(0);
                headCover.setImageBitmap(b);
                ObjectAnimator.ofInt(headCover, "imageAlpha", 0, 255).setDuration(320).start();
            });
        }
        padHead();
        statusIcons(0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        topicChip = t.text(this, "", 12, z[2], 700);
        topicChip.setPadding(t.dp(11), t.dp(4), t.dp(11), t.dp(4));
        topicChip.setBackground(t.round(tone != null ? z[2] & 0xFFFFFF | 0x24000000 : t.chip, 99));
        top.addView(topicChip);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        badge = t.text(this, "要闻", 12, tone != null ? z[0] : t.onInv, 800); // 和首页一样是墨色（彩色卡片上是文字色）反白
        badge.setPadding(t.dp(10), t.dp(4), t.dp(10), t.dp(4));
        badge.setBackground(t.round(tone != null ? z[2] : t.inv, Theme.R_XS));
        top.addView(badge);
        head.addView(top);

        headline = t.text(this, "", 27, z[2], 900);
        headline.setLineSpacing(t.dp(7), 1f);
        headline.setLetterSpacing(-0.01f);
        headline.setPadding(0, t.dp(14), 0, 0);
        head.addView(headline);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(0, t.dp(16), 0, 0);
        meta = t.text(this, "", 13, tone != null ? z[2] & 0xFFFFFF | 0xB8000000 : t.sub, 700);
        meta.setTypeface(Theme.flex(this, 92, 700));
        metaRow.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        original = t.text(this, "看原文 ↗", 13, tone != null ? z[0] : t.onInv, 700);
        original.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(8));
        original.setBackground(t.ripple(tone != null ? z[2] : t.inv, 99));
        metaRow.addView(original);
        head.addView(metaRow);
        Theme.smooth(head, top);
        if (headCover == null) Images.load(this, coverPath, this::showCover);
    }

    /** 头部的留白：顶上让出状态栏和返回按钮；铺了封面的，封面高度算上状态栏，标题从封面融开的地方开始。 */
    private void padHead() {
        int top = topInset + t.dp(68);
        if (headCover != null) {
            int h = topInset + getResources().getDisplayMetrics().widthPixels * 9 / 16;
            headCover.getLayoutParams().height = h;
            headCover.requestLayout();
            top = Math.max(top, h - t.dp(36));
        }
        head.setPadding(t.dp(22), top, t.dp(22), t.dp(26));
    }

    /** 浅色模式下从深色卡片点开：深色头部还在状态栏下面时状态栏图标用浅色，滚过去以后换回深色。 */
    private void statusIcons(int scrollY) {
        boolean light = !t.night && tone != null && Color.luminance(tone[2]) > 0.5f
                && (headBox.getHeight() == 0 || scrollY < headBox.getHeight() - topInset); // 还没量出高度时就在顶上
        if (light == iconsLight) return;
        iconsLight = light;
        View d = getWindow().getDecorView();
        int f = d.getSystemUiVisibility();
        d.setSystemUiVisibility(light ? f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    /** 封面：16:9 圆角大图，插在头部最上面，下面的内容平滑让位（见 Theme.smooth）；没有封面时头部和原来一样。 */
    private void showCover(Bitmap b) {
        ImageView cover = new ImageView(this) {
            @Override
            protected void onMeasure(int w, int h) {
                int width = MeasureSpec.getSize(w);
                setMeasuredDimension(width, width * 9 / 16);
            }
        };
        cover.setImageBitmap(b);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(t.round(t.chip, Theme.R_M));
        cover.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = t.dp(18);
        head.addView(cover, 0, lp);
    }

    /** 来源超过 5 条时先显示 4 条，最后一行“展开全部来源”。 */
    private void showSources(JSONArray signals, boolean all) {
        sources.removeAllViews();
        int n = all ? signals.length() : FOLDED_SOURCES;
        for (int i = 0; i < n; i++) sources.addView(sourceRow(signals.optJSONObject(i)));
        if (!all) {
            TextView more = t.text(this, "展开全部来源", 13.5f, t.pri, 700);
            more.setPadding(t.dp(16), t.dp(13), t.dp(16), t.dp(13));
            more.setOnClickListener(v -> {
                showSources(signals, true);
                t.segments(sources);
            });
            sources.addView(more);
        }
    }

    private TextView sectionLabel(String text) {
        TextView v = t.text(this, text, 18, t.ink, 800);
        v.setPadding(0, t.dp(30), 0, t.dp(12));
        return v;
    }

    /** 追踪发现的实质进展，最新的在上：时间线，最新一条用太阳形节点。 */
    private View updateRow(JSONObject u, boolean latest, boolean last) {
        FrameLayout row = new FrameLayout(this);
        if (!last) {
            View line = new View(this);
            line.setBackgroundColor(t.line);
            FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(t.dp(2), ViewGroup.LayoutParams.MATCH_PARENT);
            ll.leftMargin = t.dp(7);
            ll.topMargin = t.dp(20);
            row.addView(line, ll);
        }
        View node = new View(this);
        node.setBackground(new Shapes.Blob(latest ? Shapes.SUNNY : Shapes.SOFT, latest ? t.pri : t.line));
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(t.dp(16), t.dp(16));
        nl.topMargin = t.dp(3);
        row.addView(node, nl);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(t.dp(30), 0, 0, t.dp(16));
        TextView text = t.text(this, u.optString("text"), 15, t.ink, 600);
        text.setLineSpacing(t.dp(5), 1f);
        col.addView(text);
        TextView time = t.text(this, Theme.ago(Theme.millis(u.optString("at"))), 12, t.sub, 400);
        time.setPadding(0, t.dp(3), 0, 0);
        col.addView(time);
        row.addView(col);
        return row;
    }

    /** 问一问：建议问题直接发出；有过对话的可以接着聊。放进 ask 里，标题在外面不动。 */
    private void askSection(JSONObject e) {
        LinearLayout box = ask;
        JSONObject thread = e.optJSONObject("thread");
        if (thread != null && thread.optInt("count") > 0) {
            LinearLayout resume = new LinearLayout(this);
            resume.setGravity(Gravity.CENTER_VERTICAL);
            resume.setPadding(t.dp(18), t.dp(14), t.dp(18), t.dp(14));
            resume.setBackground(t.ripple(t.pc, 20));
            resume.addView(t.text(this, "继续上次的对话", 15, t.onPc, 700),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView count = t.text(this, thread.optInt("count") + " 条", 14, t.onPc, 700);
            count.setTypeface(Theme.flex(this, 110, 700));
            resume.addView(count);
            resume.setOnClickListener(v -> openChat(null));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = t.dp(8);
            box.addView(resume, lp);
        }
        JSONArray qs = e.optJSONArray("questions");
        for (int i = 0; qs != null && i < qs.length(); i++) {
            String q = qs.optString(i);
            TextView chip = t.text(this, q, 14.5f, t.ink, 600);
            chip.setLineSpacing(t.dp(3), 1f);
            chip.setPadding(t.dp(16), t.dp(12), t.dp(16), t.dp(12));
            chip.setBackground(t.ripple(t.sheet, 18));
            chip.setOnClickListener(v -> openChat(q));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = t.dp(8);
            box.addView(chip, lp);
        }
    }

    private void toggleFollow() {
        following = !following;
        updateActions();
        follow.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        post("/v1/events/" + id + "/follow", "{\"on\": " + following + "}");
        toast(following ? "已开始追踪" : "已取消追踪");
    }

    private void openChat(String question) {
        Intent i = new Intent(this, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_EVENT, id)
                .putExtra(ChatActivity.EXTRA_TITLE, event == null ? "" : event.optString("headline"));
        JSONArray qs = event == null ? null : event.optJSONArray("questions");
        if (qs != null) {
            String[] arr = new String[qs.length()];
            for (int k = 0; k < arr.length; k++) arr[k] = qs.optString(k);
            i.putExtra(ChatActivity.EXTRA_QUESTIONS, arr);
        }
        if (question != null) i.putExtra(ChatActivity.EXTRA_ASK, question);
        Push.open(this, i);
    }

    private void post(String path, String json) {
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try {
                Api.post(app, path, new JSONObject(json));
            } catch (Exception err) {
                Trace.log(app, "post.fail", path + " " + err);
            }
        }, "post").start();
    }

    private View sourceRow(JSONObject s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(t.dp(16), t.dp(12), t.dp(16), t.dp(12));
        row.addView(t.text(this, s.optString("label") + "，" + Theme.ago(Theme.millis(s.optString("time"))),
                12, t.sub, 700));
        TextView title = t.text(this, s.optString("title"), 14, t.ink, 400);
        title.setLineSpacing(t.dp(3), 1f);
        title.setPadding(0, t.dp(3), 0, 0);
        row.addView(title);
        row.setOnClickListener(v -> openUrl(s.optString("url")));
        return row;
    }

    private TextView action(String label, View.OnClickListener l) {
        TextView a = t.text(this, label, 14, t.onInv, 700);
        a.setGravity(Gravity.CENTER);
        a.setPadding(0, t.dp(14), 0, t.dp(14));
        a.setBackground(t.ripple(Color.TRANSPARENT, 99));
        a.setOnClickListener(l);
        return a;
    }

    private static Spring.Shape shape(View v) {
        return (Spring.Shape) ((android.graphics.drawable.RippleDrawable) v.getBackground()).getDrawable(0);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    /** 选中的按钮换形状：“想多看”的底色收成靠内侧的小圆角，追踪从胶囊变成墨色小圆角方块。 */
    private void updateActions() {
        boolean on = "useful".equals(feedback);
        useful.setText(on ? "✓ 想多看" : "想多看");
        useful.setBackground(t.ripple(on ? (t.onInv & 0x00FFFFFF) | 0x29000000 : Color.TRANSPARENT,
                99, on ? Theme.R_M - 2 : 99, on ? Theme.R_M - 2 : 99, 99));
        follow.setText(following ? "✓ 追踪中" : "+ 追踪");
        follow.setTextColor(following ? t.bg : t.ink);
        shape(follow).morph(t.dp(following ? Theme.R_M : 99), following ? t.ink : t.sheet);
    }

    /** 想多看这类：服务器记下或加强相关的记忆，回来后提示是哪一条；再点一次撤回（当时记下的一起删）。 */
    private void toggleWant() {
        String value = "useful".equals(feedback) ? null : "useful";
        feedback = value;
        updateActions();
        useful.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        Trace.log(this, "ui.feedback", "id=" + id + " value=" + value);
        android.content.Context app = getApplicationContext();
        long eventId = id;
        new Thread(() -> {
            JSONObject r = null;
            try {
                r = Api.post(app, "/v1/events/" + eventId + "/feedback", new JSONObject()
                        .put("value", value == null ? JSONObject.NULL : value)
                        .put("reason", value == null ? JSONObject.NULL : "topic"));
            } catch (Exception err) {
                Trace.log(app, "feedback.fail", "id=" + eventId + " " + err);
            }
            JSONObject got = r;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (got == null) toast("没有记下，请重试");
                else if (value != null) toast(wantNote(got));
            });
        }, "want").start();
    }

    /** “想多看”之后的提示：记下或加强了哪条记忆，说成“以后多帮你留意「…」”；都没有时说这类内容。 */
    static String wantNote(JSONObject r) {
        JSONObject m = r.optJSONObject("memory");
        for (String key : new String[]{"added", "updated", "related"}) {
            JSONArray items = m == null ? null : m.optJSONArray(key);
            if (items != null && items.length() > 0) {
                return "好的，以后多帮你留意「" + MemoryBubbles.label(items.optJSONObject(0).optString("text")) + "」";
            }
        }
        return "好的，以后多帮你留意这类内容";
    }

    private void notNeeded() {
        if (getCallingActivity() != null) {
            // 从首页打开的：交回首页移除，首页会给出撤销
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_DISMISSED, id));
        } else {
            send("not_needed");
            toast("已标记为不需要，可在菜单“不需要的内容”里恢复");
        }
        finish();
    }

    private void send(String value) {
        post("/feedback", "value", value == null ? JSONObject.NULL : value);
        Trace.log(this, "ui.feedback", "id=" + id + " value=" + value);
    }

    /** 后台把这件事上的一个动作（反馈、看原文）报给服务器，失败只记日志。 */
    private void post(String action, String key, Object value) {
        android.content.Context app = getApplicationContext();
        long eventId = id;
        new Thread(() -> {
            try {
                Api.post(app, "/v1/events/" + eventId + action, new JSONObject().put(key, value));
            } catch (Exception err) {
                Trace.log(app, action.substring(1) + ".fail", "id=" + eventId + " " + err);
            }
        }, action.substring(1)).start();
    }

    private void openUrl(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            post("/opened", "url", url); // 看原文是反馈学习里的兴趣信号
        } catch (Exception e) {
            toast("没有可以打开链接的应用");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
