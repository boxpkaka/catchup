package cloud.boxpkaka.infofeed;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.LineHeightSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TabStopSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话回复用的 Markdown：标题、列表（可以嵌套，含任务列表）、引用、代码块、表格、分隔线，行内的粗体、斜体、粗斜体、删除线、
 * `代码`、[链接](url) 和网址。整段回答是一个 TextView 里的文字（逐字浮现见 Reveal），代码块和表格的底、引用的竖线、
 * 分隔线用段落 span 画。来源引用 [1] 显示为点缀色淡底上的小方块数字（和详情页的来源编号一样），事件引用 [E12] 可点击打开
 * 那件事；代码块和表格的底是磨砂。
 */
final class Markdown {
    interface OnEvent {
        void open(long id);
    }

    /** 逐字浮现时跟着淡入的装饰：角标，代码块和表格的底，引用的竖线，分隔线。Reveal 按装饰开头那个字给它设透明度。 */
    interface Fading {
        void fade(float alpha);
    }

    private static final Pattern INLINE = Pattern.compile(
            "\\\\(?<esc>[\\\\`*_{}\\[\\]()#+\\-.!|~>])"
                    + "|`(?<code>[^`]+)`"
                    + "|\\*\\*\\*(?<strong>.+?)\\*\\*\\*|\\*\\*(?<bold>.+?)\\*\\*|__(?<bold2>.+?)__"
                    + "|~~(?<strike>.+?)~~"
                    + "|(?<![*\\w])\\*(?![\\s*])(?<em>.+?)(?<![\\s*])\\*(?![*\\w])"
                    + "|(?<![_\\w])_(?![\\s_])(?<em2>.+?)(?<![\\s_])_(?![_\\w])"
                    + "|!?\\[(?<text>[^\\]]+)\\]\\((?<href>https?://[^)\\s]+)\\)"
                    + "|\\[E(?<event>\\d+)\\]"
                    + "|\\[(?<cite>\\d+(?:[,，]\\s*\\d+)*)\\]"
                    + "|(?<url>https?://[^\\s<>()（）\\[\\]，。；！？、“”]*[^\\s<>()（）\\[\\]，。；！？、“”.,;:!?'\"])");
    private static final Pattern FENCE = Pattern.compile("(\\s*)(`{3,}|~{3,}).*");
    private static final Pattern HEADING = Pattern.compile("\\s*#{1,6}\\s+(.*)");
    private static final Pattern RULE = Pattern.compile("\\s*([-*_])(\\s*\\1){2,}\\s*");
    private static final Pattern SEPARATOR = Pattern.compile("\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*");
    private static final Pattern QUOTE = Pattern.compile("\\s*>+\\s?(.*)");
    private static final Pattern LIST = Pattern.compile("(\\s*)(?:([-*+•])\\s+|(\\d+)(?:[.)]\\s+|、\\s*))(.*)");
    private static final Pattern TASK = Pattern.compile("\\[([ xX])\\]\\s+(.*)");
    private static final String[] BULLETS = {"•", "◦", "▪"};
    private static final int EXCL = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;

    private Markdown() {}

    /** view 是要显示它的 TextView：表格按它的字体和宽度判断排不排得下。 */
    static CharSequence render(String md, Theme t, TextView view, OnEvent onEvent) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = md.replace("\r", "").split("\n", -1);
        Deque<Integer> indents = new ArrayDeque<>(); // 列表每一层的缩进，比上一层多就是嵌套
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) out.append('\n');
            int start = out.length();
            Matcher m;
            if (!LIST.matcher(line).matches() && !line.trim().isEmpty()) indents.clear();
            if ((m = FENCE.matcher(line)).matches()) {
                // 代码块原样显示，不解析里面的 Markdown；还在流式输出、没收尾的，后面都算代码
                String fence = m.group(2), indent = m.group(1);
                List<String> code = new ArrayList<>();
                while (i + 1 < lines.length && !lines[i + 1].trim().startsWith(fence)) {
                    String c = lines[++i];
                    code.add(c.startsWith(indent) ? c.substring(indent.length()) : c.trim());
                }
                if (i + 1 < lines.length) i++; // 收尾的那一行
                code(out, code, t, view);
            } else if (line.contains("|") && i + 1 < lines.length && lines[i + 1].contains("|")
                    && SEPARATOR.matcher(lines[i + 1]).matches()) {
                List<List<String>> rows = new ArrayList<>();
                rows.add(cells(line));
                i++;
                while (i + 1 < lines.length && lines[i + 1].contains("|") && !lines[i + 1].trim().isEmpty()) {
                    rows.add(cells(lines[++i]));
                }
                table(out, rows, t, view, onEvent);
            } else if (RULE.matcher(line).matches()) {
                out.append(' ');
                out.setSpan(new Rule(t), start, out.length(), EXCL);
            } else if ((m = HEADING.matcher(line)).matches()) {
                inline(out, m.group(1), t, onEvent);
                span(out, start, new StyleSpan(Typeface.BOLD), new RelativeSizeSpan(1.08f));
            } else if ((m = QUOTE.matcher(line)).matches()) {
                inline(out, m.group(1), t, onEvent);
                span(out, start, new ForegroundColorSpan(t.sub), new Quote(t));
            } else if ((m = LIST.matcher(line)).matches()) {
                int indent = m.group(1).replace("\t", "    ").length();
                while (!indents.isEmpty() && indents.peekLast() > indent) indents.removeLast();
                if (indents.isEmpty() || indents.peekLast() < indent) indents.addLast(indent);
                int level = indents.size() - 1;
                String content = m.group(4);
                Matcher task = TASK.matcher(content);
                String marker = m.group(3) != null ? m.group(3) + "." : BULLETS[Math.min(level, BULLETS.length - 1)];
                if (m.group(2) != null && task.matches()) {
                    marker = task.group(1).equals(" ") ? "☐" : "☑";
                    content = task.group(2);
                }
                out.append(marker).append("  ");
                inline(out, content, t, onEvent);
                int margin = level * t.dp(18);
                out.setSpan(new LeadingMarginSpan.Standard(margin, margin + t.dp(16)), start, out.length(), EXCL);
            } else {
                inline(out, line, t, onEvent);
            }
        }
        return out;
    }

    /** 代码块：等宽小一号的字放在圆角卡片里，行距收紧。 */
    private static void code(SpannableStringBuilder out, List<String> lines, Theme t, TextView view) {
        int start = out.length();
        out.append(String.join("\n", lines));
        if (out.length() == start) out.append(' '); // 空代码块也有张卡片
        span(out, start, new TypefaceSpan("monospace"), new RelativeSizeSpan(0.86f),
                new Block(t, view.getLineSpacingExtra(), true));
    }

    /** 表格的一行拆成格：去掉两头的竖线，\| 是格子里的竖线。 */
    private static List<String> cells(String row) {
        String r = row.trim();
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|") && !r.endsWith("\\|")) r = r.substring(0, r.length() - 1);
        List<String> out = new ArrayList<>();
        for (String c : r.split("(?<!\\\\)\\|", -1)) out.add(c.trim());
        return out;
    }

    /**
     * 表格放在卡片里，行与行之间有细线。每列取最宽的一格，放得下就用制表位对齐成表格，表头加粗；
     * 放不下（手机上列多、字长的常见）就每行拆成一组：第一格加粗做标题，其余写成“表头：内容”。
     */
    private static void table(SpannableStringBuilder out, List<List<String>> rows, Theme t, TextView view,
                              OnEvent onEvent) {
        int n = rows.get(0).size(), gap = t.dp(16);
        TextPaint plain = view.getPaint(), bold = new TextPaint(plain);
        bold.setTypeface(Typeface.create(plain.getTypeface(), Typeface.BOLD));
        float[] widths = new float[n];
        float total = gap * (n - 1);
        for (int r = 0; r < rows.size(); r++) {
            for (int j = 0; j < n; j++) {
                SpannableStringBuilder cell = new SpannableStringBuilder();
                inline(cell, cell(rows.get(r), j), t, onEvent);
                widths[j] = Math.max(widths[j], (r == 0 ? bold : plain).measureText(cell.toString()));
            }
        }
        for (float w : widths) total += w;
        int room = view.getWidth() > 0 ? view.getWidth() - view.getTotalPaddingLeft() - view.getTotalPaddingRight()
                : view.getResources().getDisplayMetrics().widthPixels - t.dp(40);
        int start = out.length();
        if (total <= room || rows.size() == 1) {
            for (int r = 0; r < rows.size(); r++) {
                if (r > 0) rowEnd(out);
                int s = out.length();
                for (int j = 0; j < n; j++) {
                    if (j > 0) out.append('\t');
                    inline(out, cell(rows.get(r), j), t, onEvent);
                }
                if (r == 0) span(out, s, new StyleSpan(Typeface.BOLD));
            }
            float x = 0;
            for (int j = 0; j < n - 1; j++) {
                x += widths[j] + gap;
                span(out, start, new TabStopSpan.Standard(Math.round(x)));
            }
        } else {
            for (int r = 1; r < rows.size(); r++) {
                if (r > 1) rowEnd(out);
                int s = out.length();
                inline(out, cell(rows.get(r), 0), t, onEvent);
                span(out, s, new StyleSpan(Typeface.BOLD));
                for (int j = 1; j < n; j++) {
                    out.append('\n');
                    int h = out.length();
                    inline(out, cell(rows.get(0), j), t, onEvent);
                    out.append("：");
                    span(out, h, new ForegroundColorSpan(t.sub));
                    inline(out, cell(rows.get(r), j), t, onEvent);
                }
            }
        }
        span(out, start, new Block(t, view.getLineSpacingExtra(), false));
    }

    private static String cell(List<String> row, int j) {
        return j < row.size() ? row.get(j) : "";
    }

    /** 表格一行（一组）的结尾：换行符上挂个记号，Block 在这里画分隔线。 */
    private static void rowEnd(SpannableStringBuilder out) {
        out.append('\n');
        out.setSpan(new RowEnd(), out.length() - 1, out.length(), EXCL);
    }

    /** 从 start 到末尾挂上这些 span；空的不挂（长度为 0 的 EXCLUSIVE span 会抛异常，如只有“# ”的标题）。 */
    private static void span(SpannableStringBuilder out, int start, Object... spans) {
        if (out.length() == start) return;
        for (Object s : spans) out.setSpan(s, start, out.length(), EXCL);
    }

    private static void inline(SpannableStringBuilder out, String text, Theme t, OnEvent onEvent) {
        Matcher m = INLINE.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            int s = out.length();
            String g;
            if ((g = m.group("esc")) != null) {
                out.append(g);
            } else if ((g = m.group("code")) != null) {
                out.append(g);
                span(out, s, new TypefaceSpan("monospace"), new BackgroundColorSpan(t.chip));
            } else if ((g = m.group("strong")) != null) {
                inline(out, g, t, onEvent);
                span(out, s, new StyleSpan(Typeface.BOLD_ITALIC));
            } else if ((g = either(m.group("bold"), m.group("bold2"))) != null) {
                inline(out, g, t, onEvent);
                span(out, s, new StyleSpan(Typeface.BOLD));
            } else if ((g = m.group("strike")) != null) {
                inline(out, g, t, onEvent);
                span(out, s, new StrikethroughSpan());
            } else if ((g = either(m.group("em"), m.group("em2"))) != null) {
                inline(out, g, t, onEvent);
                span(out, s, new StyleSpan(Typeface.ITALIC));
            } else if ((g = m.group("text")) != null) {
                inline(out, g, t, onEvent);
                span(out, s, new URLSpan(m.group("href")));
            } else if ((g = m.group("event")) != null) {
                long id = Long.parseLong(g);
                out.append(" ↗");
                span(out, s, new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        onEvent.open(id);
                    }

                    @Override
                    public void updateDrawState(TextPaint p) {
                        p.setColor(t.pri);
                        p.setUnderlineText(false);
                    }
                });
            } else if ((g = m.group("cite")) != null) {
                out.append(g.replaceAll("\\s", ""));
                span(out, s, new Cite(t));
            } else {
                out.append(m.group("url"));
                span(out, s, new URLSpan(m.group("url")));
            }
            last = m.end();
        }
        out.append(text, last, text.length());
    }

    private static String either(String a, String b) {
        return a != null ? a : b;
    }

    /** 来源引用：小圆角方块，主色容器底，编号用粗体小字，垂直居中在文字中线上。 */
    static final class Cite extends ReplacementSpan implements Fading {
        private final Theme t;
        private final RectF box = new RectF();
        private float alpha = 1f;

        Cite(Theme t) {
            this.t = t;
        }

        @Override
        public void fade(float alpha) {
            this.alpha = alpha;
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
            int color = p.getColor(), a = Math.round(alpha * 255);
            Typeface face = p.getTypeface();
            int tint = (t.pri & 0x00FFFFFF) | (t.night ? 0x38000000 : 0x1F000000);
            p.setColor(tint);
            p.setAlpha(Math.round(Color.alpha(tint) * alpha));
            c.drawRoundRect(box, t.dp(6), t.dp(6), p);
            p.setColor(t.pri);
            p.setAlpha(a);
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

    /** 表格里一行（一组）结尾的记号。 */
    private static final class RowEnd {}

    /**
     * 代码块和表格的卡片：圆角卡片色的底，左右各伸出 BLEED 到正文外面（对话页的消息列表不裁子 View），里面的字和正文对齐；
     * 上下各留 PAD。代码的行距收紧到 CODE_GAP；表格在行（组）之间、行距的正中画细线。
     */
    private static final class Block implements LineBackgroundSpan, LineHeightSpan, Fading {
        private final Theme t;
        private final int spacing, pad, bleed, codeGap;
        private final boolean code;
        private final Path path = new Path();
        private final RectF box = new RectF();
        private final float[] radii = new float[8];
        private int below; // 最后一行加上留白以后的 descent：卡片画到这里，下面的行距留在卡片外
        private float alpha = 1f;

        Block(Theme t, float spacing, boolean code) {
            this.t = t;
            this.spacing = Math.round(spacing);
            this.code = code;
            pad = t.dp(12);
            bleed = t.dp(12);
            codeGap = t.dp(3);
        }

        @Override
        public void fade(float alpha) {
            this.alpha = alpha;
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int lineHeight,
                                 Paint.FontMetricsInt fm) {
            Spanned s = (Spanned) text;
            int from = s.getSpanStart(this), to = s.getSpanEnd(this);
            if (end <= from || start >= to) return;
            if (start <= from) {
                fm.ascent -= pad;
                fm.top -= pad;
            }
            if (end >= to) {
                fm.descent += pad;
                fm.bottom += pad;
                below = fm.descent;
            } else if (code && spacing > codeGap) {
                fm.descent -= spacing - codeGap;
                fm.bottom -= spacing - codeGap;
            }
        }

        @Override
        public void drawBackground(Canvas c, Paint p, int left, int right, int top, int baseline, int bottom,
                                   CharSequence text, int start, int end, int lnum) {
            Spanned s = (Spanned) text;
            int from = s.getSpanStart(this), to = s.getSpanEnd(this);
            if (end <= from || start >= to || alpha <= 0) return;
            boolean first = start <= from, last = end >= to;
            int color = p.getColor();
            Paint.Style style = p.getStyle();
            p.setStyle(Paint.Style.FILL);
            p.setColor(t.frost);
            p.setAlpha(Math.round(Color.alpha(t.frost) * alpha));
            float r = t.dp(Theme.R_S);
            for (int i = 0; i < 8; i++) radii[i] = (i < 4 ? first : last) ? r : 0;
            box.set(left - bleed, top, right + bleed, last ? baseline + below : bottom);
            path.reset();
            path.addRoundRect(box, radii, Path.Direction.CW);
            c.drawPath(path, p);
            if (!code && !last && end > 0 && s.getSpans(end - 1, end, RowEnd.class).length > 0) {
                p.setColor(t.hi);
                p.setAlpha(Math.round(Color.alpha(t.hi) * alpha));
                float y = bottom - spacing / 2f;
                c.drawRect(left, y, right, y + Math.max(1, t.dp(1)), p);
            }
            p.setStyle(style);
            p.setColor(color);
        }
    }

    /** 引用：左边一条竖线，字用次要色。 */
    private static final class Quote implements LeadingMarginSpan, Fading {
        private final Theme t;
        private float alpha = 1f;

        Quote(Theme t) {
            this.t = t;
        }

        @Override
        public void fade(float alpha) {
            this.alpha = alpha;
        }

        @Override
        public int getLeadingMargin(boolean first) {
            return t.dp(14);
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
                                      CharSequence text, int start, int end, boolean first, Layout layout) {
            if (alpha <= 0) return;
            int color = p.getColor();
            Paint.Style style = p.getStyle();
            p.setStyle(Paint.Style.FILL);
            p.setColor(t.line);
            p.setAlpha(Math.round(Color.alpha(t.line) * alpha));
            // 连着的几行引用竖线连成一条；最后一行只画到字底，不伸进下面的行距
            boolean last = end >= ((Spanned) text).getSpanEnd(this);
            c.drawRect(x, top, x + dir * t.dp(3), last ? baseline + p.descent() : bottom, p);
            p.setStyle(style);
            p.setColor(color);
        }
    }

    /** 分隔线：一行中间的一条细线。 */
    private static final class Rule implements LineBackgroundSpan, Fading {
        private final Theme t;
        private float alpha = 1f;

        Rule(Theme t) {
            this.t = t;
        }

        @Override
        public void fade(float alpha) {
            this.alpha = alpha;
        }

        @Override
        public void drawBackground(Canvas c, Paint p, int left, int right, int top, int baseline, int bottom,
                                   CharSequence text, int start, int end, int lnum) {
            if (alpha <= 0) return;
            int color = p.getColor();
            p.setColor(t.line);
            p.setAlpha(Math.round(Color.alpha(t.line) * alpha));
            float y = baseline - p.getTextSize() * 0.35f;
            c.drawRect(left, y, right, y + Math.max(1, t.dp(1)), p);
            p.setColor(color);
        }
    }
}
