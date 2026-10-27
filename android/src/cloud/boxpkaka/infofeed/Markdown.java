package cloud.boxpkaka.infofeed;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.View;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话回复用的极简 Markdown：标题、列表、**粗体**、`代码`、[链接](url)。
 * 来源引用 [1] 显示为主色容器里的小方块数字，事件引用 [E12] 可点击打开那件事。
 */
final class Markdown {
    interface OnEvent {
        void open(long id);
    }

    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)|\\[E(\\d+)\\]|\\[(\\d+(?:[,，]\\s*\\d+)*)\\]");

    private Markdown() {}

    static CharSequence render(String md, Theme t, OnEvent onEvent) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = md.replace("\r", "").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int start = out.length();
            String trimmed = line.trim();
            if (trimmed.matches("#{1,6}\\s+.*")) {
                inline(out, trimmed.replaceFirst("#{1,6}\\s+", ""), t, onEvent);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(1.08f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.matches("[-*•]\\s+.*") || trimmed.matches("\\d+[.、)]\\s*.*")) {
                boolean bullet = trimmed.matches("[-*•]\\s+.*");
                String marker = bullet ? "•  " : trimmed.replaceFirst("^(\\d+)[.、)]\\s*.*$", "$1.  ");
                String content = bullet ? trimmed.replaceFirst("[-*•]\\s+", "") : trimmed.replaceFirst("^\\d+[.、)]\\s*", "");
                out.append(marker);
                inline(out, content, t, onEvent);
                out.setSpan(new LeadingMarginSpan.Standard(0, t.dp(16)), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                inline(out, line, t, onEvent);
            }
            if (i < lines.length - 1) out.append('\n');
        }
        return out;
    }

    private static void inline(SpannableStringBuilder out, String text, Theme t, OnEvent onEvent) {
        Matcher m = INLINE.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            int s = out.length();
            if (m.group(1) != null) {
                out.append(m.group(1));
                out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(2) != null) {
                out.append(m.group(2));
                out.setSpan(new TypefaceSpan("monospace"), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(t.chip), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(3) != null) {
                out.append(m.group(3));
                out.setSpan(new URLSpan(m.group(4)), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(5) != null) {
                long id = Long.parseLong(m.group(5));
                out.append(" ↗");
                out.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        onEvent.open(id);
                    }

                    @Override
                    public void updateDrawState(TextPaint p) {
                        p.setColor(t.pri);
                        p.setUnderlineText(false);
                    }
                }, s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                out.append(m.group(6).replaceAll("\\s", ""));
                out.setSpan(new Cite(t), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            last = m.end();
        }
        out.append(text, last, text.length());
    }

    /** 来源引用：小圆角方块，主色容器底，编号用粗体小字，垂直居中在文字中线上。 */
    private static final class Cite extends ReplacementSpan {
        private final Theme t;
        private final RectF box = new RectF();

        Cite(Theme t) {
            this.t = t;
        }

        private float measure(Paint p, CharSequence text, int start, int end) {
            float size = p.getTextSize();
            p.setTextSize(size * 0.7f);
            float w = p.measureText(text, start, end);
            p.setTextSize(size);
            return Math.max(w + t.dp(8), size * 1.1f);
        }

        @Override
        public int getSize(Paint p, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return Math.round(measure(p, text, start, end) + t.dp(4));
        }

        @Override
        public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint p) {
            float size = p.getTextSize(), w = measure(p, text, start, end), h = size * 1.1f;
            float mid = y - size * 0.36f;
            box.set(x + t.dp(2), mid - h / 2, x + t.dp(2) + w, mid + h / 2);
            int color = p.getColor();
            Typeface face = p.getTypeface();
            p.setColor(t.pc);
            c.drawRoundRect(box, t.dp(6), t.dp(6), p);
            p.setColor(t.onPc);
            p.setTextSize(size * 0.7f);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, 800, false));
            p.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(text, start, end, box.centerX(), mid - (fm.ascent + fm.descent) / 2, p);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTypeface(face);
            p.setTextSize(size);
            p.setColor(color);
        }
    }
}
