package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 首页的信息流列表：按见报时间从新到旧、按天分组，每天先是“最值得看”的几件（见 top），其余按时间排；最上面是追踪中，
 * “未追踪信息”入口在第一天的最后。点开看详情（卡片原地展开），右滑标记不需要（追踪中的行右滑是取消追踪、
 * 答案不再显示），移走和撤销时行平滑收起、展开。每一种行长什么样见 FeedRows；数据由首页给，移走以后怎么办由首页决定（见 Host）。
 */
final class FeedList {
    interface Host {
        /** 首页正停在信息流这一页、在前台：列表只为看得见的行取视图，这时取的才记成看过。 */
        boolean showing();

        /** 这一行已经收起（右滑或在详情页点了“不需要”）：从数据里藏起来、给出撤销。 */
        void dismissed(long id);

        /** 追踪中的一行右滑后已经收起：从数据里拿掉。answer 为 true 是有了答案的问题，否则是追踪的事件。 */
        void left(boolean answer, long id);

        /** 点了“未追踪信息”入口。 */
        void skipped();
    }

    private static final double TOP_RATIO = 0.7; // 最值得看：前 TOP_MIN 件之后，分数不低于第 TOP_MIN 名的七成才接着放
    private static final int TOP_MIN = 3, TOP_MAX = 5;
    private static final String ANSWER = "answer"; // 追踪中的行里，“你问过的问题有答案了”用这个 label 区分

    private static final class Row {
        // 0 日期，1 最值得看（label 是编号），2 按时间的行，3 追踪中（追踪的事件和有了答案的问题），4 小标题，
        // 5 “未追踪信息”入口
        final int type;
        final CharSequence label;
        final JSONObject e;

        Row(int type, CharSequence label, JSONObject e) {
            this.type = type;
            this.label = label;
            this.e = e;
        }
    }

    private final Activity a;
    private final Theme t;
    private final ListView list;
    private final Host host;
    private final FeedRows ui;
    private final List<Row> rows = new ArrayList<>();
    private final FeedAdapter adapter = new FeedAdapter();
    private long seenAtOpen;
    private final Set<Long> collapsing = new HashSet<>(); // 正在收起的行，列表重绑时别打断它的动画
    private final Set<String> leaving = new HashSet<>(); // 追踪中正在收起的行（按 rowKey）
    private long expanding = -1; // 撤销后要展开的那一行

    FeedList(Activity a, Theme t, ListView list, Host host) {
        this.a = a;
        this.t = t;
        this.list = list;
        this.host = host;
        ui = Style.current().feedRows(a, t);
        list.setAdapter(adapter);
    }

    /** 上次离开首页的时间：这之后出现的、推送过的算新的。 */
    void seenAt(long ms) {
        seenAtOpen = ms;
    }

    /** 下一次重排时把 id 这一行从无到有展开（撤销移除）；这一轮排版没轮到它（不在屏幕上）就不展开了，免得以后滚到时才突然动。 */
    void expand(long id) {
        expanding = id;
        list.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                list.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                expanding = -1;
            }
        });
    }

    /**
     * 列表里显示这件事的那张卡片：行会被复用，关详情页时按 id 重新找。同一件事可能既在“追踪中”又在下面的时间线里，
     * follow 说明要找的是哪一种。
     */
    private Card.Anchor rowAnchor(long id, boolean follow) {
        return () -> {
            for (int i = 0; i < list.getChildCount(); i++) {
                Object tag = list.getChildAt(i).getTag();
                if (tag instanceof FeedRows.Holder) {
                    FeedRows.Holder h = (FeedRows.Holder) tag;
                    if (h.id == id && h.follow == follow) return h.front;
                }
            }
            return null;
        };
    }

    /** 按 feed（第一页）和 olderEvents（往前翻页加载的）重排，hidden 里的不显示。 */
    void render(JSONObject feed, List<JSONObject> olderEvents, Set<Long> hidden) {
        // 往下翻过的时候，新内容插在上面也让正在看的那一行留在原地
        String keep = null;
        int keepTop = 0;
        if (list.getFirstVisiblePosition() > 0) {
            for (int i = 0; i < list.getChildCount() && keep == null; i++) {
                int at = list.getFirstVisiblePosition() + i - list.getHeaderViewsCount();
                if (at >= 0 && at < rows.size() && rows.get(at).e != null) {
                    keep = rowKey(rows.get(at));
                    keepTop = list.getChildAt(i).getTop();
                }
            }
        }
        List<JSONObject> events = new ArrayList<>();
        JSONArray first = feed.optJSONArray("events");
        for (int i = 0; first != null && i < first.length(); i++) events.add(first.optJSONObject(i));
        events.addAll(olderEvents);

        rows.clear();
        JSONArray follows = feed.optJSONArray("following"), answers = feed.optJSONArray("answers");
        int followCount = follows == null ? 0 : follows.length(), answerCount = answers == null ? 0 : answers.length();
        if (followCount + answerCount > 0) rows.add(new Row(4, "追踪中", null));
        for (int i = 0; i < answerCount; i++) rows.add(new Row(3, ANSWER, answers.optJSONObject(i)));
        for (int i = 0; i < followCount; i++) rows.add(new Row(3, null, follows.optJSONObject(i)));
        String day = null;
        List<JSONObject> list = new ArrayList<>();
        int skipAt = -1; // “未追踪信息”入口放在第一天的最后
        for (JSONObject e : events) { // 服务器已按见报时间从新到旧排好
            if (e == null || hidden.contains(e.optLong("id"))) continue;
            String d = MainActivity.dayLabel(Theme.millis(e.optString("time")));
            if (!d.equals(day) && day != null) {
                addDay(list);
                if (skipAt < 0) skipAt = rows.size();
            }
            day = d;
            list.add(e);
        }
        if (!list.isEmpty()) addDay(list);
        JSONArray skipped = feed.optJSONArray("skipped");
        if (skipped != null && skipped.length() > 0) rows.add(skipAt < 0 ? rows.size() : skipAt, new Row(5, null, null));
        adapter.notifyDataSetChanged();
        for (int i = 0; keep != null && i < rows.size(); i++) {
            if (rows.get(i).e != null && keep.equals(rowKey(rows.get(i)))) {
                this.list.setSelectionFromTop(i + this.list.getHeaderViewsCount(), keepTop);
                break;
            }
        }
    }

    /** 一行是哪一种、哪一件：追踪行、答案行和时间线里的同一件事不能混。 */
    private static String rowKey(Row r) {
        return r.type + (ANSWER.equals(r.label) ? "a" : "") + ":" + r.e.optLong("id");
    }

    /** 一天：标题，最值得看的几件，其余按时间从新到旧。 */
    private void addDay(List<JSONObject> list) {
        rows.add(new Row(0, MainActivity.dayTitle(a, t, Theme.millis(list.get(0).optString("time"))), null));
        List<JSONObject> top = top(list);
        if (!top.isEmpty()) rows.add(new Row(4, "最值得看", null));
        for (int i = 0; i < top.size(); i++) rows.add(new Row(1, String.format(Locale.ROOT, "%02d", i + 1), top.get(i)));
        if (!top.isEmpty() && list.size() > top.size()) rows.add(new Row(4, "其余按时间", null));
        for (JSONObject e : list) {
            if (!top.contains(e)) rows.add(new Row(2, null, e));
        }
        list.clear();
    }

    /**
     * 一天里最值得看的：重要性 4 以上按服务器综合分排，前 TOP_MIN 件都算（不够就有几件算几件），之后不低于第 TOP_MIN 名的
     * TOP_RATIO 的接着放，最多 TOP_MAX 件。第一名常常远远领先，只和它比的话多半只剩一件。
     * 分数随时间按同样的比例衰减，同一天里谁比谁高多少不会变，前排不会过几个小时自己换人。
     */
    private static List<JSONObject> top(List<JSONObject> day) {
        List<JSONObject> key = new ArrayList<>();
        for (JSONObject e : day) {
            if (e.optInt("importance") >= 4) key.add(e);
        }
        key.sort((a, b) -> Double.compare(b.optDouble("score"), a.optDouble("score")));
        int n = Math.min(key.size(), TOP_MIN);
        while (n < Math.min(key.size(), TOP_MAX)
                && key.get(n).optDouble("score") >= TOP_RATIO * key.get(TOP_MIN - 1).optDouble("score")) n++;
        return key.subList(0, n);
    }

    /** 离开期间出现的，或离开期间推送过的（推送时可能没弹出来，回到首页要能找到）。 */
    private boolean isNew(JSONObject e) {
        return seenAtOpen > 0 && Math.max(Theme.millis(e.optString("first_seen")),
                Theme.millis(e.optString("pushed_at"))) > seenAtOpen;
    }

    private final class FeedAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            Row r = rows.get(i);
            if (r.e == null) return -i - 1;
            if (ANSWER.equals(r.label)) return -2_000_000L - r.e.optLong("id");
            return r.type == 3 ? -1_000_000L - r.e.optLong("id") : r.e.optLong("id"); // 追踪的事件也可能出现在下面
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public int getViewTypeCount() {
            return 6;
        }

        @Override
        public int getItemViewType(int i) {
            return rows.get(i).type;
        }

        @Override
        public boolean isEnabled(int i) {
            return false; // 点击由每一行自己处理，便于和右滑共存
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Row r = rows.get(i);
            if (r.type == 0 || r.type == 4) {
                TextView label = convert != null ? (TextView) convert : r.type == 0 ? ui.sectionLabel() : ui.subLabel();
                label.setText(r.label);
                return label;
            }
            if (r.type == 5) return convert != null ? convert : ui.skippedEntry(v -> host.skipped());
            // 追踪中、最值得看和按时间的行是分段列表：一组的头尾用大圆角
            boolean first = rows.get(i - 1).type != r.type, last = i == rows.size() - 1 || rows.get(i + 1).type != r.type;
            if (r.type == 3) {
                if (convert == null) convert = ui.newFollowRow();
                FeedRows.Holder h = (FeedRows.Holder) convert.getTag();
                boolean answer = ANSWER.equals(r.label);
                if (!leaving.contains(rowKey(r))) {
                    SwipeDismiss.reset(h.front, h.behind);
                    SwipeDismiss.unfold(convert);
                }
                if (answer) ui.bindAnswer(h, r.e, first, last);
                else ui.bindFollow(h, r.e, first, last, fresh(r.e));
                h.front.setOnClickListener(v -> {
                    if (answer) openAnswer(r.e);
                    else openFollow(v, r.e.optLong("id"));
                });
                h.front.setOnTouchListener(new SwipeDismiss(h.front, h.behind, v -> leaveFollow((View) v.getParent(), r)));
                return convert;
            }
            if (convert == null) convert = r.type == 1 ? ui.newTop() : ui.newRow();
            FeedRows.Holder h = (FeedRows.Holder) convert.getTag();
            long id = r.e.optLong("id");
            if (!collapsing.contains(id)) { // 收起动画还在走的那一行不动它
                SwipeDismiss.reset(h.front, h.behind);
                SwipeDismiss.unfold(convert);
            }
            h.id = id;
            if (r.type == 1) ui.bindTop(h, r.e, r.label, isNew(r.e));
            else ui.bindRow(h, r.e, isNew(r.e), first, last);
            if (host.showing()) Track.seen(a, id, r.type == 1 ? "top" : "feed"); // 列表只为看得见的行取视图
            // 单击抬手就打开（不等着确认是不是双击）；右滑照常交给 SwipeDismiss
            h.front.setOnClickListener(v -> open(h.front, r.e, r.type == 1));
            h.front.setOnTouchListener(new SwipeDismiss(h.front, h.behind, v -> dismissRow(id)));
            if (id == expanding) { // 撤销移除：内容绑好以后量出高度，从无到有展开
                expanding = -1;
                if (convert.getLayoutParams() == null) {
                    convert.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                SwipeDismiss.expand(convert, parent.getWidth());
            }
            return convert;
        }
    }

    /**
     * 这张卡片原地展开成详情页：带上列表里的数据，详情页一开始就有内容。最值得看的彩色卡片还带上它的颜色和有没有封面，
     * 详情页的头部接着用，展开、收回时颜色和封面一直在，不会中途变成一片白。
     */
    private void open(View card, JSONObject e, boolean top) {
        Intent i = new Intent(a, EventActivity.class)
                .putExtra(EventActivity.EXTRA_ID, e.optLong("id"))
                .putExtra(EventActivity.EXTRA_JSON, e.toString())
                .putExtra(EventActivity.EXTRA_FROM, "feed");
        if (top) {
            JSONObject look = ui.first(e);
            boolean cover = look.optBoolean("cover");
            String topic = Theme.topicOf(look);
            i.putExtra(EventActivity.EXTRA_TONE, ui.tone(look, cover, topic))
                    .putExtra(EventActivity.EXTRA_COVER, cover)
                    .putExtra(EventActivity.EXTRA_ART, topic.isEmpty() ? Theme.PEARL : topic);
        }
        Card.open(a, card, rowAnchor(e.optLong("id"), false), i, MainActivity.REQ_EVENT);
    }

    /** 右滑或在详情页点了“不需要”：这一行平滑收起、下面的跟上来，收完再真正移走。 */
    void dismissRow(long id) {
        View card = id < 0 ? null : rowAnchor(id, false).find();
        View row = card == null ? null : (View) card.getParent();
        if (row == null || !row.isShown()) {
            host.dismissed(id);
            return;
        }
        collapsing.add(id);
        SwipeDismiss.collapse(row, () -> {
            collapsing.remove(id);
            host.dismissed(id);
        });
    }

    /**
     * 右滑追踪中的一行：追踪的事件取消追踪，有了答案的问题不再显示（服务器上记成不用再留意）。
     * 这一行平滑收起，收完从列表里拿掉；没有撤销，取消追踪的可以在详情页重新追踪。
     */
    private void leaveFollow(View row, Row r) {
        boolean answer = ANSWER.equals(r.label);
        long id = r.e.optLong("id");
        String key = rowKey(r);
        leaving.add(key);
        SwipeDismiss.collapse(row, () -> {
            leaving.remove(key);
            host.left(answer, id);
        });
        Trace.log(a, answer ? "ui.answer.drop" : "ui.unfollow", "id=" + id);
        Context app = a.getApplicationContext();
        new Thread(() -> {
            try {
                if (answer) Api.post(app, "/v1/questions/" + id, new JSONObject().put("drop", true));
                else Api.post(app, "/v1/events/" + id + "/follow", new JSONObject().put("on", false));
            } catch (Exception err) {
                Trace.log(app, "follow.leave.fail", "id=" + id + " " + err);
            }
        }, "follow").start();
    }

    /** 列表里的这件事，不在列表里返回 null。 */
    JSONObject findEvent(long id) {
        for (Row r : rows) {
            if (r.e != null && r.e.optLong("id") == id) return r.e;
        }
        return null;
    }

    /** 追踪的事件离开期间有了新进展。 */
    private boolean fresh(JSONObject f) {
        return f.optInt("update_count") > 0 && seenAtOpen > 0 && Theme.millis(f.optString("updated")) > seenAtOpen;
    }

    /** 追踪中的事件原地展开成详情页。 */
    private void openFollow(View card, long id) {
        Card.open(a, card, rowAnchor(id, true), new Intent(a, EventActivity.class).putExtra(EventActivity.EXTRA_ID, id)
                .putExtra(EventActivity.EXTRA_FROM, "following"), MainActivity.REQ_EVENT);
    }

    /** 有了答案的问题：回到当时的对话。 */
    private void openAnswer(JSONObject q) {
        Push.open(a, new Intent(a, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_THREAD, q.optLong("thread_id"))
                .putExtra(ChatActivity.EXTRA_EVENT, q.optLong("event_id", -1))
                .putExtra(ChatActivity.EXTRA_TITLE, q.optString("question")));
    }
}
