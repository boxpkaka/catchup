package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 事件详情：话题色的头部（标题、来源数、看原文），下面是概要和要点、进展、问一问、全部来源。
 * 底部悬浮工具栏：有用 / 不需要，旁边是酸橙色的问一问。
 * 从列表进入时先用列表里的数据立即显示，再从服务器取完整来源列表。
 */
public class EventActivity extends Activity {
    static final String EXTRA_ID = "id";
    static final String EXTRA_JSON = "json";
    static final String EXTRA_DISMISSED = "dismissed";

    private static final int FOLDED_SOURCES = 4;

    private Theme t;
    private long id;
    private JSONObject event;
    private LinearLayout page, head, body, sources, actions;
    private TextView useful, follow;
    private int topInset;
    private String feedback;
    private boolean following;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        t = new Theme(this);
        t.applyWindow(this);
        id = getIntent().getLongExtra(EXTRA_ID, -1);
        setContentView(build());
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
        super.finish();
        overridePendingTransition(R.anim.hold, R.anim.exit_down);
    }

    @Override
    public void onBackPressed() {
        if (isTaskRoot()) startActivity(new Intent(this, MainActivity.class)); // 从通知直接进来时，返回到首页
        super.onBackPressed();
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, 0, 0, t.dp(120));
        head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        page.addView(head);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(t.dp(22), t.dp(4), t.dp(22), 0);
        page.addView(body);
        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // 按钮下面垫渐隐底，不用阴影：顶部的等话题色页头滚出去才出现，免得盖住页头
        View topFade = t.fade(this, false);
        topFade.setAlpha(0f);
        FrameLayout.LayoutParams tfl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(80));
        root.addView(topFade, tfl);
        View bottomFade = t.fade(this, true);
        FrameLayout.LayoutParams bfl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(132),
                Gravity.BOTTOM);
        root.addView(bottomFade, bfl);
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> topFade.setAlpha(
                Math.max(0f, Math.min(1f, (y - head.getHeight() + topFade.getHeight()) / (float) t.dp(32)))));

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

        // 悬浮工具栏：反色胶囊里放“有用 / 不需要”，问一问单独做成酸橙色的大按钮
        actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(t.dp(6), t.dp(6), t.dp(6), t.dp(6));
        bar.setBackground(t.round(t.inv, 99));
        useful = action("有用", v -> toggleUseful());
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
                head.setPadding(t.dp(22), topInset + t.dp(68), t.dp(22), t.dp(26));
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

    private void render(JSONObject e) {
        event = e;
        feedback = e.isNull("feedback") ? null : e.optString("feedback");
        renderHead(e);
        body.removeAllViews();

        String summary = e.optString("summary");
        if (!summary.isEmpty()) {
            TextView s = t.text(this, summary, 17, t.ink, 400);
            s.setLineSpacing(t.dp(9), 1f);
            s.setPadding(0, t.dp(22), 0, 0);
            body.addView(s);
        }
        JSONArray points = e.optJSONArray("points"); // 简报要点：原文里的具体内容，详情接口才有
        for (int i = 0; points != null && i < points.length(); i++) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, t.dp(i == 0 ? 14 : 10), 0, 0);
            View dot = new View(this);
            dot.setBackground(new Shapes.Blob(Shapes.SOFT, t.pri));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(t.dp(14), t.dp(14));
            dl.topMargin = t.dp(5);
            row.addView(dot, dl);
            TextView p = t.text(this, points.optString(i), 15.5f, t.ink, 400);
            p.setLineSpacing(t.dp(7), 1f);
            p.setPadding(t.dp(12), 0, 0, 0);
            row.addView(p, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            body.addView(row);
        }

        JSONArray updates = e.optJSONArray("updates");
        if (updates != null && updates.length() > 0) {
            body.addView(sectionLabel("进展"));
            for (int i = 0; i < updates.length(); i++) {
                body.addView(updateRow(updates.optJSONObject(i), i == 0, i == updates.length() - 1));
            }
        }

        body.addView(askSection(e));

        body.addView(sectionLabel("来源"));
        sources = new LinearLayout(this);
        sources.setOrientation(LinearLayout.VERTICAL);
        body.addView(sources);
        JSONArray signals = e.optJSONArray("signals");
        if (signals == null) {
            TextView wait = t.text(this, "正在加载来源…", 14, t.sub, 400);
            wait.setPadding(t.dp(16), t.dp(14), t.dp(16), t.dp(14));
            sources.addView(wait);
        } else {
            showSources(signals, signals.length() <= FOLDED_SOURCES + 1);
        }
        t.segments(sources);
        following = e.optBoolean("following");
        updateActions();
    }

    /** 头部：话题色延伸到状态栏下面，底部两个大圆角；从首页点进来时颜色和卡片一样，不跳变。 */
    private void renderHead(JSONObject e) {
        String topic = Theme.topicOf(e);
        int[] c = t.topic(topic);
        head.removeAllViews();
        head.setBackground(t.round(c[0], 0, 0, 44, 44));
        head.setPadding(t.dp(22), topInset + t.dp(68), t.dp(22), t.dp(26));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (!topic.isEmpty()) { // 主话题标签，和首页卡片一致
            TextView chip = t.text(this, topic, 12, c[1], 700);
            chip.setPadding(t.dp(11), t.dp(4), t.dp(11), t.dp(4));
            chip.setBackground(t.round((c[1] & 0x00FFFFFF) | 0x1C000000, 99));
            top.addView(chip);
        }
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (e.optInt("importance") >= 5) {
            TextView badge = t.text(this, "要闻", 12, t.onPri, 800);
            badge.setPadding(t.dp(10), t.dp(4), t.dp(10), t.dp(4));
            badge.setBackground(t.round(t.pri, 99));
            top.addView(badge);
        }
        head.addView(top);

        TextView headline = t.text(this, e.optString("headline"), 27, c[1], 900);
        headline.setLineSpacing(t.dp(7), 1f);
        headline.setLetterSpacing(-0.01f);
        headline.setPadding(0, t.dp(14), 0, 0);
        head.addView(headline);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(0, t.dp(16), 0, 0);
        TextView meta = t.text(this, e.optInt("source_count") + " 个来源   最近 "
                + Theme.ago(Theme.millis(e.optString("time"))), 13, c[1], 700);
        meta.setTypeface(Theme.flex(this, 92, 700));
        metaRow.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView original = t.text(this, "看原文 ↗", 13, c[0], 700);
        original.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(8));
        original.setBackground(t.ripple(c[1], 99));
        original.setOnClickListener(v -> openUrl(e.optString("url")));
        metaRow.addView(original);
        head.addView(metaRow);
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

    /** 问一问：建议问题直接发出；有过对话的可以接着聊。 */
    private View askSection(JSONObject e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionLabel("问一问"));
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
        return box;
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
        i.putExtra(ChatActivity.EXTRA_TOPIC, Theme.topicOf(event));
        if (question != null) i.putExtra(ChatActivity.EXTRA_ASK, question);
        startActivity(i);
        overridePendingTransition(R.anim.enter_up, R.anim.hold);
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

    /** 选中的按钮换形状：“有用”的底色收成靠内侧的小圆角，追踪从胶囊变成墨色小圆角方块。 */
    private void updateActions() {
        boolean on = "useful".equals(feedback);
        useful.setText(on ? "已标记有用" : "有用");
        useful.setBackground(t.ripple(on ? (t.onInv & 0x00FFFFFF) | 0x29000000 : Color.TRANSPARENT,
                99, on ? Theme.R_M - 2 : 99, on ? Theme.R_M - 2 : 99, 99));
        follow.setText(following ? "✓ 追踪中" : "+ 追踪");
        follow.setTextColor(following ? t.bg : t.ink);
        shape(follow).morph(t.dp(following ? Theme.R_M : 99), following ? t.ink : t.sheet);
    }

    private void toggleUseful() {
        String value = "useful".equals(feedback) ? null : "useful";
        feedback = value;
        updateActions();
        useful.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        send(value);
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
