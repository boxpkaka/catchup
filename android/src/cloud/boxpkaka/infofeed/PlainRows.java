package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.json.JSONObject;

/**
 * 朴素风格（验证用，见 Style）的信息流：“最值得看”不是彩色大卡片，而是一张平涂的紧凑卡片——左边是编号，右边是标题、概要和
 * “时间 · 话题 · 来源”，有封面的最右边是小图；不铺封面的颜色，也不流动。其余几种行和雾面金属一样，材质由 PlainTheme 换成平涂。
 */
final class PlainRows extends FeedRows {
    PlainRows(Activity a, Theme t) {
        super(a, t);
    }

    @Override
    View newTop() {
        Holder h = new Holder();
        LinearLayout row = new LinearLayout(a);
        row.setPadding(t.dp(16), t.dp(16), t.dp(18), t.dp(16));
        h.num = t.text(a, "", 22, t.pri, 800);
        h.num.setTypeface(Theme.flex(a, 135, 800));
        row.addView(h.num, new LinearLayout.LayoutParams(t.dp(40), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        h.label = newLabel("新");
        head.addView(h.label, gapLp(6));
        h.badge = newsBadge();
        head.addView(h.badge);
        col.addView(head);
        h.headline = t.text(a, "", 16.5f, t.ink, 800);
        h.headline.setLineSpacing(t.dp(4), 1f);
        h.headline.setMaxLines(3);
        h.headline.setEllipsize(TextUtils.TruncateAt.END);
        h.headline.setPadding(0, t.dp(2), 0, 0);
        col.addView(h.headline);
        h.summary = t.text(a, "", 13.5f, t.sub, 400);
        h.summary.setLineSpacing(t.dp(4), 1f);
        h.summary.setMaxLines(2);
        h.summary.setEllipsize(TextUtils.TruncateAt.END);
        h.summary.setPadding(0, t.dp(4), 0, 0);
        col.addView(h.summary);
        h.meta = t.text(a, "", 12, t.sub, 600);
        h.meta.setTypeface(Theme.flex(a, 92, 600));
        h.meta.setSingleLine();
        h.meta.setEllipsize(TextUtils.TruncateAt.END);
        h.meta.setPadding(0, t.dp(6), 0, 0);
        col.addView(h.meta);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        h.cover = newCover(row, 56);

        FrameLayout frame = swipeFrame(h, row);
        frame.setPadding(t.dp(12), t.dp(4), t.dp(12), t.dp(4));
        return frame;
    }

    @Override
    void bindTop(Holder h, JSONObject e, CharSequence num, boolean fresh) {
        h.front.setBackground(t.ripple(t.sheet, Theme.R_XL));
        h.behind.setBackground(t.round(t.err, Theme.R_XL));
        h.num.setText(num);
        h.label.setVisibility(fresh ? View.VISIBLE : View.GONE);
        h.badge.setVisibility(e.optInt("importance") >= 5 ? View.VISIBLE : View.GONE);
        h.headline.setText(e.optString("headline"));
        show(h.summary, e.optString("summary"));
        h.meta.setText(meta(MainActivity.clock(Theme.millis(e.optString("time"))) + " · ", e, t.ink));
        bindCover(h, e);
    }
}
