package cloud.boxpkaka.infofeed;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
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

import java.util.Locale;

/**
 * 事件详情：头部（有封面时放在最上面，标题、来源数、看原文），下面是概要和要点、AI 解读、进展、问一问、全部来源。
 * 从首页最值得看的彩色卡片点开时，头部接着用那张卡片的颜色：封面铺满顶上，底下是和卡片一样流动的雾面金属（Flow），
 * 往下淌成一团雾，深色在标题下面化开、浅雾淌进正文，一侧淌得深、一侧早收住。整页的底是这件事颜色的雾（封面或话题，
 * 没有话题的是珍珠银），正文不用方框，靠留白和小标题分段。返回、追踪是磨砂按钮。
 * 底部悬浮工具栏：金属的有用 / 不需要，旁边是酸橙色的问一问。
 * 从列表进入时先用列表里的数据立即显示，再从服务器取完整来源列表。
 */
public class EventActivity extends Activity {
    static final String EXTRA_ID = "id";
    static final String EXTRA_JSON = "json";
    static final String EXTRA_DISMISSED = "dismissed";
    static final String EXTRA_FROM = "from"; // 从哪打开的（feed / push / topic …），埋点用，见 Track
    static final String EXTRA_TONE = "tone"; // 彩色卡片的颜色 {上, 下, 文字色, 点缀色}，见 MainActivity.tone
    static final String EXTRA_COVER = "cover"; // 那张彩色卡片有没有封面
    static final String EXTRA_ART = "art"; // 没有封面的彩色卡片用哪个话题的颜色（见 Theme.field），没有话题是 Theme.PEARL

    private static final int FOLDED_SOURCES = 4;
    private static final int BLEED_DP = 360; // 头部的颜色往下淌进正文的长度

    private Theme t;
    private Nav nav;
    private long id;
    private JSONObject event;
    private LinearLayout page, head, body, sources, actions;
    private FrameLayout content; // 滚动的内容：底下垫着头部往下淌的颜色（backdrop），上面是 page
    private Flow backdrop, mist; // 头部往下淌的雾；整页的雾
    private int accent; // 正文的点缀色，第一次显示时定下来，见 Theme.accent
    private boolean accented;
    private FrameLayout headBox; // 头部和铺满顶上的封面，head 叠在上面
    private RoundImage headCover;
    private int[] tone; // 从彩色卡片点开时卡片的颜色，其余为 null
    private boolean iconsLight;
    private LinearLayout relation, points, insight, updates, ask; // 正文里会随服务器数据更新的几块，原地更新、不整页重建
    private TextView useful, follow, summary, topicChip, badge, headline, meta, original;
    private String shownRelation, shownPoints, shownInsight, shownUpdates, shownAsk, shownSignals;
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
        nav = Nav.of(this, build(), t.sheet);
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
        root.setBackgroundColor(t.sheet);
        mist = new Flow(this); // 颜色在第一次显示内容时定，见 buildHead
        root.addView(mist, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
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
        body.setClipToPadding(false); // AI 解读的色带越过左右留白铺满整个宽度，不能被裁成方块
        body.setClipChildren(false);
        page.addView(body);
        content = new FrameLayout(this) {
            /** 头部往下淌的颜色比头部长出一截：先量出头部多高，再定它的高度，第一帧就对，不会等布局完再跳。 */
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                if (backdrop != null) {
                    page.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    backdrop.getLayoutParams().height = headBox.getMeasuredHeight() + t.dp(BLEED_DP);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        content.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // 按钮下面垫一层半透明的渐隐（像起了一层薄雾），不用阴影：顶部的等页头滚出去才出现，免得盖住页头
        int haze = t.night ? 0xB31D1E26 : 0xA6FFFFFF;
        View topFade = t.fade(this, false, haze);
        topFade.setAlpha(0f);
        FrameLayout.LayoutParams tfl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(80));
        root.addView(topFade, tfl);
        View bottomFade = t.fade(this, true, haze);
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
        t.press(follow, t.frostButton, 99);
        follow.setOnClickListener(v -> toggleFollow());
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.dp(48),
                Gravity.END | Gravity.TOP);
        fl.setMargins(0, t.dp(10), t.dp(12), 0);
        root.addView(follow, fl);

        // 悬浮工具栏：墨色金属的胶囊里放“想多看 / 不需要”，问一问单独做成酸橙色的大按钮
        actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(t.dp(6), t.dp(6), t.dp(6), t.dp(6));
        bar.setBackground(t.metal(t.inv, 99));
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
        if (!accented) { // 只定一次：服务器的数据回来时颜色不跟着变
            accent = t.accent(e);
            accented = true;
        }
        feedback = e.isNull("feedback") ? null : e.optString("feedback");
        renderHead(e);
        if (summary == null) buildBody();

        String s = e.optString("summary");
        if (!s.contentEquals(summary.getText())) summary.setText(s);
        summary.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);

        JSONObject paper = e.optJSONObject("paper"); // 论文和你的关系（见 catchup/pipeline/papers.py），详情接口才有
        String why = paper == null ? "" : paper.optString("why");
        if (!why.equals(shownRelation)) {
            shownRelation = why;
            relation.removeAllViews();
            if (!why.isEmpty()) {
                relation.addView(sectionHead("和你的关系", null));
                TextView p = t.text(this, why, 15.5f, t.ink, 600);
                p.setLineSpacing(t.dp(7), 1f);
                relation.addView(p);
            }
        }

        JSONArray ps = e.optJSONArray("points"); // 简报要点：原文里的具体内容，详情接口才有
        if (!String.valueOf(ps).equals(shownPoints)) {
            shownPoints = String.valueOf(ps);
            points.removeAllViews();
            if (ps != null && ps.length() > 0) points.addView(sectionHead("要点", ps.length() + " 条"));
            for (int i = 0; ps != null && i < ps.length(); i++) points.addView(pointRow(ps.optString(i), i));
        }

        String in = e.optString("insight"); // AI 解读：模型怎么看这件事，详情接口才有
        if (!in.equals(shownInsight)) {
            shownInsight = in;
            insight.removeAllViews();
            if (!in.isEmpty()) insight.addView(insightBand(in));
        }

        JSONArray us = e.optJSONArray("updates");
        if (!String.valueOf(us).equals(shownUpdates)) {
            shownUpdates = String.valueOf(us);
            updates.removeAllViews();
            if (us != null && us.length() > 0) {
                updates.addView(sectionHead("进展", null));
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
                sources.addView(sectionHead("来源", null));
                sources.addView(t.text(this, "正在加载来源…", 14, t.sub, 400));
            } else {
                showSources(signals, signals.length() <= FOLDED_SOURCES + 1);
            }
        }
        following = e.optBoolean("following");
        updateActions();
    }

    /**
     * 正文的骨架：概要、（论文）和你的关系、要点、AI 解读、进展、问一问、来源，各块只建一次。不用方框，直接排在白页上，靠留白和小标题分段；
     * 从彩色卡片点开时概要往下让一段，留给头部的颜色融开。
     */
    private void buildBody() {
        summary = t.text(this, "", 17.5f, t.ink, 400);
        summary.setLineSpacing(t.dp(10), 1f);
        summary.setPadding(0, t.dp(tone != null ? 74 : 22), 0, 0);
        body.addView(summary);
        relation = column();
        points = column();
        insight = column();
        updates = column();
        body.addView(sectionHead("问一问", null));
        ask = column();
        sources = column();
        Theme.smooth(body, relation, points, insight, updates, ask, sources);
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        body.addView(v);
        return v;
    }

    /** 小标题：左边是名字，右边是条数之类的小字（可以为 null）；和上一块之间留一段空白。 */
    private View sectionHead(String label, String note) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, t.dp(34), 0, t.dp(12));
        TextView name = t.text(this, label, 13, t.sub, 800);
        name.setLetterSpacing(0.04f);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (note != null) {
            TextView n = t.text(this, note, 12, t.sub, 600);
            n.setTypeface(Theme.flex(this, 96, 600));
            row.addView(n);
        }
        return row;
    }

    /** 一条要点：左边是加宽数字的编号（点缀色），右边是正文。 */
    private View pointRow(String text, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, index == 0 ? 0 : t.dp(12), 0, 0);
        TextView num = t.text(this, String.format(Locale.ROOT, "%02d", index + 1), 15, accent, 800);
        num.setTypeface(Theme.flex(this, 125, 800));
        num.setPadding(0, t.dp(3), 0, 0);
        row.addView(num, new LinearLayout.LayoutParams(t.dp(34), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView p = t.text(this, text, 15.5f, t.ink, 400);
        p.setLineSpacing(t.dp(7), 1f);
        row.addView(p, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    /**
     * AI 解读和只写事实的概要、要点分开：一条横贯全宽的淡色带（点缀色），上下两头融进白页，没有边角；
     * 四角星标记，注明是模型的看法。
     */
    private View insightBand(String text) {
        LinearLayout band = new LinearLayout(this);
        band.setOrientation(LinearLayout.VERTICAL);
        band.setPadding(t.dp(22), t.dp(34), t.dp(22), t.dp(36));
        int tint = (accent & 0x00FFFFFF) | (t.night ? 0x29000000 : 0x17000000);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, null);
        g.setColors(new int[]{tint & 0x00FFFFFF, tint, tint, tint & 0x00FFFFFF}, new float[]{0, 0.26f, 0.74f, 1});
        band.setBackground(g);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = t.text(this, "AI 解读", 14, accent, 800);
        Drawable spark = new Spark(accent, Theme.mix(accent, t.night ? Color.BLACK : Color.WHITE, 0.45f));
        spark.setBounds(0, 0, t.dp(16), t.dp(16));
        label.setCompoundDrawablesRelative(spark, null, null, null);
        label.setCompoundDrawablePadding(t.dp(7));
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(t.text(this, "模型的看法，不是报道原文", 12, t.sub, 600));
        band.addView(head);
        TextView p = t.text(this, text, 15.5f, t.ink, 400);
        p.setLineSpacing(t.dp(8), 1f);
        p.setPadding(0, t.dp(12), 0, 0);
        band.addView(p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = t.dp(26);
        lp.leftMargin = -t.dp(22); // 铺满整个宽度，越过正文的左右留白
        lp.rightMargin = -t.dp(22);
        band.setLayoutParams(lp);
        return band;
    }

    /** 四角星：AI 解读的标记，从点缀色渐变到更浅（深色模式更深）的同色。 */
    private static final class Spark extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int from, to;

        Spark(int from, int to) {
            this.from = from;
            this.to = to;
        }

        @Override
        protected void onBoundsChange(Rect b) {
            float s = b.width() / 24f;
            path.reset();
            path.moveTo(12, 1.5f);
            path.cubicTo(12.8f, 7.5f, 16.4f, 11.1f, 22.5f, 12);
            path.cubicTo(16.4f, 12.9f, 12.8f, 16.5f, 12, 22.5f);
            path.cubicTo(11.2f, 16.5f, 7.6f, 12.9f, 1.5f, 12);
            path.cubicTo(7.6f, 11.1f, 11.2f, 7.5f, 12, 1.5f);
            path.close();
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate(b.left, b.top);
            path.transform(m);
            paint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom, from, to, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas c) {
            c.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** 列表里两行之间的细线。 */
    private View hairline() {
        View v = new View(this);
        v.setBackgroundColor((t.ink & 0x00FFFFFF) | 0x14000000);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, t.dp(0.6f))));
        return v;
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

    /**
     * 头部：头部不单独上色，底下是整页的雾。从彩色卡片点开时，底下垫一层和卡片一样流动的雾面金属（同一张模糊封面或抽象封面），
     * 比头部长出一截、往下淌成雾，跟着内容一起滚走；有封面的话封面铺满顶上、下半截融进去。
     */
    private void buildHead() {
        int[] z = tone != null ? tone : new int[]{t.sheet, t.sheet, t.ink, t.ink};
        String coverPath = "/v1/events/" + id + "/cover";
        boolean cover = tone != null && getIntent().getBooleanExtra(EXTRA_COVER, false);
        String art = getIntent().getStringExtra(EXTRA_ART);
        if (art == null) art = Theme.topicOf(event).isEmpty() ? Theme.PEARL : Theme.topicOf(event);
        // 整页的雾：有封面的卡片用封面（还没下来时先用话题色），否则用话题色；第一帧之后才定的（从推送进来、等服务器）淡入
        if (mist.isLaidOut()) {
            mist.setAlpha(0f);
            mist.animate().alpha(1f).setDuration(400).start();
        }
        mist.mist(t, art, id);
        if (tone != null) {
            backdrop = new Flow(this);
            backdrop.gradient(z[0], z[1], Color.luminance(z[2]) > 0.5f, false, id);
            if (!cover) backdrop.topic(t, art, id);
            content.addView(backdrop, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)); // 高度见 content
        }
        if (cover) {
            headCover = new RoundImage(this, 0);
            headCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            headCover.fadeBottom(0.4f);
            headBox.addView(headCover, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)); // 高度见 padHead
            // 卡片上刚显示过，多半还在内存里：第一帧就有图和流动的底，往哪边淌也按封面定
            Bitmap ready = Images.cached(coverPath), blurred = Flow.cachedBlur(coverPath);
            if (blurred == null && ready != null) blurred = Flow.blurred(coverPath, ready);
            if (ready != null) headCover.setImageBitmap(ready);
            if (blurred != null) {
                backdrop.cover(blurred, false);
                mist.mistCover(blurred, false);
            }
            backdrop.bleed(blurred != null ? Flow.lean(blurred) : 0, id, t.dp(BLEED_DP), z[3]);
            if (ready == null) Images.load(this, coverPath, b -> {
                headCover.setImageAlpha(0);
                headCover.setImageBitmap(b);
                ObjectAnimator.ofInt(headCover, "imageAlpha", 0, 255).setDuration(320).start();
                backdrop.cover(Flow.blurred(coverPath, b), true);
                mist.mistCover(Flow.blurred(coverPath, b), true);
            });
        } else if (backdrop != null) {
            backdrop.bleed(0, id, t.dp(BLEED_DP), z[3]);
        }
        padHead();
        statusIcons(0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        topicChip = t.text(this, "", 12, z[2], 700);
        topicChip.setPadding(t.dp(11), t.dp(4), t.dp(11), t.dp(4));
        topicChip.setBackground(t.frost(tone != null ? z[2] & 0xFFFFFF | 0x24000000 : t.frost, 99, 99, 99, 99));
        top.addView(topicChip);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        badge = t.text(this, "要闻", 12, tone != null ? z[0] : t.onInv, 800); // 和首页一样是墨色（彩色卡片上是文字色）反白
        badge.setPadding(t.dp(10), t.dp(4), t.dp(10), t.dp(4));
        badge.setBackground(tone != null ? t.round(z[2], Theme.R_XS) : t.metal(t.inv, Theme.R_XS));
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
        original.setBackground(tone != null ? t.frostRipple(z[2] & 0xFFFFFF | 0xD6000000, 99)
                : new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF),
                t.metal(t.inv, 99), null));
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

    /** 来源：小标题写共几家；一行一条，中间细线隔开；超过 5 条时先显示 4 条，最后一行“展开全部 N 个来源”。 */
    private void showSources(JSONArray signals, boolean all) {
        sources.removeAllViews();
        sources.addView(sectionHead("来源", signals.length() + " 家报道"));
        int n = all ? signals.length() : FOLDED_SOURCES;
        for (int i = 0; i < n; i++) {
            if (i > 0) sources.addView(hairline());
            sources.addView(sourceRow(signals.optJSONObject(i)));
        }
        if (!all) {
            sources.addView(hairline());
            LinearLayout more = new LinearLayout(this);
            more.setPadding(0, t.dp(13), 0, t.dp(13));
            more.addView(t.text(this, "展开全部 " + signals.length() + " 个来源", 14, accent, 800),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            more.addView(t.text(this, "›", 15, accent, 800));
            more.setBackground(t.ripple(Color.TRANSPARENT, Theme.R_XS));
            more.setOnClickListener(v -> showSources(signals, true));
            sources.addView(more);
        }
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

    /** 问一问：建议问题一行一个，点了直接发出；有过对话的第一行是接着聊。细线隔开，不用方框；标题在外面不动。 */
    private void askSection(JSONObject e) {
        LinearLayout box = ask;
        JSONObject thread = e.optJSONObject("thread");
        if (thread != null && thread.optInt("count") > 0) {
            box.addView(askRow("继续上次的对话", thread.optInt("count") + " 条", true, v -> openChat(null)));
        }
        JSONArray qs = e.optJSONArray("questions");
        for (int i = 0; qs != null && i < qs.length(); i++) {
            String q = qs.optString(i);
            if (box.getChildCount() > 0) box.addView(hairline());
            box.addView(askRow(q, null, false, v -> openChat(q)));
        }
    }

    private View askRow(String text, String note, boolean resume, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, t.dp(13), 0, t.dp(13));
        TextView q = t.text(this, text, 15, resume ? accent : t.ink, resume ? 800 : 500);
        q.setLineSpacing(t.dp(4), 1f);
        row.addView(q, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (note != null) {
            TextView n = t.text(this, note, 12.5f, t.sub, 700);
            n.setTypeface(Theme.flex(this, 110, 700));
            n.setPadding(t.dp(10), 0, 0, 0);
            row.addView(n);
        }
        TextView go = t.text(this, "›", 16, accent, 800);
        go.setPadding(t.dp(12), 0, 0, 0);
        row.addView(go);
        row.setBackground(t.ripple(Color.TRANSPARENT, Theme.R_XS));
        row.setOnClickListener(click);
        return row;
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

    /** 一条来源：网站图标，来源名和时间，下面是这篇报道的标题。Google 新闻的标题末尾带着“ - 媒体”，去掉。 */
    private View sourceRow(JSONObject s) {
        String url = s.optString("url"), label = s.optString("label"), title = s.optString("title");
        String host = Uri.parse(url).getHost(), domain = host == null ? "" : host.replaceFirst("^(www|m)\\.", "");
        if (domain.equals("news.google.com")) {
            int i = title.lastIndexOf(" - ");
            if (i > 0) title = title.substring(0, i);
            domain = ""; // 不知道媒体的域名，图标只显示首字
        }
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, t.dp(13), 0, t.dp(13));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(t.dp(26), t.dp(26));
        il.rightMargin = t.dp(12);
        il.topMargin = t.dp(1);
        row.addView(siteIcon(label, domain), il);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = t.text(this, label, 13, t.ink, 800);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView time = t.text(this, Theme.ago(Theme.millis(s.optString("time"))), 12, t.sub, 600);
        time.setTypeface(Theme.flex(this, 96, 600));
        time.setPadding(t.dp(8), 0, 0, 0);
        top.addView(time);
        col.addView(top);
        TextView head = t.text(this, title, 14.5f, t.ink, 400);
        head.setLineSpacing(t.dp(4), 1f);
        head.setMaxLines(2);
        head.setEllipsize(TextUtils.TruncateAt.END);
        head.setPadding(0, t.dp(4), 0, 0);
        col.addView(head);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView go = t.text(this, "↗", 12, t.sub, 700);
        go.setPadding(t.dp(10), t.dp(1), 0, 0);
        row.addView(go);
        row.setBackground(t.ripple(Color.TRANSPARENT, Theme.R_XS));
        row.setOnClickListener(v -> openUrl(url));
        return row;
    }

    /** 网站图标：先显示网站名的首字（点缀色），服务器有这个网站的图标再换上；domain 为空（Google 新闻）时只有首字。 */
    private View siteIcon(String site, String domain) {
        FrameLayout box = new FrameLayout(this);
        box.setBackground(t.round((accent & 0x00FFFFFF) | (t.night ? 0x38000000 : 0x1F000000), Theme.R_XS));
        box.setClipToOutline(true);
        TextView letter = t.text(this, site.isEmpty() ? "" : site.substring(0, 1).toUpperCase(Locale.ROOT), 12.5f,
                accent, 800);
        letter.setGravity(Gravity.CENTER);
        box.addView(letter, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (!domain.isEmpty()) Images.load(this, "/v1/icons/" + domain, b -> {
            ImageView image = new ImageView(this);
            image.setImageBitmap(b);
            ((GradientDrawable) box.getBackground()).setColor(Color.WHITE); // 图标多是按白底画的，透明处垫白
            box.removeView(letter);
            box.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        });
        return box;
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
        shape(follow).morph(t.dp(following ? Theme.R_M : 99), following ? t.ink : t.frostButton);
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
