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
 * 事件详情：标题、事实摘要、与你有关、为什么排在前面、全部来源。底部浮动操作条：有用 / 不需要 / 看原文。
 * 从列表进入时先用列表里的数据立即显示，再从服务器取完整来源列表。
 */
public class EventActivity extends Activity {
    static final String EXTRA_ID = "id";
    static final String EXTRA_JSON = "json";

    private Theme t;
    private long id;
    private JSONObject event;
    private LinearLayout body, sources, actions;
    private TextView useful, follow;
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
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(t.dp(22), t.dp(72), t.dp(22), t.dp(120));
        scroll.addView(body);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView back = t.backButton(this);
        back.setElevation(t.dp(2));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(t.dp(44), t.dp(44));
        bl.setMargins(t.dp(14), t.dp(14), 0, 0);
        root.addView(back, bl);

        follow = t.pill(this, "追踪", false);
        follow.setElevation(t.dp(2));
        follow.setOnClickListener(v -> toggleFollow());
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.dp(44),
                Gravity.END | Gravity.TOP);
        fl.setMargins(0, t.dp(14), t.dp(14), 0);
        root.addView(follow, fl);

        actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(t.dp(6), t.dp(6), t.dp(6), t.dp(6));
        actions.setBackground(t.round(t.sheet, 99));
        actions.setElevation(t.dp(8));
        useful = action("有用", v -> toggleUseful());
        actions.addView(useful, weight());
        actions.addView(action("不需要", v -> notNeeded()), weight());
        TextView ask = action("问一问", v -> openChat(null));
        ask.setBackground(t.ripple(t.ink, 99));
        ask.setTextColor(t.bg);
        actions.addView(ask, weight());
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        al.setMargins(t.dp(16), 0, t.dp(16), t.dp(16));
        root.addView(actions, al);

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                bl.topMargin = bars.top + t.dp(10);
                back.setLayoutParams(bl);
                fl.topMargin = bars.top + t.dp(10);
                follow.setLayoutParams(fl);
                body.setPadding(t.dp(22), bars.top + t.dp(72), t.dp(22), bars.bottom + t.dp(120));
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
        body.removeAllViews();

        JSONArray topics = e.optJSONArray("topics");
        if (topics != null && topics.length() > 0) {
            LinearLayout chips = new LinearLayout(this);
            for (int i = 0; i < Math.min(3, topics.length()); i++) {
                TextView c = t.text(this, topics.optString(i), 12, t.ink, 600);
                c.setPadding(t.dp(10), t.dp(5), t.dp(10), t.dp(5));
                c.setBackground(t.round(t.chip, 99));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = t.dp(6);
                chips.addView(c, lp);
            }
            body.addView(chips);
        }

        TextView headline = t.text(this, e.optString("headline"), 26, t.ink, 800);
        headline.setLineSpacing(t.dp(5), 1f);
        headline.setLetterSpacing(-0.01f);
        headline.setPadding(0, t.dp(16), 0, 0);
        body.addView(headline);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(0, t.dp(12), 0, 0);
        metaRow.addView(t.text(this, e.optInt("source_count") + " 个来源，最近一条 "
                + Theme.ago(Theme.millis(e.optString("time"))), 13, t.sub, 500));
        TextView original = t.text(this, "看原文 ↗", 13, t.ink, 700);
        original.setPadding(t.dp(12), t.dp(4), t.dp(4), t.dp(4));
        original.setBackground(t.ripple(Color.TRANSPARENT, 8));
        original.setOnClickListener(v -> openUrl(e.optString("url")));
        metaRow.addView(original);
        body.addView(metaRow);

        String summary = e.optString("summary");
        if (!summary.isEmpty()) {
            TextView s = t.text(this, summary, 17, t.ink, 400);
            s.setLineSpacing(t.dp(7), 1f);
            s.setPadding(0, t.dp(22), 0, 0);
            body.addView(s);
        }

        String why = e.optString("why");
        JSONArray reasons = e.optJSONArray("reasons");
        if (!why.isEmpty() || (reasons != null && reasons.length() > 0)) {
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(t.dp(16), t.dp(16), t.dp(16), t.dp(16));
            block.setBackground(t.round(t.sheet, 18));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = t.dp(24);
            LinearLayout title = new LinearLayout(this);
            title.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(this);
            dot.setBackground(t.round(Theme.CINNABAR, 99));
            title.addView(dot, new LinearLayout.LayoutParams(t.dp(7), t.dp(7)));
            TextView label = t.text(this, "与你有关", 13, t.ink, 700);
            label.setPadding(t.dp(8), 0, 0, 0);
            title.addView(label);
            block.addView(title);
            if (!why.isEmpty()) {
                TextView w = t.text(this, why, 15, t.ink, 500);
                w.setLineSpacing(t.dp(5), 1f);
                w.setPadding(0, t.dp(10), 0, 0);
                block.addView(w);
            }
            for (int i = 0; reasons != null && i < reasons.length(); i++) {
                TextView r = t.text(this, reasons.optString(i), 13, t.sub, 400);
                r.setLineSpacing(t.dp(3), 1f);
                r.setPadding(0, t.dp(i == 0 ? 12 : 6), 0, 0);
                block.addView(r);
            }
            body.addView(block, lp);
        }

        if (e.optBoolean("ai")) {
            TextView ai = t.text(this, "标题和摘要由 AI 根据来源整理，请以原文为准", 12, t.sub, 400);
            ai.setPadding(0, t.dp(14), 0, 0);
            body.addView(ai);
        }

        JSONArray updates = e.optJSONArray("updates");
        if (updates != null && updates.length() > 0) {
            body.addView(sectionLabel("进展"));
            for (int i = 0; i < updates.length(); i++) body.addView(updateRow(updates.optJSONObject(i), i == 0));
        }

        body.addView(askSection(e));

        TextView srcLabel = t.text(this, "来源", 13, t.sub, 700);
        srcLabel.setPadding(0, t.dp(32), 0, t.dp(4));
        body.addView(srcLabel);
        sources = new LinearLayout(this);
        sources.setOrientation(LinearLayout.VERTICAL);
        body.addView(sources);
        JSONArray signals = e.optJSONArray("signals");
        if (signals == null) {
            TextView wait = t.text(this, "正在加载来源…", 14, t.sub, 400);
            wait.setPadding(0, t.dp(12), 0, 0);
            sources.addView(wait);
        } else {
            for (int i = 0; i < signals.length(); i++) sources.addView(sourceRow(signals.optJSONObject(i)));
        }
        if (e.has("would_miss")) body.addView(wouldMiss(e.isNull("would_miss") ? null : e.optBoolean("would_miss")));
        following = e.optBoolean("following");
        updateActions();
    }

    private TextView sectionLabel(String text) {
        TextView v = t.text(this, text, 13, t.sub, 700);
        v.setPadding(0, t.dp(30), 0, t.dp(10));
        return v;
    }

    /** 追踪发现的实质进展，最新的在上，最新一条用朱砂点标出。 */
    private View updateRow(JSONObject u, boolean latest) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, t.dp(6), 0, t.dp(10));
        View dot = new View(this);
        dot.setBackground(t.round(latest ? Theme.CINNABAR : t.line, 99));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(t.dp(8), t.dp(8));
        dl.topMargin = t.dp(6);
        row.addView(dot, dl);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(t.dp(12), 0, 0, 0);
        TextView text = t.text(this, u.optString("text"), 15, t.ink, 500);
        text.setLineSpacing(t.dp(4), 1f);
        col.addView(text);
        TextView time = t.text(this, Theme.ago(Theme.millis(u.optString("at"))), 12, t.sub, 400);
        time.setPadding(0, t.dp(4), 0, 0);
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
            TextView resume = t.text(this, "继续上次的对话（" + thread.optInt("count") + " 条）", 15, t.ink, 600);
            resume.setPadding(t.dp(16), t.dp(13), t.dp(16), t.dp(13));
            resume.setBackground(t.ripple(t.chip, 16));
            resume.setOnClickListener(v -> openChat(null));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = t.dp(8);
            box.addView(resume, lp);
        }
        JSONArray qs = e.optJSONArray("questions");
        for (int i = 0; qs != null && i < qs.length(); i++) {
            String q = qs.optString(i);
            TextView chip = t.text(this, q, 15, t.ink, 500);
            chip.setPadding(t.dp(16), t.dp(13), t.dp(16), t.dp(13));
            chip.setBackground(t.ripple(t.sheet, 16));
            chip.setOnClickListener(v -> openChat(q));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = t.dp(8);
            box.addView(chip, lp);
        }
        return box;
    }

    /** “要不是 Catchup，你会错过它吗？”——衡量产品核心价值的那个问题。 */
    private View wouldMiss(Boolean answer) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, t.dp(32), 0, 0);
        box.addView(t.text(this, "要不是 Catchup，你会错过它吗？", 14, t.ink, 600));
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, t.dp(12), 0, 0);
        TextView yes = t.pill(this, "会错过", Boolean.TRUE.equals(answer));
        TextView no = t.pill(this, "本来就知道", Boolean.FALSE.equals(answer));
        yes.setOnClickListener(v -> rate(true, yes, no));
        no.setOnClickListener(v -> rate(false, yes, no));
        row.addView(yes);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = t.dp(8);
        row.addView(no, gap);
        box.addView(row);
        return box;
    }

    private void rate(boolean wouldMiss, TextView yes, TextView no) {
        restyle(yes, wouldMiss);
        restyle(no, !wouldMiss);
        yes.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        post("/v1/events/" + id + "/rating", "{\"would_miss\": " + wouldMiss + "}");
    }

    private void restyle(TextView pill, boolean filled) {
        pill.setTextColor(filled ? t.bg : t.ink);
        pill.setBackground(t.ripple(filled ? t.ink : t.chip, 99));
    }

    private void toggleFollow() {
        following = !following;
        updateActions();
        follow.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        post("/v1/events/" + id + "/follow", "{\"on\": " + following + "}");
        toast(following ? "开始追踪：有实质进展时会告诉你" : "已取消追踪");
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
        row.setPadding(t.dp(2), t.dp(14), t.dp(2), t.dp(14));
        row.setBackground(t.ripple(Color.TRANSPARENT, 12));
        LinearLayout top = new LinearLayout(this);
        top.addView(t.text(this, s.optString("label"), 12, t.sub, 600),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView time = t.text(this, Theme.ago(Theme.millis(s.optString("time"))), 12, t.sub, 400);
        time.setFontFeatureSettings("tnum");
        top.addView(time);
        row.addView(top);
        TextView title = t.text(this, s.optString("title"), 15, t.ink, 500);
        title.setLineSpacing(t.dp(3), 1f);
        title.setPadding(0, t.dp(6), 0, 0);
        row.addView(title);
        row.setOnClickListener(v -> openUrl(s.optString("url")));
        return row;
    }

    private TextView action(String label, View.OnClickListener l) {
        TextView a = t.text(this, label, 14, t.ink, 700);
        a.setGravity(Gravity.CENTER);
        a.setPadding(0, t.dp(13), 0, t.dp(13));
        a.setBackground(t.ripple(Color.TRANSPARENT, 99));
        a.setOnClickListener(l);
        return a;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private void updateActions() {
        boolean on = "useful".equals(feedback);
        useful.setText(on ? "已标记有用" : "有用");
        useful.setBackground(t.ripple(on ? t.chip : Color.TRANSPARENT, 99));
        follow.setText(following ? "追踪中" : "追踪");
        follow.setTextColor(following ? t.bg : t.ink);
        follow.setBackground(t.ripple(following ? t.ink : t.sheet, 99));
    }

    private void toggleUseful() {
        String value = "useful".equals(feedback) ? null : "useful";
        feedback = value;
        updateActions();
        useful.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        send(value);
    }

    private void notNeeded() {
        send("not_needed");
        toast("已标记为不需要");
        finish();
    }

    private void send(String value) {
        android.content.Context app = getApplicationContext();
        long eventId = id;
        new Thread(() -> {
            try {
                Api.post(app, "/v1/events/" + eventId + "/feedback",
                        new JSONObject().put("value", value == null ? JSONObject.NULL : value));
            } catch (Exception err) {
                Trace.log(app, "feedback.fail", "id=" + eventId + " " + err);
            }
        }, "feedback").start();
        Trace.log(this, "ui.feedback", "id=" + id + " value=" + value);
    }

    private void openUrl(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("没有可以打开链接的应用");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
