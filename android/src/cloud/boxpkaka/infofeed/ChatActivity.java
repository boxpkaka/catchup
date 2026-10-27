package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
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
    static final String EXTRA_TOPICS = "topics"; // 事件的 topics（JSON 数组），标题栏用它的话题色

    private Theme t;
    private long eventId;
    private long threadId = -1;
    private boolean busy;
    private ScrollView scroll;
    private LinearLayout messages;
    private EditText input;
    private TextView send;
    private LinearLayout suggestions;
    private LinearLayout notes; // 这一轮的“记住了 / 会替你留意”，排成一组
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

    @Override
    public void onBackPressed() {
        if (isTaskRoot()) startActivity(new Intent(this, MainActivity.class)); // 从“有答案了”的通知直接进来时，返回到首页
        super.onBackPressed();
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 标题栏：在事件下提问时带着事件的话题色，延伸到状态栏下面；随便问时用中性的底色
        int[] c = {t.bg, t.ink};
        String topics = getIntent().getStringExtra(EXTRA_TOPICS);
        if (eventId > 0 && topics != null) {
            try {
                c = t.topic(new JSONArray(topics));
            } catch (Exception ignored) {
                // 话题坏了就用中性色
            }
        }
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(t.dp(12), t.dp(12), t.dp(16), t.dp(14));
        top.setBackground(t.round(c[0], 0, 0, Theme.R_XL, Theme.R_XL));
        top.addView(t.backButton(this), new LinearLayout.LayoutParams(t.dp(48), t.dp(48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(t.dp(12), 0, 0, 0);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        TextView h = t.text(this, eventId > 0 && title != null ? title : "问 Catchup", 16, c[1], 800);
        h.setSingleLine();
        h.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(h);
        TextView sub = t.text(this, eventId > 0 ? "基于这件事的原文回答" : "基于 Catchup 替你整理过的事件回答", 12, c[1], 400);
        sub.setAlpha(0.78f);
        sub.setPadding(0, t.dp(3), 0, 0);
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

        // 输入框和发送按钮分开：空着时发送是灰色圆形，有字时变成酸橙色的圆角方块
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.BOTTOM);
        bar.setPadding(t.dp(12), t.dp(8), t.dp(12), t.dp(12));
        input = new EditText(this);
        input.setHint(eventId > 0 ? "问问这件事…" : "想知道什么？");
        input.setHintTextColor(t.sub);
        input.setTextColor(t.ink);
        input.setTextSize(15);
        input.setBackground(t.round(t.sheet, Theme.R_XL));
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setMinHeight(t.dp(52));
        input.setPadding(t.dp(20), t.dp(14), t.dp(20), t.dp(14));
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                styleSend();
            }
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        send = t.text(this, "↑", 20, t.onLime, 900);
        send.setGravity(Gravity.CENTER);
        send.setContentDescription("发送");
        send.setOnClickListener(v -> sendMessage(input.getText().toString()));
        t.press(send, t.hi, 99);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(t.dp(52), t.dp(52));
        sl.leftMargin = t.dp(8);
        bar.addView(send, sl);
        styleSend();
        column.addView(bar);

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                top.setPadding(t.dp(12), bars.top + t.dp(12), t.dp(16), t.dp(14));
                bar.setPadding(t.dp(12), t.dp(8), t.dp(12), Math.max(bars.bottom, ime.bottom) + t.dp(12));
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
        if (eventId <= 0) {
            TextView hello = t.text(this, "想知道什么？", 30, t.ink, 900);
            hello.setPadding(t.dp(2), t.dp(56), 0, 0);
            suggestions.addView(hello);
        }
        TextView label = t.text(this, eventId > 0 ? "可以这样问" : "试试问", 13, t.sub, 700);
        label.setPadding(t.dp(10), t.dp(eventId > 0 ? 24 : 30), 0, t.dp(10));
        suggestions.addView(label);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (String q : questions) {
            TextView chip = t.text(this, q, 15, t.ink, 600);
            chip.setLineSpacing(t.dp(3), 1f);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(t.dp(16), t.dp(14), t.dp(16), t.dp(14));
            Drawable mark = new Shapes.Blob(Shapes.SOFT, t.pri);
            mark.setBounds(0, 0, t.dp(22), t.dp(22));
            chip.setCompoundDrawablesRelative(mark, null, null, null);
            chip.setCompoundDrawablePadding(t.dp(12));
            chip.setOnClickListener(v -> sendMessage(q));
            list.addView(chip);
        }
        t.segments(list);
        suggestions.addView(list);
        messages.addView(suggestions);
    }

    // ---------- 发送与流式回复 ----------

    private void sendMessage(String text) {
        String msg = text.trim();
        if (msg.isEmpty() || busy) return;
        busy = true;
        input.setText("");
        styleSend();
        notes = null;
        if (suggestions != null) {
            messages.removeView(suggestions);
            suggestions = null;
        }
        addUser(msg);
        LinearLayout status = statusLine("正在思考");
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
                styleSend();
                removeStatus(status);
                if (err != null && full.length() == 0) answer.setText("没有拿到回答：" + err + "。再发一次试试。");
            });
        }, "chat-send").start();
    }

    private void onEvent(JSONObject e, LinearLayout status, TextView answer, StringBuilder full) {
        if (isDestroyed()) return;
        switch (e.optString("type")) {
            case "thread":
                threadId = e.optLong("id");
                break;
            case "status":
                ((TextView) status.getChildAt(1)).setText(e.optString("text"));
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
            case "watch":
                addWatch(e.optLong("id"), e.optString("text"));
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
        TextView v = t.text(this, text, 15, t.onPri, 500);
        v.setLineSpacing(t.dp(4), 1f);
        v.setPadding(t.dp(16), t.dp(11), t.dp(16), t.dp(11));
        v.setBackground(t.round(t.pri, Theme.R_L, Theme.R_L, 6, Theme.R_L));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.topMargin = t.dp(18);
        lp.leftMargin = t.dp(56);
        messages.addView(v, lp);
    }

    private TextView addAssistant() {
        TextView v = t.text(this, "", 15.5f, t.ink, 400);
        v.setLineSpacing(t.dp(10), 1f);
        v.setMovementMethod(LinkMovementMethod.getInstance());
        v.setLinkTextColor(t.pri);
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

    private LinearLayout statusLine(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, t.dp(16), 0, 0);
        Shapes.Loader loader = new Shapes.Loader(this, t.pri);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(t.dp(20), t.dp(20));
        ll.rightMargin = t.dp(8);
        row.addView(loader, ll);
        row.addView(t.text(this, text, 13, t.sub, 600));
        messages.addView(row);
        loader.start();
        return row;
    }

    private void removeStatus(LinearLayout status) {
        if (status.getParent() == null) return;
        messages.removeView(status); // 指示器离开窗口时自己停
    }

    private void styleSend() {
        boolean ready = !busy && input.getText().toString().trim().length() > 0;
        send.setTextColor(ready ? t.onLime : t.sub);
        ((Spring.Shape) ((android.graphics.drawable.RippleDrawable) send.getBackground()).getDrawable(0))
                .morph(t.dp(ready ? 18 : 99), ready ? t.lime : t.hi);
    }

    /** 这一轮的“记住了 / 会替你留意”放在同一个分段列表里。 */
    private LinearLayout notes() {
        if (notes == null) {
            notes = new LinearLayout(this);
            notes.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = t.dp(16);
            messages.addView(notes, lp);
        }
        return notes;
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
            View dot = new View(this);
            dot.setBackground(new Shapes.Blob(Shapes.SOFT, t.pri));
            row.addView(dot, new LinearLayout.LayoutParams(t.dp(12), t.dp(12)));
            TextView text = t.text(this, verb + "：" + m.optString("text"), 13, t.ink, 600);
            text.setLineSpacing(t.dp(2), 1f);
            text.setPadding(t.dp(10), 0, t.dp(8), 0);
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (undoable) {
                TextView undo = t.text(this, "撤销", 13, t.pri, 800);
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
            notes().addView(row);
            t.segments(notes);
        }
    }

    /** 这轮里暂时没有答案的问题：之后有报道回答了它，会写回这个对话并推送。不需要可以当场取消。 */
    private void addWatch(long id, String question) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(t.dp(14), t.dp(11), t.dp(8), t.dp(11));
        GradientDrawable ring = new GradientDrawable(); // 空心圆环：还在等答案，和“记住了”的实心形状区分开
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(t.dp(2), t.ink);
        View mark = new View(this);
        mark.setBackground(ring);
        row.addView(mark, new LinearLayout.LayoutParams(t.dp(11), t.dp(11)));
        TextView text = t.text(this, "会替你留意：" + question, 13, t.ink, 600);
        text.setLineSpacing(t.dp(2), 1f);
        text.setPadding(t.dp(10), 0, t.dp(8), 0);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView drop = t.text(this, "不用了", 13, t.pri, 800);
        drop.setPadding(t.dp(10), t.dp(6), t.dp(10), t.dp(6));
        drop.setBackground(t.ripple(Color.TRANSPARENT, 10));
        drop.setOnClickListener(v -> {
            drop.setEnabled(false);
            drop.setText("已取消");
            text.setAlpha(0.45f);
            postAsync("/v1/questions/" + id, "{\"drop\": true}");
        });
        row.addView(drop);
        notes().addView(row);
        t.segments(notes);
        scrollToEnd();
    }

    /** 随便问时引用到的事件，点开看详情。 */
    private void addRefs(JSONArray events) {
        if (events == null || events.length() == 0) return;
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(t.dp(16), t.dp(12), t.dp(14), t.dp(12));
            row.addView(t.text(this, e.optString("headline"), 14, t.ink, 600),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(t.text(this, "↗", 14, t.pri, 800));
            long id = e.optLong("id");
            row.setOnClickListener(v -> openEvent(id));
            list.addView(row);
        }
        t.segments(list);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = t.dp(14);
        messages.addView(list, lp);
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
