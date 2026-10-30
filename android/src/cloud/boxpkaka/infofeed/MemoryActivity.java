package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆页：Catchup 记得的关于你的事，按类别列出，都能改、能删、能手动添加；
 * 顶部是“根据记忆个性化”的总开关（关掉后不使用任何个人信息排序）。
 */
public class MemoryActivity extends Activity {
    private Theme t;
    private Nav nav;
    private LinearLayout body;
    private final Map<String, String> kinds = new LinkedHashMap<>();
    private boolean personalize = true;
    private boolean shown; // 第一次画出来时整页淡入

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        t = new Theme(this);
        t.applyWindow(this);
        kinds.put("task", "正在做");
        kinds.put("interest", "长期关注");
        kinds.put("avoid", "不想看");
        kinds.put("source", "偏好的来源");
        kinds.put("avoid_source", "不想看的来源");
        kinds.put("style", "希望怎么写");
        nav = Nav.of(this, build(), t.bg);
        setContentView(nav.wrap(state == null)); // 从点开的卡片展开或从右边推进来，右滑返回
        load();
    }

    @Override
    public void finish() {
        nav.finish(super::finish); // 先收回卡片或滑出去再关
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(t.dp(12), t.dp(72), t.dp(12), t.dp(120));
        scroll.addView(body);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        View bottomFade = t.fade(this, true); // 底部按钮下面垫渐隐底，不用阴影
        FrameLayout.LayoutParams bfl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(132),
                Gravity.BOTTOM);
        root.addView(bottomFade, bfl);

        TextView back = t.backButton(this);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(t.dp(44), t.dp(44));
        bl.setMargins(t.dp(14), t.dp(14), 0, 0);
        root.addView(back, bl);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView add = t.text(this, "添加一条", 14, t.onInv, 700);
        add.setGravity(Gravity.CENTER);
        t.press(add, t.inv, 99);
        add.setOnClickListener(v -> editor(null));
        actions.addView(add, new LinearLayout.LayoutParams(0, t.dp(60), 1));
        TextView miss = t.text(this, "报告漏报", 15, t.onLime, 800);
        miss.setGravity(Gravity.CENTER);
        t.press(miss, t.lime, Theme.R_L);
        miss.setOnClickListener(v -> reportMiss());
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(t.dp(120), t.dp(64));
        ml.leftMargin = t.dp(10);
        actions.addView(miss, ml);
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        al.setMargins(t.dp(12), 0, t.dp(12), t.dp(16));
        root.addView(actions, al);

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                bl.topMargin = bars.top + t.dp(10);
                back.setLayoutParams(bl);
                body.setPadding(t.dp(12), bars.top + t.dp(72), t.dp(12), bars.bottom + t.dp(120));
                al.bottomMargin = bars.bottom + t.dp(16);
                actions.setLayoutParams(al);
                bfl.height = bars.bottom + t.dp(132);
                bottomFade.setLayoutParams(bfl);
                return WindowInsets.CONSUMED;
            });
        }
        return root;
    }

    private void load() {
        new Thread(() -> {
            JSONObject r = null;
            try {
                r = Api.get(this, "/v1/memories");
            } catch (Exception e) {
                Trace.log(this, "memories.fail", e.toString());
            }
            JSONObject got = r;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (got == null) {
                    toast("加载失败，稍后再试");
                    return;
                }
                render(got);
            });
        }, "memories").start();
    }

    private void render(JSONObject r) {
        personalize = r.optBoolean("personalize", true);
        if (!shown) {
            shown = true;
            body.setAlpha(0f);
            body.animate().alpha(1f).setDuration(220).start();
        }
        body.removeAllViews();
        TextView h = t.text(this, "Catchup 记得的\n关于你的事", 28, t.ink, 900);
        h.setLineSpacing(t.dp(6), 1f);
        h.setPadding(t.dp(10), 0, t.dp(10), 0);
        body.addView(h);
        TextView sub = t.text(this, "它们决定给你推什么、怎么写。在事件下面聊聊你在做什么，这里会自动更新；每一条都能改或删。",
                14, t.sub, 400);
        sub.setLineSpacing(t.dp(6), 1f);
        sub.setPadding(t.dp(10), t.dp(10), t.dp(10), t.dp(18));
        body.addView(sub);

        body.addView(toggleRow());

        Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
        for (String k : kinds.keySet()) groups.put(k, new ArrayList<>());
        JSONArray ms = r.optJSONArray("memories");
        for (int i = 0; ms != null && i < ms.length(); i++) {
            JSONObject m = ms.optJSONObject(i);
            List<JSONObject> g = groups.get(m.optString("kind"));
            if (g != null) g.add(m);
        }
        boolean any = false;
        for (Map.Entry<String, List<JSONObject>> g : groups.entrySet()) {
            if (g.getValue().isEmpty()) continue;
            any = true;
            body.addView(groupLabel(kinds.get(g.getKey())));
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (JSONObject m : g.getValue()) {
                list.addView(deletable(item(m), "删除",
                        () -> post("/v1/memories/" + m.optLong("id"), "{\"delete\": true}", this::load)));
            }
            deletableSegments(list);
            body.addView(list);
        }
        if (!any) {
            TextView empty = t.text(this, "还没有记忆。\n打开任意一件事点“问一问”，聊聊你在做什么，Catchup 会记住。",
                    14, t.sub, 400);
            empty.setLineSpacing(t.dp(4), 1f);
            empty.setPadding(t.dp(10), t.dp(32), t.dp(10), 0);
            body.addView(empty);
        }
        JSONArray qs = r.optJSONArray("questions");
        if (qs != null && qs.length() > 0) {
            body.addView(groupLabel("替你留意的问题"));
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < qs.length(); i++) {
                JSONObject q = qs.optJSONObject(i);
                View row = question(q);
                if ("answered".equals(q.optString("status"))) list.addView(row);
                else list.addView(deletable(row, "不用了",
                        () -> post("/v1/questions/" + q.optLong("id"), "{\"drop\": true}", this::load)));
            }
            deletableSegments(list);
            body.addView(list);
        }
    }

    /**
     * 左滑删除的一行（右滑留给返回）：后面垫着警示色的 label，滑过去这一行飞出、平滑收起，再去删；
     * 删完重新加载，列表和收起后的样子一样，不会再跳。
     */
    private View deletable(View row, String label, Runnable delete) {
        FrameLayout frame = new FrameLayout(this);
        TextView behind = t.text(this, label + "  ✕", 15, t.onErr, 800);
        behind.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        behind.setPadding(0, 0, t.dp(26), 0);
        behind.setVisibility(View.INVISIBLE);
        behind.setAlpha(0.45f);
        frame.addView(behind, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setClickable(true); // 不接按下就收不到后面的滑动
        row.setOnTouchListener(new SwipeDismiss(row, behind, v -> SwipeDismiss.collapse(frame, delete), -1));
        frame.setTag(new View[]{row, behind});
        return frame;
    }

    /** 分段排好以后，卡片色底换到跟手移动的那一层，警示色垫在后面。 */
    private void deletableSegments(LinearLayout list) {
        t.segments(list);
        int n = list.getChildCount();
        for (int i = 0; i < n; i++) {
            View frame = list.getChildAt(i);
            if (!(frame.getTag() instanceof View[])) continue;
            View[] parts = (View[]) frame.getTag();
            parts[0].setBackground(frame.getBackground());
            frame.setBackground(null);
            float top = i == 0 ? Theme.R_XL : Theme.R_XS, bottom = i == n - 1 ? Theme.R_XL : Theme.R_XS;
            parts[1].setBackground(t.round(t.err, top, top, bottom, bottom));
        }
    }

    private TextView groupLabel(String text) {
        TextView label = t.text(this, text, 13, t.sub, 700);
        label.setPadding(t.dp(20), t.dp(24), t.dp(20), t.dp(8));
        return label;
    }

    /** 对话里暂时没有答案的问题：还在等的可以取消；有了答案的点开回到当时的对话。 */
    private View question(JSONObject q) {
        boolean answered = "answered".equals(q.optString("status"));
        LinearLayout row = new LinearLayout(this);
        row.setPadding(t.dp(18), t.dp(14), t.dp(18), t.dp(14));
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        if (answered) ring.setColor(t.pri);
        else ring.setStroke(t.dp(2), t.ink);
        View mark = new View(this);
        mark.setBackground(ring);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(t.dp(11), t.dp(11));
        ml.topMargin = t.dp(6);
        ml.rightMargin = t.dp(12);
        row.addView(mark, ml);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, weight());
        TextView text = t.text(this, q.optString("text"), 15, t.ink, 600);
        text.setLineSpacing(t.dp(3), 1f);
        col.addView(text);
        if (answered) {
            TextView answer = t.text(this, q.optString("answer"), 14, t.ink, 400);
            answer.setAlpha(0.82f);
            answer.setLineSpacing(t.dp(3), 1f);
            answer.setPadding(0, t.dp(6), 0, 0);
            col.addView(answer);
        }
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, t.dp(6), 0, 0);
        bottom.addView(t.text(this, answered ? "有了答案，点开回到对话"
                        : Theme.ago(Theme.millis(q.optString("created"))) + "问的，有答案时告诉你", 12, t.sub, 400),
                weight());
        long id = q.optLong("id");
        if (answered) {
            row.setOnClickListener(v -> Push.open(this, new Intent(this, ChatActivity.class)
                    .putExtra(ChatActivity.EXTRA_THREAD, q.optLong("thread_id"))
                    .putExtra(ChatActivity.EXTRA_TITLE, q.optString("text"))));
        } else {
            TextView drop = t.text(this, "不用了", 12.5f, t.pri, 800);
            drop.setPadding(t.dp(10), t.dp(4), t.dp(4), t.dp(4));
            drop.setOnClickListener(v -> {
                drop.setEnabled(false);
                post("/v1/questions/" + id, "{\"drop\": true}", this::load);
            });
            bottom.addView(drop);
        }
        col.addView(bottom);
        return row;
    }

    private View toggleRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(t.dp(20), t.dp(16), t.dp(16), t.dp(16));
        row.setBackground(t.ripple(t.sheet, Theme.R_XL));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(t.text(this, "根据记忆个性化", 15, t.ink, 700));
        TextView s = t.text(this, personalize ? "排序会参考这些记忆" : "已关闭：按通用规则排序，不使用任何个人信息",
                12.5f, t.sub, 400);
        s.setPadding(0, t.dp(3), t.dp(8), 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        FrameLayout track = new FrameLayout(this);
        GradientDrawable tr = t.round(personalize ? t.pri : t.hi, 99);
        if (!personalize) tr.setStroke(t.dp(2), t.line);
        track.setBackground(tr);
        TextView knob = t.text(this, personalize ? "✓" : "", 13, t.pri, 900);
        knob.setGravity(Gravity.CENTER);
        knob.setBackground(t.round(personalize ? t.onPri : t.line, 99));
        int size = personalize ? 24 : 16;
        FrameLayout.LayoutParams kl = new FrameLayout.LayoutParams(t.dp(size), t.dp(size),
                Gravity.CENTER_VERTICAL | (personalize ? Gravity.END : Gravity.START));
        kl.setMargins(t.dp(personalize ? 4 : 8), 0, t.dp(4), 0);
        track.addView(knob, kl);
        row.addView(track, new LinearLayout.LayoutParams(t.dp(52), t.dp(32)));
        row.setOnClickListener(v -> {
            boolean next = !personalize;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            post("/v1/settings", "{\"personalize\": " + next + "}", this::load);
        });
        return row;
    }

    private View item(JSONObject m) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.TOP);
        row.setPadding(t.dp(18), t.dp(14), t.dp(18), t.dp(14));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, weight());
        TextView text = t.text(this, m.optString("text"), 15, t.ink, 600);
        text.setLineSpacing(t.dp(3), 1f);
        if (m.optBoolean("expired")) text.setAlpha(0.45f);
        col.addView(text);
        StringBuilder meta = new StringBuilder();
        String origin = m.optString("origin");
        String event = m.isNull("event") ? "" : m.optString("event");
        if ("对话".equals(origin) && !event.isEmpty()) meta.append("来自关于「").append(event).append("」的对话");
        else if ("对话".equals(origin)) meta.append("来自你和 Catchup 的对话");
        else meta.append("来自").append(origin.isEmpty() ? "你" : origin);
        meta.append("，").append(Theme.ago(Theme.millis(m.optString("updated"))));
        if (m.optBoolean("expired")) meta.append("，已过期，点开可续期");
        TextView mv = t.text(this, meta.toString(), 12, t.sub, 400);
        mv.setLineSpacing(t.dp(2), 1f);
        mv.setPadding(0, t.dp(4), 0, 0);
        col.addView(mv);
        if (!m.optBoolean("expired") && !m.isNull("days_left")) {
            LinearLayout days = new LinearLayout(this);
            days.setOrientation(LinearLayout.VERTICAL);
            days.setGravity(Gravity.CENTER_HORIZONTAL);
            days.setPadding(t.dp(12), t.dp(3), 0, 0);
            TextView n = t.text(this, String.valueOf(m.optInt("days_left")), 20, t.pri, 900);
            n.setTypeface(Theme.flex(this, 151, 900));
            days.addView(n);
            TextView unit = t.text(this, "天后到期", 11, t.sub, 600);
            unit.setPadding(0, t.dp(3), 0, 0);
            days.addView(unit);
            row.addView(days);
        }
        row.setOnClickListener(v -> editor(m));
        return row;
    }

    /** 编辑或新增一条记忆：选择类别、改文字；已有的可以删除。 */
    private void editor(JSONObject m) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(t.dp(22), t.dp(4), t.dp(22), 0);
        box.addView(t.text(this, m == null ? "告诉 Catchup 一件关于你的事" : "编辑这条记忆", 18, t.ink, 800));
        String[] chosen = {m == null ? "task" : m.optString("kind")};
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, t.dp(14), 0, t.dp(12));
        List<TextView> views = new ArrayList<>();
        for (Iterator<String> it = kinds.keySet().iterator(); it.hasNext(); ) {
            String k = it.next();
            TextView c = t.text(this, kinds.get(k), 12.5f, t.ink, 600);
            c.setPadding(t.dp(10), t.dp(7), t.dp(10), t.dp(7));
            c.setTag(k);
            c.setOnClickListener(v -> {
                chosen[0] = k;
                for (TextView x : views) styleChip(x, x.getTag().equals(chosen[0]));
            });
            views.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = t.dp(6);
            chips.addView(c, lp);
        }
        for (TextView x : views) styleChip(x, x.getTag().equals(chosen[0]));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        box.addView(hs);
        EditText input = field(m == null ? "比如：我在评估用 FunASR 替换 Whisper" : "");
        if (m != null) input.setText(m.optString("text"));
        box.addView(input);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, t.dp(16), 0, 0);
        Dialog[] d = new Dialog[1];
        if (m != null) {
            TextView del = t.pill(this, "删除", false);
            del.setTextColor(t.onErr);
            del.setBackground(t.ripple(t.err, 99));
            del.setOnClickListener(v -> {
                d[0].dismiss();
                post("/v1/memories/" + m.optLong("id"), "{\"delete\": true}", this::load);
            });
            buttons.addView(del, weight());
            buttons.addView(new View(this), new LinearLayout.LayoutParams(t.dp(10), 1));
        }
        TextView save = t.pill(this, "保存", true);
        save.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            d[0].dismiss();
            try {
                JSONObject b = new JSONObject().put("kind", chosen[0]).put("text", text);
                if (m != null && m.optBoolean("expired")) b.put("renew", true);
                post(m == null ? "/v1/memories" : "/v1/memories/" + m.optLong("id"), b.toString(), this::load);
            } catch (Exception ignored) {
                // put 不会失败
            }
        });
        buttons.addView(save, weight());
        box.addView(buttons);
        d[0] = t.sheet(this, box);
        d[0].show();
        input.requestFocus();
    }

    private void reportMiss() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(t.dp(22), t.dp(4), t.dp(22), 0);
        box.addView(t.text(this, "有什么在别处看到、\n但 Catchup 没告诉你？", 18, t.ink, 800));
        TextView hint = t.text(this, "写下标题或链接，Catchup 会记住这类内容，以后留意。", 13, t.sub, 400);
        hint.setPadding(0, t.dp(8), 0, t.dp(12));
        box.addView(hint);
        EditText input = field("比如：Qwen3-TTS 开源了，我是在群里看到的");
        box.addView(input);
        TextView send = t.pill(this, "提交", true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = t.dp(16);
        box.addView(send, lp);
        Dialog d = t.sheet(this, box);
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            d.dismiss();
            new Thread(() -> {
                String msg;
                try {
                    JSONObject r = Api.post(this, "/v1/misses", new JSONObject().put("text", text));
                    JSONArray added = r.optJSONArray("added");
                    msg = added != null && added.length() > 0 ? "已记下：" + added.optJSONObject(0).optString("text")
                            : "已记下";
                } catch (Exception e) {
                    msg = "提交失败：" + MainActivity.reason(e);
                }
                String toastText = msg;
                runOnUiThread(() -> {
                    toast(toastText);
                    load();
                });
            }, "miss").start();
        });
        d.show();
        input.requestFocus();
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(t.sub);
        e.setTextColor(t.ink);
        e.setTextSize(15);
        e.setMinLines(2);
        e.setGravity(Gravity.TOP);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setPadding(t.dp(16), t.dp(14), t.dp(16), t.dp(14));
        e.setBackground(t.round(t.sheet, Theme.R_M));
        return e;
    }

    private void styleChip(TextView c, boolean on) {
        c.setTextColor(on ? t.onPri : t.ink);
        c.setBackground(t.round(on ? t.pri : t.hi, 99));
    }

    private void post(String path, String json, Runnable then) {
        new Thread(() -> {
            try {
                Api.post(this, path, new JSONObject(json));
            } catch (Exception e) {
                Trace.log(this, "post.fail", path + " " + e);
                runOnUiThread(() -> toast("操作失败：" + MainActivity.reason(e)));
            }
            runOnUiThread(() -> {
                if (!isDestroyed()) then.run();
            });
        }, "post").start();
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
