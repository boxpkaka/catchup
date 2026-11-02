package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话页：在某件事下面问（基于原文回答，标注出处），或者直接问 Catchup（基于你的事件库）；两种都会联网搜索补充，
 * 引用到的来源收成回答下面的一张卡片。回答流式逐字浮现，往上翻着看时不会被拽回底部。同一个问题最近问过，
 * 直接显示上次的回答，可以要求重新回答。每轮之后如果记住了关于你的新信息，会在回答下方显示，可以当场撤销。
 */
public class ChatActivity extends Activity {
    static final String EXTRA_EVENT = "event";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_QUESTIONS = "questions";
    static final String EXTRA_ASK = "ask";
    static final String EXTRA_THREAD = "thread";
    /** 服务器附在回答末尾的来源列表（catchup/chat.py 的 _footer），每行“编号. [标题](链接)”。 */
    private static final String SOURCES = "\n\n**来源**\n";
    private static final Pattern SOURCE = Pattern.compile("(\\d+)\\. \\[(.+)\\]\\((\\S+)\\)");

    private Theme t;
    private Nav nav;
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
    private boolean follow = true; // 回答输出时跟着滚到底；手指往上拖就停下，滑回底部再继续
    private boolean touching;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        t = new Theme(this);
        t.applyWindow(this);
        eventId = getIntent().getLongExtra(EXTRA_EVENT, -1);
        threadId = getIntent().getLongExtra(EXTRA_THREAD, -1);
        nav = Nav.of(this, build(), t.bg);
        setContentView(nav.wrap(state == null)); // 从点开的卡片展开或从右边推进来，右滑返回
        pendingAsk = getIntent().getStringExtra(EXTRA_ASK);
        if (threadId > 0 || eventId > 0) {
            loadHistory();
        } else if (pendingAsk != null && !pendingAsk.isEmpty()) {
            sendMessage(pendingAsk);
        } else {
            showSuggestions(getIntent().getStringArrayExtra(EXTRA_QUESTIONS));
        }
        Trace.log(this, "ui.chat", "event=" + eventId);
    }

    @Override
    public void finish() {
        nav.finish(super::finish); // 先收回卡片或滑出去再关
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

        // 标题栏：延伸到状态栏下面，中性的底色，不按话题上色
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(t.dp(12), t.dp(12), t.dp(16), t.dp(14));
        top.setBackground(t.round(t.bg, 0, 0, Theme.R_XL, Theme.R_XL));
        top.addView(t.backButton(this), new LinearLayout.LayoutParams(t.dp(48), t.dp(48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(t.dp(12), 0, 0, 0);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        TextView h = t.text(this, eventId > 0 && title != null ? title : "问 Catchup", 16, t.ink, 800);
        h.setSingleLine();
        h.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(h);
        TextView sub = t.text(this, eventId > 0 ? "基于原文和网页搜索回答" : "基于你的事件库和网页搜索回答", 12, t.ink, 400);
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
        messages.setClipChildren(false); // 回答里代码块、表格的卡片底左右伸出正文，见 Markdown.Block
        scroll.addView(messages);
        scroll.setOnTouchListener((v, ev) -> {
            int a = ev.getActionMasked();
            touching = a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE;
            return false;
        });
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (touching && y < oldY) follow = false;
            else if (y + scroll.getHeight() >= messages.getHeight() - t.dp(24)) follow = true;
        });
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
                View asked = null;
                for (int i = 0; i < ms.length(); i++) {
                    JSONObject m = ms.optJSONObject(i);
                    if ("user".equals(m.optString("role"))) {
                        View v = addUser(m.optString("content"));
                        if (ask != null && same(ask, m.optString("content"))) asked = v;
                    } else {
                        showAnswer(addAssistant(), m.optString("content"), null);
                    }
                }
                if (asked != null) { // 这个问题问过了：直接看上次的回答，不再重新生成
                    View target = asked;
                    follow = false;
                    scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, target.getTop() - t.dp(12))));
                    return;
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

    /** 去掉空白和标点再比，和服务器判断“同一个问题”的方式一致。 */
    private static boolean same(String a, String b) {
        String strip = "[\\s，,。.？?！!、]+";
        return a.replaceAll(strip, "").equalsIgnoreCase(b.replaceAll(strip, ""));
    }

    private void sendMessage(String text) {
        sendMessage(text, false);
    }

    /** fresh：不用上次的回答，重新生成。 */
    private void sendMessage(String text, boolean fresh) {
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
        Reveal reveal = new Reveal(answer);
        StringBuilder full = new StringBuilder();
        scrollToEnd();
        JSONObject body = new JSONObject();
        try {
            body.put("message", msg);
            if (threadId > 0) body.put("thread_id", threadId);
            else if (eventId > 0) body.put("event_id", eventId);
            if (fresh) body.put("fresh", true);
        } catch (Exception ignored) {
            // put 不会失败
        }
        new Thread(() -> {
            String error = null;
            try {
                Api.stream(this, "/v1/chat", body,
                        e -> runOnUiThread(() -> onEvent(e, status, answer, reveal, full, msg)));
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

    private void onEvent(JSONObject e, LinearLayout status, TextView answer, Reveal reveal, StringBuilder full,
                         String question) {
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
                showAnswer(answer, full.toString(), reveal);
                followEnd();
                break;
            case "cached":
                addCachedNote(e.optString("at"), question);
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

    private View addUser(String text) {
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
        return v;
    }

    /** 这次给的是之前的回答：标出是什么时候的，可以要求重新回答。 */
    private void addCachedNote(String at, String question) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(t.text(this, Theme.ago(Theme.millis(at)) + "的回答", 12.5f, t.sub, 600));
        TextView redo = t.text(this, "重新回答", 12.5f, t.pri, 800);
        redo.setPadding(t.dp(10), t.dp(6), t.dp(10), t.dp(6));
        redo.setBackground(t.ripple(Color.TRANSPARENT, 10));
        redo.setOnClickListener(v -> {
            if (busy) return;
            messages.removeView(row);
            sendMessage(question, true);
        });
        row.addView(redo);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = t.dp(6);
        messages.addView(row, lp);
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

    /** 回答正文按 Markdown 显示，末尾的来源列表拆出来做成卡片放在回答下面。reveal 为空时直接显示（历史记录）。 */
    private void showAnswer(TextView answer, String md, Reveal reveal) {
        int cut = sourcesAt(md);
        CharSequence body = Markdown.render(cut < 0 ? md : md.substring(0, cut), t, answer, this::openEvent);
        if (reveal != null) reveal.set(body);
        else answer.setText(body);
        if (cut < 0 || answer.getTag() != null) return;
        View card = sourcesCard(md.substring(cut + SOURCES.length()));
        answer.setTag(card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = t.dp(14);
        messages.addView(card, messages.indexOfChild(answer) + 1, lp);
        if (reveal == null) return;
        card.setAlpha(0f); // 等正文最后几个字浮现出来，再从下面轻轻升上来
        card.setTranslationY(t.dp(12));
        card.animate().alpha(1f).translationY(0).setStartDelay(160)
                .setInterpolator(Spring.FX).setDuration(Spring.FX.duration).start();
    }

    /** 回答末尾来源列表的起点，没有返回 -1。每行都得是来源格式，免得把回答自己写的“来源”小标题当成列表。 */
    private static int sourcesAt(String md) {
        int i = md.lastIndexOf(SOURCES);
        if (i < 0) return -1;
        for (String line : md.substring(i + SOURCES.length()).split("\n")) {
            if (!SOURCE.matcher(line).matches()) return -1;
        }
        return i;
    }

    /**
     * 引用的网页：平时收成一行（前几个网站的图标叠在一起、几个来源、来自哪些网站），点开逐条列出，
     * 编号对应正文里的角标，点一条打开原网页。
     */
    private View sourcesCard(String footer) {
        List<String[]> items = new ArrayList<>();
        Map<String, String> sites = new LinkedHashMap<>(); // 网站名 → 取图标用的域名
        for (String line : footer.split("\n")) {
            Matcher m = SOURCE.matcher(line);
            if (!m.matches()) continue;
            String title = m.group(2), url = m.group(3), host = Uri.parse(url).getHost();
            String site = host == null ? "" : host.replaceFirst("^(www|m)\\.", ""), domain = site;
            if (site.equals("news.google.com")) { // Google 新闻给的是跳转链接，媒体名在标题末尾的“ - 媒体”里
                int i = title.lastIndexOf(" - ");
                site = i > 0 ? title.substring(i + 3) : "Google 新闻";
                if (i > 0) title = title.substring(0, i);
                domain = ""; // 不知道媒体的域名，图标只显示首字
            }
            sites.putIfAbsent(site, domain);
            items.add(new String[]{m.group(1), site, title, url, domain});
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(t.dp(14), t.dp(13), t.dp(12), t.dp(13));
        int shown = 0;
        for (Map.Entry<String, String> e : sites.entrySet()) {
            if (shown == 3) break;
            View icon = siteIcon(e.getKey(), e.getValue(), 20, 99);
            icon.setPadding(t.dp(2), t.dp(2), t.dp(2), t.dp(2)); // 卡片色的圈把叠在一起的图标隔开
            ((GradientDrawable) icon.getBackground()).setStroke(t.dp(2), t.sheet);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(t.dp(24), t.dp(24));
            il.leftMargin = shown++ == 0 ? 0 : -t.dp(8);
            head.addView(icon, il);
        }
        TextView count = t.text(this, items.size() + " 个来源", 13.5f, t.ink, 700);
        count.setPadding(t.dp(8), 0, 0, 0);
        head.addView(count);
        TextView names = t.text(this, String.join(" · ", sites.keySet()), 12.5f, t.sub, 600);
        names.setTypeface(Theme.flex(this, 92, 600));
        names.setSingleLine();
        names.setEllipsize(TextUtils.TruncateAt.END);
        names.setPadding(t.dp(10), 0, t.dp(8), 0);
        head.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = t.text(this, "›", 20, t.sub, 700);
        arrow.setGravity(Gravity.CENTER);
        arrow.setRotation(90);
        head.addView(arrow, new LinearLayout.LayoutParams(t.dp(24), t.dp(24)));
        card.addView(head);
        head.setOnClickListener(v -> {
            boolean open = card.getChildCount() == 1;
            if (open) for (String[] s : items) card.addView(sourceRow(s)); // 每次新建：收起时的行还在淡出，不能再加回来
            else card.removeViews(1, card.getChildCount() - 1);
            t.segments(card);
            arrow.animate().rotation(open ? -90 : 90).setInterpolator(Spring.FAST).setDuration(Spring.FAST.duration)
                    .start();
        });
        t.segments(card);
        Theme.smooth(card);
        return card;
    }

    /** 来源卡里的一条：{编号, 网站, 标题, 链接, 域名}。 */
    private View sourceRow(String[] s) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(t.dp(14), t.dp(12), t.dp(16), t.dp(13));
        TextView num = t.text(this, s[0], 11, t.onPc, 800);
        num.setGravity(Gravity.CENTER);
        num.setMinWidth(t.dp(20));
        num.setPadding(t.dp(4), 0, t.dp(4), 0);
        num.setBackground(t.round(t.pc, 6));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.dp(18));
        nl.rightMargin = t.dp(12);
        row.addView(num, nl);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout siteLine = new LinearLayout(this);
        siteLine.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(t.dp(14), t.dp(14));
        il.rightMargin = t.dp(6);
        siteLine.addView(siteIcon(s[1], s[4], 14, 3), il);
        TextView site = t.text(this, s[1], 12, t.sub, 700);
        site.setTypeface(Theme.flex(this, 92, 700));
        site.setSingleLine();
        site.setEllipsize(TextUtils.TruncateAt.END);
        siteLine.addView(site);
        text.addView(siteLine);
        TextView title = t.text(this, s[2], 14, t.ink, 500);
        title.setLineSpacing(t.dp(3), 1f);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, t.dp(4), 0, 0);
        text.addView(title);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> openUrl(s[3]));
        return row;
    }

    /** 网站图标：先显示网站名的首字，服务器有这个网站的图标再换上；domain 为空（Google 新闻）时只有首字。 */
    private View siteIcon(String site, String domain, int size, float radius) {
        FrameLayout box = new FrameLayout(this);
        box.setBackground(t.round(t.chip, radius));
        box.setClipToOutline(true);
        TextView letter = t.text(this, site.isEmpty() ? "" : site.substring(0, 1).toUpperCase(), size * 0.5f, t.sub,
                800);
        letter.setGravity(Gravity.CENTER);
        letter.setIncludeFontPadding(false);
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

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "没有可以打开链接的应用", Toast.LENGTH_SHORT).show();
        }
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
        followEnd();
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
        followEnd();
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
            row.setOnClickListener(v -> openEvent(v, id));
            list.addView(row);
        }
        t.segments(list);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = t.dp(14);
        messages.addView(list, lp);
    }

    private void openEvent(View from, long id) {
        Card.open(this, from, new Intent(this, EventActivity.class).putExtra(EventActivity.EXTRA_ID, id)
                .putExtra(EventActivity.EXTRA_FROM, "chat"));
    }

    /** 回答正文里的事件链接：不是卡片，从右边推进来。 */
    private void openEvent(long id) {
        Push.open(this, new Intent(this, EventActivity.class).putExtra(EventActivity.EXTRA_ID, id)
                .putExtra(EventActivity.EXTRA_FROM, "chat"));
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

    /** 滚到最底下并继续跟随；不用 fullScroll，它会把焦点移进列表，正在打字时键盘会被收起。 */
    private void scrollToEnd() {
        follow = true;
        scroll.post(() -> scroll.smoothScrollTo(0, messages.getHeight()));
    }

    /** 新内容出来时跟着滚到底；用户往上翻着看的时候不动。 */
    private void followEnd() {
        if (follow) scroll.post(() -> scroll.smoothScrollTo(0, messages.getHeight()));
    }
}
