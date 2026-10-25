package cloud.boxpkaka.infofeed;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 对话页：在某件事下面问（基于原文回答，标注出处），或者直接问 Catchup（基于你的事件库）。
 * 回答流式显示；每轮之后如果记住了关于你的新信息，会在回答下方显示，可以当场撤销。
 */
public class ChatActivity extends Activity {
    static final String EXTRA_EVENT = "event";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_QUESTIONS = "questions";
    static final String EXTRA_ASK = "ask";
    static final String EXTRA_THREAD = "thread";

    private Theme t;
    private long eventId;
    private long threadId = -1;
    private boolean busy;
    private ScrollView scroll;
    private LinearLayout messages;
    private EditText input;
    private TextView send;
    private LinearLayout suggestions;
    private String pendingAsk; // 从详情页带过来的问题，等历史加载完再发，避免顺序错乱

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        t = new Theme(this);
        t.applyWindow(this);
        eventId = getIntent().getLongExtra(EXTRA_EVENT, -1);
        threadId = getIntent().getLongExtra(EXTRA_THREAD, -1);
        setContentView(build());
        pendingAsk = getIntent().getStringExtra(EXTRA_ASK);
        if (threadId > 0 || eventId > 0) {
            loadHistory();
        } else if (pendingAsk != null && !pendingAsk.isEmpty()) {
            sendMessage(pendingAsk);
        } else {
            showSuggestions(null);
        }
        Trace.log(this, "ui.chat", "event=" + eventId);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.hold, R.anim.exit_down);
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(t.dp(14), t.dp(12), t.dp(20), t.dp(10));
        top.addView(t.backButton(this), new LinearLayout.LayoutParams(t.dp(44), t.dp(44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(t.dp(12), 0, 0, 0);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        TextView h = t.text(this, eventId > 0 && title != null ? title : "问 Catchup", 16, t.ink, 700);
        h.setSingleLine();
        h.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(h);
        TextView sub = t.text(this, eventId > 0 ? "基于这件事的原文回答" : "基于 Catchup 替你整理过的事件回答", 12, t.sub, 400);
        sub.setPadding(0, t.dp(4), 0, 0);
        titles.addView(sub);
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        column.addView(top);

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(t.dp(20), t.dp(8), t.dp(20), t.dp(16));
        scroll.addView(messages);
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.BOTTOM);
        bar.setPadding(t.dp(16), t.dp(8), t.dp(16), t.dp(12));
        LinearLayout field = new LinearLayout(this);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(t.dp(18), t.dp(4), t.dp(6), t.dp(4));
        field.setBackground(t.round(t.sheet, 26));
        input = new EditText(this);
        input.setHint(eventId > 0 ? "问问这件事…" : "想知道什么？");
        input.setHintTextColor(t.sub);
        input.setTextColor(t.ink);
        input.setTextSize(15);
        input.setBackground(null);
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setPadding(0, t.dp(10), 0, t.dp(10));
        field.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        send = t.text(this, "↑", 18, t.bg, 800);
        send.setGravity(Gravity.CENTER);
        send.setContentDescription("发送");
        send.setBackground(t.ripple(t.ink, 22));
        send.setOnClickListener(v -> sendMessage(input.getText().toString()));
        field.addView(send, new LinearLayout.LayoutParams(t.dp(40), t.dp(40)));
        bar.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(bar);

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                top.setPadding(t.dp(14), bars.top + t.dp(12), t.dp(20), t.dp(10));
                bar.setPadding(t.dp(16), t.dp(8), t.dp(16), Math.max(bars.bottom, ime.bottom) + t.dp(12));
                if (ime.bottom > 0) scrollToEnd();
                return WindowInsets.CONSUMED;
            });
        }
        return root;
    }

    // ---------- 历史与建议问题 ----------

    private void loadHistory() {
        new Thread(() -> {
            JSONObject r = null;
            try {
                r = Api.get(this, threadId > 0 ? "/v1/threads/" + threadId : "/v1/events/" + eventId + "/thread");
            } catch (Exception e) {
                Trace.log(this, "chat.history.fail", e.toString());
            }
            JSONObject got = r;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                JSONArray ms = got == null ? null : got.optJSONArray("messages");
                JSONObject thread = got == null ? null : got.optJSONObject("thread");
                if (thread != null) threadId = thread.optLong("id");
                String ask = pendingAsk;
                pendingAsk = null;
                if (ms == null || ms.length() == 0) {
                    if (ask != null && !ask.isEmpty()) sendMessage(ask);
                    else showSuggestions(getIntent().getStringArrayExtra(EXTRA_QUESTIONS));
                    return;
                }
                for (int i = 0; i < ms.length(); i++) {
                    JSONObject m = ms.optJSONObject(i);
                    if ("user".equals(m.optString("role"))) addUser(m.optString("content"));
                    else addAssistant().setText(Markdown.render(m.optString("content"), t, this::openEvent));
                }
                scrollToEnd();
                if (ask != null && !ask.isEmpty()) sendMessage(ask);
            });
        }, "chat-history").start();
    }

    private void showSuggestions(String[] questions) {
        if (questions == null || questions.length == 0) {
            questions = eventId > 0
                    ? new String[]{"这件事对我有什么影响？", "和之前的方案相比有什么不同？", "现在能用上吗？"}
                    : new String[]{"最近 TTS 开源有什么进展？", "这两天大模型有哪些重要发布？", "我在追踪的事有什么新进展？"};
        }
        suggestions = new LinearLayout(this);
        suggestions.setOrientation(LinearLayout.VERTICAL);
        suggestions.setPadding(0, t.dp(24), 0, 0);
        TextView label = t.text(this, eventId > 0 ? "可以这样问" : "试试问", 13, t.sub, 700);
        label.setPadding(0, 0, 0, t.dp(10));
        suggestions.addView(label);
        for (String q : questions) {
            TextView chip = t.text(this, q, 15, t.ink, 500);
            chip.setPadding(t.dp(16), t.dp(13), t.dp(16), t.dp(13));
            chip.setBackground(t.ripple(t.sheet, 16));
            chip.setOnClickListener(v -> sendMessage(q));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = t.dp(8);
            suggestions.addView(chip, lp);
        }
        messages.addView(suggestions);
    }

    // ---------- 发送与流式回复 ----------

    private void sendMessage(String text) {
        String msg = text.trim();
        if (msg.isEmpty() || busy) return;
        busy = true;
        send.setAlpha(0.35f);
        input.setText("");
        if (suggestions != null) {
            messages.removeView(suggestions);
            suggestions = null;
        }
        addUser(msg);
        TextView status = statusLine("正在思考");
        TextView answer = addAssistant();
        StringBuilder full = new StringBuilder();
        scrollToEnd();
        JSONObject body = new JSONObject();
        try {
            body.put("message", msg);
            if (threadId > 0) body.put("thread_id", threadId);
            else if (eventId > 0) body.put("event_id", eventId);
        } catch (Exception ignored) {
            // put 不会失败
        }
        new Thread(() -> {
            String error = null;
            try {
                Api.stream(this, "/v1/chat", body, e -> runOnUiThread(() -> onEvent(e, status, answer, full)));
            } catch (Exception e) {
                error = MainActivity.reason(e);
                Trace.log(this, "chat.fail", e.toString());
            }
            String err = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false;
                send.setAlpha(1f);
                removeStatus(status);
                if (err != null && full.length() == 0) answer.setText("没有拿到回答：" + err + "。再发一次试试。");
            });
        }, "chat-send").start();
    }

    private void onEvent(JSONObject e, TextView status, TextView answer, StringBuilder full) {
        if (isDestroyed()) return;
        switch (e.optString("type")) {
            case "thread":
                threadId = e.optLong("id");
                break;
            case "status":
                status.setText(e.optString("text"));
                break;
            case "delta":
                removeStatus(status);
                full.append(e.optString("text"));
                answer.setText(Markdown.render(full.toString(), t, this::openEvent));
                scrollToEnd();
                break;
            case "refs":
                addRefs(e.optJSONArray("events"));
                break;
            case "memory":
                addMemory(e);
                break;
            case "error":
                if (full.length() == 0) answer.setText(e.optString("text"));
                break;
            default:
                break;
        }
    }

    // ---------- 消息气泡 ----------

    private void addUser(String text) {
        TextView v = t.text(this, text, 15, t.bg, 500);
        v.setLineSpacing(t.dp(3), 1f);
        v.setPadding(t.dp(16), t.dp(11), t.dp(16), t.dp(11));
        GradientDrawable bubble = new GradientDrawable();
        bubble.setColor(t.ink);
        float r = t.dp(20), small = t.dp(6);
        bubble.setCornerRadii(new float[]{r, r, r, r, small, small, r, r});
        v.setBackground(bubble);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.topMargin = t.dp(18);
        lp.leftMargin = t.dp(48);
        messages.addView(v, lp);
    }

    private TextView addAssistant() {
        TextView v = t.text(this, "", 15.5f, t.ink, 400);
        v.setLineSpacing(t.dp(6), 1f);
        v.setMovementMethod(LinkMovementMethod.getInstance());
        v.setLinkTextColor(t.ink);
        v.setOnLongClickListener(x -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Catchup", v.getText()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = t.dp(16);
        messages.addView(v, lp);
        return v;
    }

    private TextView statusLine(String text) {
        TextView v = t.text(this, text, 13, t.sub, 500);
        v.setPadding(0, t.dp(16), 0, 0);
        messages.addView(v);
        ObjectAnimator pulse = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.35f);
        pulse.setDuration(700);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
        v.setTag(pulse);
        return v;
    }

    private void removeStatus(TextView status) {
        if (status.getParent() == null) return;
        ((ValueAnimator) status.getTag()).cancel();
        messages.removeView(status);
    }

    /** 这轮对话记住 / 改写 / 忘掉的关于你的信息；记住的可以当场撤销。 */
    private void addMemory(JSONObject e) {
        addMemoryRows(e.optJSONArray("added"), "记住了", true);
        addMemoryRows(e.optJSONArray("updated"), "更新了", false);
        addMemoryRows(e.optJSONArray("removed"), "忘掉了", false);
        scrollToEnd();
    }

    private void addMemoryRows(JSONArray items, String verb, boolean undoable) {
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject m = items.optJSONObject(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(t.dp(14), t.dp(11), t.dp(8), t.dp(11));
            row.setBackground(t.round(t.sheet, 14));
            View dot = new View(this);
            dot.setBackground(t.round(Theme.CINNABAR, 99));
            row.addView(dot, new LinearLayout.LayoutParams(t.dp(7), t.dp(7)));
            TextView text = t.text(this, verb + "：" + m.optString("text"), 13, t.ink, 500);
            text.setLineSpacing(t.dp(2), 1f);
            text.setPadding(t.dp(10), 0, t.dp(8), 0);
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (undoable) {
                TextView undo = t.text(this, "撤销", 13, t.sub, 700);
                undo.setPadding(t.dp(10), t.dp(6), t.dp(10), t.dp(6));
                undo.setBackground(t.ripple(Color.TRANSPARENT, 10));
                long id = m.optLong("id");
                undo.setOnClickListener(v -> {
                    undo.setEnabled(false);
                    undo.setText("已撤销");
                    text.setAlpha(0.45f);
                    postAsync("/v1/memories/" + id, "{\"delete\": true}");
                });
                row.addView(undo);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = t.dp(i == 0 ? 14 : 6);
            messages.addView(row, lp);
        }
    }

    /** 随便问时引用到的事件，点开看详情。 */
    private void addRefs(JSONArray events) {
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(t.dp(14), t.dp(12), t.dp(14), t.dp(12));
            row.setBackground(t.ripple(t.sheet, 14));
            row.addView(t.text(this, e.optString("headline"), 14, t.ink, 600),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(t.text(this, "↗", 14, Theme.CINNABAR, 700));
            long id = e.optLong("id");
            row.setOnClickListener(v -> openEvent(id));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = t.dp(i == 0 ? 14 : 6);
            messages.addView(row, lp);
        }
    }

    private void openEvent(long id) {
        startActivity(new Intent(this, EventActivity.class).putExtra(EventActivity.EXTRA_ID, id));
        overridePendingTransition(R.anim.enter_up, R.anim.hold);
    }

    private void postAsync(String path, String json) {
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try {
                Api.post(app, path, new JSONObject(json));
            } catch (Exception e) {
                Trace.log(app, "post.fail", path + " " + e);
            }
        }, "post").start();
    }

    /** 滚到最底下；不用 fullScroll，它会把焦点移进列表，正在打字时键盘会被收起。 */
    private void scrollToEnd() {
        scroll.post(() -> scroll.smoothScrollTo(0, messages.getHeight()));
    }
}
