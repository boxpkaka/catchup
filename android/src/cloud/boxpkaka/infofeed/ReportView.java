package cloud.boxpkaka.infofeed;

import android.animation.LayoutTransition;
import android.app.Activity;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 首页的“报告”页：日报、周报、月报三页横向排开，左右滑时相邻的一页跟着手指进来（也可以点上面的分段按钮）。
 * 每类最新一期在最上面，往下滚到底就接着出上一期，一直往前看，不用点。要点、趋势、持续发展的事和分领域的条目
 * 点开从那一行展开成事件详情。
 * 报告由服务器在每个时间段结束后写好（见 catchup/pipeline/reports.py），这里只负责读和显示；第一次进来时三类一起加载，
 * 滑过去就已经有内容。
 */
final class ReportView extends FrameLayout {
    interface OnOpen {
        /** 从 from 这张卡片展开事件详情。 */
        void open(View from, long eventId);
    }

    private static final String[][] KINDS = {{"daily", "日报"}, {"weekly", "周报"}, {"monthly", "月报"}};

    private final Activity a;
    private final Theme t;
    private final OnOpen onOpen;
    private final ScrollView scroll;
    private final Swipe.Tabs kinds;
    private Swipe.Pager pager; // 分段按钮的点击要用，建按钮时它还没建好
    private final Kind[] pages = new Kind[KINDS.length];
    private boolean started;

    ReportView(Activity a, Theme t, OnOpen onOpen) {
        super(a);
        this.a = a;
        this.t = t;
        this.onOpen = onOpen;
        setBackgroundColor(t.bg);

        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(t.dp(12), t.dp(8), t.dp(12), 0);
        column.setClipChildren(false); // 相邻的一页从屏幕边上进来，不在内容边距处被切掉

        // 分段按钮：浅色底槽里一块主色滑块，翻页时跟着手指走
        kinds = new Swipe.Tabs(a, t.pri, t.onPri, t.ink);
        kinds.setPadding(t.dp(3), t.dp(3), t.dp(3), t.dp(3));
        kinds.setBackground(t.round(t.sheet, 99));
        for (int i = 0; i < KINDS.length; i++) {
            int index = i;
            TextView tab = t.text(a, KINDS[i][1], 14, t.ink, 700);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, t.dp(9), 0, t.dp(9));
            tab.setBackground(t.ripple(Color.TRANSPARENT, 99));
            tab.setOnClickListener(v -> pager.go(index));
            kinds.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        kinds.setCount(KINDS.length);
        column.addView(kinds);

        FrameLayout deck = new FrameLayout(a);
        for (int i = 0; i < KINDS.length; i++) {
            pages[i] = new Kind(KINDS[i][0]);
            pages[i].view.setVisibility(GONE);
            deck.addView(pages[i].view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        column.addView(deck);

        scroll = new ScrollView(a);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, 0, 0, t.dp(96));
        scroll.addView(column);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> more());
        pager = new Swipe.Pager(a, new Swipe.Pager.Host() {
            @Override
            public int count() {
                return KINDS.length;
            }

            @Override
            public View page(int index) {
                if (started) pages[index].showIfEmpty(); // 上次没加载成的，滑到时再试
                return pages[index].view;
            }

            @Override
            public void moved(float position) {
                kinds.at(position);
            }

            @Override
            public void settled(int index) {
                more();
            }
        }, false); // 两头交给首页翻页
        pager.scrollsWith(scroll, () -> 0);
        pager.frame.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        addView(pager.frame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        pager.jump(0);
    }

    void setBottomInset(int px) {
        scroll.setPadding(0, 0, 0, px);
    }

    /** 切到这一页时调用：还没加载过的类型都去加载最新一期。 */
    void showIfEmpty() {
        started = true;
        for (Kind k : pages) k.showIfEmpty();
    }

    /** 翻到这类报告，从 period 这一期看起（空是最新一期）。 */
    void show(String kind, String period) {
        started = true;
        for (int i = 0; i < KINDS.length; i++) {
            if (!KINDS[i][0].equals(kind)) continue;
            pager.jump(i);
            pages[i].show(period);
        }
    }

    /** 当前这类离底部不到一屏了：接上更早的一期。 */
    private void more() {
        View column = scroll.getChildAt(0);
        if (column.getHeight() - scroll.getScrollY() - scroll.getHeight() < scroll.getHeight()) {
            pages[pager.index()].more();
        }
    }

    /** 一类报告的一页：一期接一期往下排，最新的在最上面，自己加载。 */
    private final class Kind {
        final String kind;
        final LinearLayout view;
        String older; // 下一期要接上的往期，null 是到头了
        boolean loading, loaded;
        long failedAt;
        int loadSeq;

        Kind(String kind) {
            this.kind = kind;
            view = new LinearLayout(a);
            view.setOrientation(LinearLayout.VERTICAL);
            Theme.smooth(view); // 接上的往期淡入
        }

        void showIfEmpty() {
            if (!loaded && !loading) show(null);
        }

        /** 从 period 这一期看起（空是最新一期），往期接在下面。 */
        void show(String period) {
            loading = true;
            if (loaded) view.animate().alpha(0.4f).setDuration(120).start();
            fetch(period, ++loadSeq, true);
        }

        /** 滚到底了：接上更早的一期；上次失败的过一会儿再试。 */
        void more() {
            if (!loaded || loading || older == null || SystemClock.uptimeMillis() - failedAt < 10_000) return;
            loading = true;
            fetch(older, loadSeq, false);
        }

        private void fetch(String period, int seq, boolean fresh) {
            Trace.log(a, "ui.report", kind + " " + (period == null ? "latest" : period));
            new Thread(() -> {
                JSONObject r = null;
                Exception err = null;
                try {
                    r = Api.get(a, "/v1/reports/" + kind + (period == null ? "" : "/" + period));
                } catch (Exception e) {
                    err = e;
                    Trace.log(a, "report.fail", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                JSONObject got = r;
                Exception error = err;
                a.runOnUiThread(() -> {
                    if (a.isDestroyed() || seq != loadSeq) return; // 期间又换了从哪一期看起
                    loading = false;
                    if (got == null) {
                        failedAt = SystemClock.uptimeMillis();
                        if (fresh) failed(error, period);
                        return;
                    }
                    if (fresh) {
                        // 换了从哪一期看起：整页换掉时不逐条淡入淡出，整页一起淡入
                        LayoutTransition lt = view.getLayoutTransition();
                        view.setLayoutTransition(null);
                        view.removeAllViews();
                        append(got);
                        view.setLayoutTransition(lt);
                        view.setAlpha(loaded ? 0.4f : 0f);
                        view.animate().alpha(1f).setDuration(220).start();
                        loaded = true;
                        if (this == pages[pager.index()]) scroll.scrollTo(0, 0);
                    } else {
                        append(got);
                    }
                    scroll.post(ReportView.this::more); // 还没铺满一屏就接着往前接
                });
            }, "report-load").start();
        }

        /** 接上一期：期名做标题，下面是要点、趋势、持续发展的事和各领域。 */
        private void append(JSONObject r) {
            LinearLayout section = new LinearLayout(a);
            section.setOrientation(LinearLayout.VERTICAL);
            String period = r.isNull("period") ? null : r.optString("period");
            older = r.isNull("prev") ? null : r.optString("prev");
            if (period == null) { // 第一期还没出
                section.addView(note(r.optString("upcoming") + " 出第一期" + r.optString("name")));
                view.addView(section);
                return;
            }
            TextView label = t.text(a, r.optString("label"), 22, t.ink, 900);
            label.setPadding(t.dp(10), t.dp(view.getChildCount() == 0 ? 18 : 44), t.dp(10), t.dp(2));
            section.addView(label);

            JSONArray highlights = r.optJSONArray("highlights");
            if (highlights != null && highlights.length() > 0) {
                LinearLayout card = new LinearLayout(a);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(t.dp(8), t.dp(16), t.dp(8), t.dp(10));
                card.setBackground(t.round(t.pc, Theme.R_2XL));
                TextView title = t.text(a, "要点", 13, t.onPc, 800);
                title.setPadding(t.dp(12), 0, 0, t.dp(6));
                card.addView(title);
                for (int i = 0; i < highlights.length(); i++) card.addView(highlight(highlights.optJSONObject(i)));
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                cl.topMargin = t.dp(14);
                section.addView(card, cl);
            }
            notes(section, "趋势", r.optJSONArray("trends"));
            notes(section, "持续发展的事", r.optJSONArray("ongoing"));
            JSONArray sections = r.optJSONArray("sections");
            for (int i = 0; sections != null && i < sections.length(); i++) {
                JSONObject s = sections.optJSONObject(i);
                JSONArray items = s.optJSONArray("items");
                if (items == null || items.length() == 0) continue;
                section.addView(heading(s.optString("name")));
                LinearLayout list = new LinearLayout(a);
                list.setOrientation(LinearLayout.VERTICAL);
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.optJSONObject(j);
                    list.addView(row(item.optString("text"), item.optLong("id", -1)));
                }
                t.segments(list);
                section.addView(list);
            }
            view.addView(section);
        }

        private void failed(Exception e, String period) {
            view.animate().alpha(1f).setDuration(120).start();
            if (loaded) return; // 已有内容就留着，下次再试
            view.removeAllViews();
            TextView v = note("加载失败：" + MainActivity.reason(e) + "\n点这里重试");
            v.setOnClickListener(x -> show(period));
            view.addView(v);
        }
    }

    /** 要点里的一条：点开从这一行展开成它说的第一件事。 */
    private View highlight(JSONObject h) {
        TextView v = t.text(a, "· " + h.optString("text"), 15, t.onPc, 600);
        v.setLineSpacing(t.dp(5), 1f);
        v.setPadding(t.dp(12), t.dp(8), t.dp(12), t.dp(8));
        long id = firstEvent(h);
        if (id >= 0) {
            v.setBackground(t.ripple(Color.TRANSPARENT, Theme.R_M));
            v.setOnClickListener(x -> onOpen.open(x, id));
        }
        return v;
    }

    /** 趋势、持续发展的事：一组分段行，点开进提到的第一件事。 */
    private void notes(LinearLayout into, String title, JSONArray items) {
        if (items == null || items.length() == 0) return;
        into.addView(heading(title));
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < items.length(); i++) {
            JSONObject x = items.optJSONObject(i);
            list.addView(row(x.optString("text"), firstEvent(x)));
        }
        t.segments(list);
        into.addView(list);
    }

    private static long firstEvent(JSONObject x) {
        JSONArray events = x.optJSONArray("events");
        return events != null && events.length() > 0 ? events.optJSONObject(0).optLong("id", -1) : -1;
    }

    private View row(String text, long eventId) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(t.dp(18), t.dp(14), t.dp(14), t.dp(14));
        TextView v = t.text(a, text, 14.5f, t.ink, 500);
        v.setLineSpacing(t.dp(5), 1f);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (eventId >= 0) {
            row.addView(t.text(a, "›", 17, t.sub, 700));
            row.setOnClickListener(x -> onOpen.open(x, eventId));
        }
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView heading(String s) {
        TextView v = t.text(a, s, 18, t.ink, 800);
        v.setPadding(t.dp(10), t.dp(26), t.dp(10), t.dp(10));
        return v;
    }

    private TextView note(String s) {
        TextView v = t.text(a, s, 14.5f, t.sub, 500);
        v.setGravity(Gravity.CENTER);
        v.setPadding(t.dp(20), t.dp(48), t.dp(20), t.dp(48));
        return v;
    }
}
