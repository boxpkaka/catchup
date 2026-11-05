package cloud.boxpkaka.infofeed;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 信息流里每一种行长什么样：每天的标题和小标题；“最值得看”一件一张雾面金属的彩色大卡片、带编号（见 newTop）；其余按时间排成
 * 一条线，不上色：重要性 4 以上加粗，3 以上带一行概要，其他只有标题，服务器已经取到封面的右边带小图，话题写在来源前面；
 * 追踪中的一组（追踪的事件、有了答案的问题）；“未追踪信息”入口。行里的颜色只表示状态：钴蓝是新的，墨色反白的“要闻”是最重要的。
 * 这里只管建和绑：哪一行放什么、点按、右滑、收起和展开由 FeedList 接上。这是雾面金属的样子，别的风格覆盖其中几种行
 * （见 Style.feedRows）。
 */
class FeedRows {
    final Activity a;
    final Theme t;
    private final Map<Long, JSONObject> firstSeen = new HashMap<>(); // 见 first

    FeedRows(Activity a, Theme t) {
        this.a = a;
        this.t = t;
    }

    static final class Holder {
        long id = -1; // 显示的是哪件事，追踪行和答案行以外的分组标题等都是 -1
        boolean follow; // 追踪中的行（追踪的事件和有了答案的问题）
        View front, behind;
        TextView label, badge, num, headline, summary, meta, time;
        ImageView cover;
        Flow flow; // 最值得看的卡片底下流动的模糊封面
    }

    /** 每天的标题：大字，是这一天的起点。 */
    TextView sectionLabel() {
        TextView v = t.text(a, "", 26, t.ink, 900);
        v.setPadding(t.dp(22), t.dp(26), t.dp(22), t.dp(2));
        return v;
    }

    /** 小标题：追踪中、最值得看、其余按时间。 */
    TextView subLabel() {
        TextView v = t.text(a, "", 13, t.sub, 700);
        v.setPadding(t.dp(24), t.dp(12), t.dp(24), t.dp(8));
        return v;
    }

    /** 右滑时露出来的警示色底：圆角和这一段的头尾一样；text 不为空时换掉“✕ 不需要”。 */
    private void behind(Holder h, boolean first, boolean last, String text) {
        float top = first ? Theme.R_XL : Theme.R_XS, bottom = last ? Theme.R_XL : Theme.R_XS;
        h.behind.setBackground(t.round(t.err, top, top, bottom, bottom));
        if (text != null) ((TextView) h.behind).setText(text);
    }

    /** 可以右滑的一项：后面垫着警示色的“✕ 不需要”（追踪中的行绑定时换成“取消追踪”“不用了”），从左边露出来。 */
    FrameLayout swipeFrame(Holder h, View front) {
        FrameLayout frame = new FrameLayout(a) {
            /** 右滑归这一行（移除），首页不拿它拉橡皮筋。 */
            @Override
            public boolean canScrollHorizontally(int direction) {
                return direction < 0 || super.canScrollHorizontally(direction);
            }
        };
        TextView behind = t.text(a, "✕  不需要", 15, t.onErr, 800);
        behind.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        behind.setPadding(t.dp(26), 0, 0, 0);
        behind.setVisibility(View.INVISIBLE);
        frame.addView(behind, match());
        frame.addView(front, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        h.front = front;
        h.behind = behind;
        frame.setTag(h);
        return frame;
    }

    /**
     * 最值得看的一件：一张雾面金属的大卡片（Flow）。有封面的最上面是铺满卡片宽度的大图，下面一段渐渐透明、融进流动的模糊封面，
     * 编号那一行压在融开的地方；没有封面的底下是话题色生成的抽象封面，不放徽记。往下是大字标题、概要和“时间 · 话题 · 来源”。
     * 颜色见 tone。
     */
    View newTop() {
        Holder h = new Holder();
        FrameLayout front = new FrameLayout(a); // 点按、右滑、展开都认它；里面的内容不接点按
        h.flow = new Flow(a);
        h.flow.corners(front);
        front.addView(h.flow, match());
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        front.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        RoundImage cover = new RoundImage(a, t.dp(Theme.R_XL)); // 和卡片一样的圆角，上面两个角贴着卡片的角
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.fadeBottom(0.45f);
        h.cover = cover;
        int width = a.getResources().getDisplayMetrics().widthPixels - t.dp(12 * 2);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, width * 9 / 16); // 高度先定好，图下来时卡片不跳
        cl.bottomMargin = -t.dp(22);
        card.addView(cover, cl);

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(t.dp(20), t.dp(16), t.dp(20), t.dp(18));
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        h.num = t.text(a, "", 28, t.ink, 800);
        h.num.setTypeface(Theme.flex(a, 135, 800));
        head.addView(h.num, gapLp(10));
        h.label = newLabel("新");
        head.addView(h.label, gapLp(6));
        h.badge = newsBadge();
        head.addView(h.badge);
        head.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        head.setMinimumHeight(t.dp(36));
        body.addView(head);
        h.headline = t.text(a, "", 19, t.ink, 800);
        h.headline.setLineSpacing(t.dp(4), 1f);
        h.headline.setPadding(0, t.dp(10), 0, 0);
        body.addView(h.headline);
        h.summary = t.text(a, "", 14.5f, t.sub, 400);
        h.summary.setLineSpacing(t.dp(5), 1f);
        h.summary.setEllipsize(TextUtils.TruncateAt.END);
        h.summary.setPadding(0, t.dp(8), 0, 0);
        body.addView(h.summary);
        h.meta = t.text(a, "", 12.5f, t.sub, 600);
        h.meta.setTypeface(Theme.flex(a, 92, 600));
        h.meta.setSingleLine();
        h.meta.setEllipsize(TextUtils.TruncateAt.END);
        h.meta.setPadding(0, t.dp(12), 0, 0);
        body.addView(h.meta);
        card.addView(body);

        FrameLayout frame = swipeFrame(h, front);
        frame.setPadding(t.dp(12), t.dp(5), t.dp(12), t.dp(5));
        return frame;
    }

    /** 按时间的一行：左边是时间，离开期间出现的时间用主色；重要性 4 以上的标题加粗，3 以上带一行概要；有封面的右边是小图。 */
    View newRow() {
        Holder h = new Holder();
        LinearLayout row = new LinearLayout(a);
        row.setPadding(t.dp(18), t.dp(14), t.dp(18), t.dp(14));
        row.setClickable(true);

        h.time = t.text(a, "", 13, t.sub, 800);
        h.time.setTypeface(Theme.flex(a, 120, 800));
        h.time.setPadding(0, t.dp(2), 0, 0);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(t.dp(42), ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.rightMargin = t.dp(14);
        row.addView(h.time, tl);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        h.badge = newsBadge();
        LinearLayout.LayoutParams bl = gapLp(0);
        bl.bottomMargin = t.dp(6);
        col.addView(h.badge, bl);
        h.headline = t.text(a, "", 15, t.ink, 700);
        h.headline.setLineSpacing(t.dp(4), 1f);
        h.headline.setMaxLines(2);
        h.headline.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(h.headline);
        h.summary = t.text(a, "", 13.5f, t.sub, 400); // 简报概要，不用点开也知道主要内容
        h.summary.setLineSpacing(t.dp(4), 1f);
        h.summary.setSingleLine();
        h.summary.setEllipsize(TextUtils.TruncateAt.END);
        h.summary.setPadding(0, t.dp(3), 0, 0);
        col.addView(h.summary);
        h.meta = t.text(a, "", 12, t.sub, 600);
        h.meta.setTypeface(Theme.flex(a, 92, 600));
        h.meta.setPadding(0, t.dp(5), 0, 0);
        h.meta.setSingleLine();
        h.meta.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(h.meta);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        h.cover = newCover(row, 56);
        FrameLayout frame = swipeFrame(h, row);
        frame.setPadding(t.dp(12), t.dp(1.5f), t.dp(12), t.dp(1.5f));
        return frame;
    }

    /** 行右边的封面小图：圆角方图，图还没下来时是浅底。 */
    ImageView newCover(LinearLayout row, int sizeDp) {
        ImageView cover = new RoundImage(a, t.dp(Theme.R_S)); // 圆角画在图上，卡片收回时的截图里也是圆角
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(t.round(t.chip, Theme.R_S));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(t.dp(sizeDp), t.dp(sizeDp));
        lp.leftMargin = t.dp(12);
        lp.topMargin = t.dp(2);
        row.addView(cover, lp);
        return cover;
    }

    /**
     * 服务器已经取到封面的（推送前会取）放上图，没有的不占位置。有没有图由服务器先说好，图下来时行高不跳；
     * 有没有图按这件事这次第一次显示时的数据（见 first）。内存里已经有的直接放，要等的下来后淡入；
     * 行会复用，图回来时还是这件事才放上去；已经是这张图（可能正在淡入）的不打断。
     */
    void bindCover(Holder h, JSONObject e) {
        String path = first(e).optBoolean("cover") ? "/v1/events/" + e.optLong("id") + "/cover" : null;
        ImageView cover = h.cover;
        if (path != null && path.equals(cover.getTag())) return;
        cover.setTag(path);
        cover.setImageDrawable(null);
        cover.setImageAlpha(255);
        cover.setVisibility(path == null ? View.GONE : View.VISIBLE);
        if (path == null) return;
        int size = t.dp(56); // 小图按显示的大小解码，见 newCover
        Bitmap ready = Images.cached(path, size);
        if (ready != null) {
            cover.setImageBitmap(ready);
            return;
        }
        Images.load(a, path, size, b -> {
            if (!path.equals(cover.getTag())) return;
            cover.setImageAlpha(0);
            cover.setImageBitmap(b);
            ValueAnimator in = ValueAnimator.ofInt(0, 255);
            in.setDuration(320);
            in.setInterpolator(new DecelerateInterpolator(1.5f));
            in.addUpdateListener(a -> {
                if (path.equals(cover.getTag())) cover.setImageAlpha((int) a.getAnimatedValue());
            });
            in.start();
        });
    }

    /**
     * 这件事这次打开里第一次显示时的数据。有没有封面、卡片的颜色和徽记都按它来：之后服务器取到了封面、换了话题也不跟着变，
     * 免得正看着的卡片突然长出一张图、换了颜色（下次打开才用新的）。
     */
    JSONObject first(JSONObject e) {
        return firstSeen.computeIfAbsent(e.optLong("id"), k -> e);
    }

    /** 追踪中：有了答案的问题（主色容器）和追踪的事件（卡片色）放在一组。 */
    View newFollowRow() {
        Holder h = new Holder();
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(t.dp(20), t.dp(16), t.dp(20), t.dp(16));
        h.label = newLabel("");
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.bottomMargin = t.dp(8);
        card.addView(h.label, ll);
        h.headline = t.text(a, "", 15.5f, t.ink, 800);
        h.headline.setLineSpacing(t.dp(4), 1f);
        h.headline.setMaxLines(2);
        h.headline.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(h.headline);
        h.summary = t.text(a, "", 13.5f, t.sub, 400);
        h.summary.setLineSpacing(t.dp(3), 1f);
        h.summary.setMaxLines(2);
        h.summary.setEllipsize(TextUtils.TruncateAt.END);
        h.summary.setPadding(0, t.dp(4), 0, 0);
        card.addView(h.summary);
        h.meta = t.text(a, "", 12.5f, t.sub, 600);
        h.meta.setTypeface(Theme.flex(a, 90, 600));
        h.meta.setPadding(0, t.dp(8), 0, 0);
        card.addView(h.meta);
        h.follow = true;
        FrameLayout frame = swipeFrame(h, card);
        frame.setPadding(t.dp(12), t.dp(1.5f), t.dp(12), t.dp(1.5f));
        return frame;
    }

    /** 追踪的事件；fresh 是离开期间有了新进展。 */
    void bindFollow(Holder h, JSONObject f, boolean first, boolean last, boolean fresh) {
        h.front.setBackground(t.segment(t.sheet, first, last));
        behind(h, first, last, "✕  取消追踪");
        h.headline.setTextColor(t.ink);
        h.summary.setTextColor(t.sub);
        h.meta.setTextColor(t.sub);
        h.headline.setText(f.optString("headline"));
        String update = f.optString("update");
        show(h.summary, update);
        int n = f.optInt("update_count");
        h.meta.setText((n > 0 ? n + " 条进展，" : "") + "最近 " + Theme.ago(Theme.millis(f.optString("updated"))));
        show(h.label, fresh ? "新进展" : "");
        h.id = f.optLong("id");
    }

    /** 替你留意的问题有了答案：点开回到当时的对话，答案已经写在里面。 */
    void bindAnswer(Holder h, JSONObject q, boolean first, boolean last) {
        h.id = -1;
        h.front.setBackground(t.segment(t.pc, first, last));
        behind(h, first, last, "✕  不用了");
        h.headline.setTextColor(t.onPc);
        h.summary.setTextColor(t.onPc);
        h.meta.setTextColor(t.onPc);
        show(h.label, "有了答案");
        h.headline.setText("你问过：" + q.optString("question"));
        show(h.summary, q.optString("answer"));
        h.meta.setText(Theme.ago(Theme.millis(q.optString("answered"))));
    }

    /** 最值得看的一件：num 是编号，fresh 是离开期间出现的（标“新”）。 */
    void bindTop(Holder h, JSONObject e, CharSequence num, boolean fresh) {
        JSONObject look = first(e);
        h.behind.setBackground(t.round(t.err, Theme.R_XL)); // 一张张分开的卡片，卡片的底色在 paint 里按封面或话题上
        boolean cover = look.optBoolean("cover");
        String topic = Theme.topicOf(look);
        int[] z = tone(look, cover, topic);
        paint(h, z);
        bindFlow(h, e, cover, z, topic.isEmpty() ? Theme.PEARL : topic);
        h.num.setText(num);
        h.label.setVisibility(fresh ? View.VISIBLE : View.GONE);
        h.badge.setVisibility(e.optInt("importance") >= 5 ? View.VISIBLE : View.GONE);
        h.headline.setText(e.optString("headline"));
        h.summary.setMaxLines(cover ? 2 : 3);
        show(h.summary, e.optString("summary"));
        h.meta.setText(meta(MainActivity.clock(Theme.millis(e.optString("time"))) + " · ", e, z[2]));
        bindCover(h, e);
    }

    /**
     * 最值得看的卡片的颜色 {上, 下, 文字色, 点缀色}：有封面的照着封面铺渐变（Theme.cover，颜色随信息流一起来，卡片一出来
     * 就是这个颜色）；没有封面的用话题的金属配色（Theme.field，浅色模式也是深底白字），没有话题的是珍珠银。
     */
    int[] tone(JSONObject look, boolean cover, String topic) {
        JSONArray palette = look.optJSONArray("palette");
        if (cover && palette != null && palette.length() == 2) {
            return t.cover(Color.parseColor(palette.optString(0)), Color.parseColor(palette.optString(1)));
        }
        return t.field(topic.isEmpty() ? Theme.PEARL : topic);
    }

    /**
     * 卡片底下流动的雾面金属（Flow）：有封面的是模糊封面，封面已经模糊过或在内存里的直接上，要等的先是卡片色的渐变，
     * 图下来后过渡过去；没有封面的是 art 这个话题生成的抽象封面。行复用来显示同一件事时不重来，接着流。
     */
    private void bindFlow(Holder h, JSONObject e, boolean cover, int[] z, String art) {
        Flow flow = h.flow;
        long id = e.optLong("id");
        String path = "/v1/events/" + id + "/cover", tag = cover ? path : art + "|" + id;
        if (tag.equals(flow.getTag())) return;
        flow.setTag(tag);
        flow.gradient(z[0], z[1], Color.luminance(z[2]) > 0.5f, true, id);
        if (!cover) {
            flow.topic(t, art, id);
            return;
        }
        Bitmap blurred = Flow.cachedBlur(path), ready = blurred == null ? Images.cached(path) : null;
        if (blurred != null || ready != null) {
            flow.cover(blurred != null ? blurred : Flow.blurred(path, ready), false);
            return;
        }
        Images.load(a, path, b -> {
            if (path.equals(flow.getTag())) flow.cover(Flow.blurred(path, b), true);
        });
    }

    /** 最值得看的卡片上色：编号用点缀色，概要和来源是淡一些的文字色，“要闻”是文字色反白。点按不泛白光，直接展开。 */
    private void paint(Holder h, int[] z) {
        h.front.setBackground(t.gradient(z[0], z[1], Theme.R_XL));
        h.num.setTextColor(z[3]);
        h.headline.setTextColor(z[2]);
        h.summary.setTextColor(z[2] & 0xFFFFFF | 0xD0000000);
        h.meta.setTextColor(z[2] & 0xFFFFFF | 0xB8000000);
        h.badge.setTextColor(z[0]);
        h.badge.setBackground(t.round(z[2], Theme.R_XS));
    }

    /** 按时间的一行：fresh 是离开期间出现的（时间用主色）；first、last 是不是这一段的头尾（大圆角）。 */
    void bindRow(Holder h, JSONObject e, boolean fresh, boolean first, boolean last) {
        h.front.setBackground(t.segment(t.sheet, first, last));
        behind(h, first, last, null);
        boolean key = e.optInt("importance") >= 4;
        h.time.setText(MainActivity.clock(Theme.millis(e.optString("time"))));
        h.time.setTextColor(fresh ? t.pri : t.sub);
        h.badge.setVisibility(e.optInt("importance") >= 5 ? View.VISIBLE : View.GONE);
        h.headline.setText(e.optString("headline"));
        h.headline.setTextSize(key ? 15.5f : 15);
        h.headline.setTypeface(Theme.weight(key ? 800 : 500));
        show(h.summary, e.optInt("importance") >= 3 ? e.optString("summary") : "");
        h.meta.setText(meta("", e, t.ink));
        bindCover(h, e);
    }

    /** “大模型 · IT之家 等 3 家”：话题用 ink（行里是墨色，彩色卡片上是卡片的文字色）写在来源前面；prefix 是前面的时间。 */
    CharSequence meta(String prefix, JSONObject e, int ink) {
        SpannableStringBuilder s = new SpannableStringBuilder(prefix);
        String topic = Theme.topicOf(e);
        if (!topic.isEmpty()) s.append(topic, new ForegroundColorSpan(ink), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE).append(" · ");
        return s.append(sources(e));
    }

    /** “IT之家 等 3 家”：主要来源和报道的家数。 */
    private static String sources(JSONObject e) {
        JSONArray src = e.optJSONArray("sources");
        String lead = src != null && src.length() > 0 ? src.optString(0) : "";
        int n = e.optInt("source_count");
        return n > 1 ? lead + " 等 " + n + " 家" : lead;
    }

    /** 墨色反白的“要闻”：只给重要性 5 的事件。 */
    TextView newsBadge() {
        TextView v = t.text(a, "要闻", 11.5f, t.onInv, 800);
        v.setPadding(t.dp(8), t.dp(3), t.dp(8), t.dp(3));
        v.setBackground(t.metal(t.inv, Theme.R_XS));
        return v;
    }

    /** “未追踪信息”入口：四叶形 + 文字的胶囊，放在第一天的最后，不挡在第一条内容前面。 */
    View skippedEntry(View.OnClickListener open) {
        TextView v = t.text(a, "未追踪信息 ›", 13, t.ink, 600);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(t.dp(10), t.dp(9), t.dp(16), t.dp(9));
        Drawable clover = new Shapes.Blob(Shapes.CLOVER, t.sub);
        clover.setBounds(0, 0, t.dp(22), t.dp(22));
        v.setCompoundDrawablesRelative(clover, null, null, null);
        v.setCompoundDrawablePadding(t.dp(8));
        v.setBackground(t.frostRipple(t.frost, 99));
        v.setOnClickListener(open);
        FrameLayout frame = new FrameLayout(a);
        frame.setPadding(t.dp(12), t.dp(10), t.dp(12), t.dp(2));
        frame.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return frame;
    }

    /** 主色小标签：“新”“新进展”“有了答案”，一小块钴蓝金属。 */
    TextView newLabel(String text) {
        TextView v = t.text(a, text, 11.5f, t.onPri, 800);
        v.setPadding(t.dp(9), t.dp(3), t.dp(9), t.dp(3));
        v.setBackground(t.metal(t.pri, 99));
        return v;
    }

    LinearLayout.LayoutParams gapLp(int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = t.dp(rightDp);
        return lp;
    }

    static void show(TextView v, String s) {
        v.setText(s);
        v.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
