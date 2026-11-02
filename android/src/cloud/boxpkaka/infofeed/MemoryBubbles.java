package cloud.boxpkaka.infofeed;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 记忆气泡：每条记忆一个气泡。越强越大（服务器给的 strength）；越近被你的行为印证越亮、越靠上，
 * 久没印证的变淡、往下沉；两天内被印证过的外面有一圈呼吸的光环，一天内新记下的右上角有个酸橙色的点。
 * 打开时从底下依次浮上来，平时轻轻漂浮，删除时破掉。只有长期关注和正在做会淡忘，其余几类亮度不变。
 * 系统关掉动画时一律静止。
 */
final class MemoryBubbles extends View {
    interface OnPick {
        void picked(JSONObject memory);
    }

    private static final long DAY = 86_400_000L;
    private static final float FADE_DAYS = 30f; // 和服务器上长期关注的半衰期一样
    private static final float TAU = (float) (Math.PI * 2);

    private static final class Bubble {
        JSONObject m;
        float r, x, y, goalY, fresh, lift, phase, period;
        boolean halo, fresh24h;
        int fill, ink;
        StaticLayout label;
        float pop = -1f; // 破掉的进度，-1 是没在破
    }

    private final Theme t;
    private final OnPick onPick;
    private final List<Bubble> bubbles = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final boolean motion = ValueAnimator.areAnimatorsEnabled();
    private final boolean entrance;
    private long born = -1;
    private int packedWidth = -1, packedHeight;
    private Bubble pressed;

    MemoryBubbles(Context c, Theme t, List<JSONObject> memories, boolean entrance, OnPick onPick) {
        super(c);
        this.t = t;
        this.onPick = onPick;
        this.entrance = entrance && motion;
        text.setTypeface(Theme.weight(700));
        text.setTextSize(12.5f * c.getResources().getDisplayMetrics().scaledDensity);
        long now = System.currentTimeMillis();
        StringBuilder said = new StringBuilder();
        for (JSONObject m : memories) {
            bubbles.add(bubble(m, now));
            said.append(m.optString("text")).append("；");
        }
        setContentDescription(said);
    }

    private Bubble bubble(JSONObject m, long now) {
        Bubble b = new Bubble();
        b.m = m;
        String kind = m.optString("kind");
        double strength = Math.max(0, Math.min(m.optDouble("strength", 3), 10));
        b.r = t.dp(30) + t.dp(34) * (float) Math.sqrt(strength / 10);
        long seen = Theme.millis(m.isNull("seen") ? m.optString("updated") : m.optString("seen"));
        float days = seen > 0 ? (now - seen) / (float) DAY : FADE_DAYS;
        boolean fades = "interest".equals(kind) || "task".equals(kind);
        boolean expired = m.optBoolean("expired");
        b.fresh = expired ? 0f : fades ? Math.max(0f, 1f - days / FADE_DAYS) : 1f;
        b.lift = 0.6f * b.fresh + 0.4f * (float) (strength / 10); // 浮力：越新、越强越往上
        b.halo = !expired && days < 2;
        b.fresh24h = now - Theme.millis(m.optString("created")) < DAY;
        Random rnd = new Random(m.optLong("id"));
        b.phase = rnd.nextFloat() * TAU;
        b.period = 4200 + rnd.nextInt(2400);
        switch (kind) {
            case "task": // 正在做：权重最高，用最跳的酸橙色
                b.fill = t.lime;
                b.ink = t.onLime;
                break;
            case "interest":
                b.fill = t.pri;
                b.ink = t.onPri;
                break;
            case "source":
                b.fill = t.pc;
                b.ink = t.onPc;
                break;
            default: // 不想看、不想看的来源、写法：素色
                b.fill = t.hi;
                b.ink = t.ink;
        }
        int width = (int) (b.r * 1.45f);
        b.label = StaticLayout.Builder.obtain(label(m.optString("text")), 0, label(m.optString("text")).length(),
                        text, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(b.r > t.dp(46) ? 3 : 2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(t.dp(1), 1f)
                .setIncludePad(false)
                .build();
        return b;
    }

    /** 气泡里只写要点：去掉“你关注”“你不想看……的内容”这类套话。 */
    static String label(String s) {
        return s.replaceFirst("^你(长期)?(关注|不想看|正在|在|希望|更信任|偏好)", "")
                .replaceFirst("(相关)?的内容$", "").trim();
    }

    /** 删掉一条时先让它破掉，再 done；不在这里的返回 false。 */
    boolean pop(long id, Runnable done) {
        for (Bubble b : bubbles) {
            if (b.m.optLong("id") != id) continue;
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(320);
            a.addUpdateListener(v -> {
                b.pop = (float) v.getAnimatedValue();
                invalidate();
            });
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    done.run();
                }
            });
            a.start();
            return true;
        }
        return false;
    }

    // ---------- 排布 ----------

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (width != packedWidth) {
            pack(width);
            packedWidth = width;
        }
        setMeasuredDimension(width, packedHeight);
    }

    /**
     * 简单的松弛排布：按记忆 id 撒下初始位置（每次打开都一样），再反复把重叠的推开（按面积分，大的少动）、
     * 往中间聚，并让每个气泡往自己的目标高度靠：越新越强的浮在上面，快忘了的沉到下面；最后几轮只消除重叠。
     */
    private void pack(int width) {
        float pad = t.dp(8), gap = t.dp(6), maxR = 0;
        double area = 0;
        for (Bubble b : bubbles) {
            area += Math.PI * (b.r + gap) * (b.r + gap);
            maxR = Math.max(maxR, b.r);
        }
        float h = Math.max(2 * (maxR + pad), (float) (area / Math.max(width, 1) * 1.3));
        for (Bubble b : bubbles) {
            Random rnd = new Random(b.m.optLong("id") * 31 + 7);
            b.goalY = pad + b.r + (1f - b.lift) * Math.max(0, h - 2 * pad - 2 * b.r);
            b.x = pad + b.r + rnd.nextFloat() * Math.max(1, width - 2 * (pad + b.r));
            b.y = b.goalY + (rnd.nextFloat() - 0.5f) * t.dp(20);
        }
        for (int it = 0; it < 320; it++) {
            for (Bubble b : bubbles) {
                if (it >= 260) break; // 最后几轮只推开重叠的
                b.x += (width / 2f - b.x) * 0.012f;
                b.y += (b.goalY - b.y) * 0.03f;
            }
            for (int i = 0; i < bubbles.size(); i++) {
                Bubble p = bubbles.get(i);
                for (int j = i + 1; j < bubbles.size(); j++) {
                    Bubble q = bubbles.get(j);
                    float dx = q.x - p.x, dy = q.y - p.y, d = (float) Math.hypot(dx, dy), min = p.r + q.r + gap;
                    if (d >= min) continue;
                    if (d < 0.01f) {
                        dx = 0.01f;
                        d = 0.01f;
                    }
                    float share = q.r * q.r / (p.r * p.r + q.r * q.r), push = (min - d) / d; // p 退 share，q 退其余
                    p.x -= dx * push * share;
                    p.y -= dy * push * share;
                    q.x += dx * push * (1f - share);
                    q.y += dy * push * (1f - share);
                }
            }
            for (Bubble b : bubbles) {
                b.x = Math.max(pad + b.r, Math.min(width - pad - b.r, b.x));
                b.y = Math.max(pad + b.r, b.y);
            }
        }
        float bottom = 0;
        for (Bubble b : bubbles) bottom = Math.max(bottom, b.y + b.r);
        packedHeight = (int) (bottom + pad + t.dp(4));
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        if (born < 0) born = now;
        boolean moving = false;
        for (int i = 0; i < bubbles.size(); i++) {
            Bubble b = bubbles.get(i);
            float enter = entrance ? Math.max(0f, Math.min(1f, (now - born - i * 70) / 560f)) : 1f;
            if (enter < 1f) moving = true;
            if (enter <= 0f) continue;
            float eased = 1f - (1f - enter) * (1f - enter) * (1f - enter);
            float wave = motion ? (float) Math.sin(TAU * now / b.period + b.phase) : 0f;
            float cx = b.x + (motion ? (float) Math.cos(TAU * now / (b.period * 1.3f) + b.phase) * t.dp(2) : 0f);
            float cy = b.y + wave * t.dp(3) + (1f - eased) * t.dp(48);
            float scale = 0.55f + 0.45f * overshoot(enter);
            if (b == pressed) scale *= 0.94f;
            float alpha = (0.32f + 0.68f * b.fresh) * eased;
            if (b.pop >= 0f) {
                scale *= 1f + 0.3f * b.pop;
                alpha *= 1f - b.pop;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(b.fill, (1f - b.pop) * 0.9f));
                for (int k = 0; k < 8; k++) { // 破开的碎沫
                    double a = k * Math.PI / 4 + b.phase;
                    float dist = b.r * (1f + 0.7f * b.pop);
                    c.drawCircle(cx + (float) Math.cos(a) * dist, cy + (float) Math.sin(a) * dist,
                            t.dp(3.5f) * (1f - b.pop), paint);
                }
            }
            float r = b.r * scale;
            if (b.halo && motion && b.pop < 0f) { // 最近被印证：一圈光环跟着呼吸
                float breath = 0.5f + 0.5f * (float) Math.sin(TAU * now / 2400f + b.phase);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(t.dp(2));
                paint.setColor(withAlpha(b.fill == t.hi ? t.sub : b.fill, 0.45f * breath * alpha));
                c.drawCircle(cx, cy, r + t.dp(4) + t.dp(4) * breath, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(b.fill, alpha));
            c.drawCircle(cx, cy, r, paint);
            if (b.fill == t.hi) { // 素色的描一圈细边，浅色背景上也看得出轮廓
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(t.dp(1));
                paint.setColor(withAlpha(t.line, alpha));
                c.drawCircle(cx, cy, r, paint);
            }
            if (b.fresh24h && b.pop < 0f) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(b.fill == t.lime ? t.pri : t.lime, eased));
                c.drawCircle(cx + r * 0.7f, cy - r * 0.7f, t.dp(5), paint);
            }
            text.setColor(withAlpha(b.ink, Math.min(1f, alpha * 1.4f)));
            c.save();
            c.translate(cx, cy);
            c.scale(scale, scale);
            c.translate(-b.label.getWidth() / 2f, -b.label.getHeight() / 2f);
            b.label.draw(c);
            c.restore();
        }
        if (motion || moving) postInvalidateOnAnimation(); // 漂浮和光环一直在动
    }

    private static float overshoot(float x) {
        float s = 1.7f, y = x - 1f;
        return y * y * ((s + 1) * y + s) + 1f;
    }

    private static int withAlpha(int color, float a) {
        int alpha = Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, a)));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    // ---------- 点按 ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(e.getX(), e.getY());
                invalidate();
                return pressed != null;
            case MotionEvent.ACTION_MOVE:
                if (pressed != null && hit(e.getX(), e.getY()) != pressed) {
                    pressed = null;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP: {
                Bubble b = pressed;
                pressed = null;
                invalidate();
                if (b != null && b.pop < 0f && hit(e.getX(), e.getY()) == b) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                    performClick();
                    onPick.picked(b.m);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private Bubble hit(float x, float y) {
        for (int i = bubbles.size() - 1; i >= 0; i--) {
            Bubble b = bubbles.get(i);
            if (Math.hypot(x - b.x, y - b.y) <= b.r + t.dp(4)) return b;
        }
        return null;
    }
}
