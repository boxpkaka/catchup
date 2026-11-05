package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.content.Context;
import android.view.View;

/**
 * 界面风格（见 docs/android.md）：一种风格给出整个 App 的样子——材质（theme）、每一页的背景（mist）、各个界面的版式
 * （现在接上的是首页的行，feedRows）和动效（motion）。行为层（数据、点了去哪、手势怎么判定）不随风格变，要样子时向这里要。
 * 同一时刻只有一种，存在本机（Store.style），进程启动时读出（App）；换了以后页面重建。
 * 雾面金属是现在的样子。朴素是验证接口用的，只在诊断信息里能切：平涂、背景不动，最值得看是紧凑的一行，动效不回弹，
 * 点开时从右边推进来。还没接上的：详情页照旧是雾面金属；对话、记忆、模型、专题页只换了材质和动效，背景还在流动。
 */
abstract class Style {
    static final Style METAL = new Style("metal", "雾面金属", Motion.SPRINGY) {
        @Override
        Theme theme(Context c) {
            return new Theme(c);
        }

        @Override
        View mist(Activity a, Theme t, String key, long seed) {
            Flow f = new Flow(a);
            f.mist(t, key, seed);
            return f;
        }

        @Override
        FeedRows feedRows(Activity a, Theme t) {
            return new FeedRows(a, t);
        }
    };

    static final Style PLAIN = new Style("plain", "朴素", Motion.CALM) {
        @Override
        Theme theme(Context c) {
            return new PlainTheme(c);
        }

        @Override
        View mist(Activity a, Theme t, String key, long seed) {
            View v = new View(a);
            v.setBackgroundColor(t.bg);
            return v;
        }

        @Override
        FeedRows feedRows(Activity a, Theme t) {
            return new PlainRows(a, t);
        }
    };

    private static final Style[] ALL = {METAL, PLAIN};
    private static volatile Style current = METAL;

    final String key, name;
    final Motion motion;

    private Style(String key, String name, Motion motion) {
        this.key = key;
        this.name = name;
        this.motion = motion;
    }

    /** 材质和配色：页面里的底、块、按钮都从它取（Theme.of）。 */
    abstract Theme theme(Context c);

    /** 一页的背景：key 是这一页的颜色（话题、主色或珍珠银，见 Theme.zone），seed 让同一页每次看起来一样。 */
    abstract View mist(Activity a, Theme t, String key, long seed);

    /** 首页信息流里每一种行长什么样（行为见 FeedList）。 */
    abstract FeedRows feedRows(Activity a, Theme t);

    static Style current() {
        return current;
    }

    /** 进程启动时读出上次选的风格；没选过或不认识的用雾面金属。 */
    static void load(Context c) {
        current = byKey(Store.style(c));
    }

    /** 换成 s 并记下；已经打开的页面要自己重建才会换。 */
    static void use(Context c, Style s) {
        current = s;
        Store.setStyle(c, s.key);
    }

    /** 下一种风格（诊断信息里轮着切）。 */
    static Style next() {
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i] == current) return ALL[(i + 1) % ALL.length];
        }
        return METAL;
    }

    private static Style byKey(String key) {
        for (Style s : ALL) {
            if (s.key.equals(key)) return s;
        }
        return METAL;
    }
}
