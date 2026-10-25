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
    private TextView useful;
    private String feedback;

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

        TextView back = t.text(this, "←", 20, t.ink, 700);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setBackground(t.ripple(t.sheet, 22));
        back.setElevation(t.dp(2));
        back.setOnClickListener(v -> onBackPressed());
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(t.dp(44), t.dp(44));
        bl.setMargins(t.dp(14), t.dp(14), 0, 0);
        root.addView(back, bl);

        actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(t.dp(6), t.dp(6), t.dp(6), t.dp(6));
        actions.setBackground(t.round(t.sheet, 99));
        actions.setElevation(t.dp(8));
        useful = action("有用", v -> toggleUseful());
        actions.addView(useful, weight());
        actions.addView(action("不需要", v -> notNeeded()), weight());
        TextView source = action("看原文", v -> openUrl(event == null ? "" : event.optString("url")));
        source.setBackground(t.ripple(t.ink, 99));
        source.setTextColor(t.bg);
        actions.addView(source, weight());
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        al.setMargins(t.dp(16), 0, t.dp(16), t.dp(16));
        root.addView(actions, al);

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                bl.topMargin = bars.top + t.dp(10);
                back.setLayoutParams(bl);
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

        TextView meta = t.text(this, e.optInt("source_count") + " 个来源，最近一条 "
                + Theme.ago(Theme.millis(e.optString("time"))), 13, t.sub, 500);
        meta.setPadding(0, t.dp(12), 0, 0);
        body.addView(meta);

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
        updateActions();
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
