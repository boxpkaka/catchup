package cloud.boxpkaka.infofeed;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 事件封面和网站图标：经 /v1 向服务器要（服务器去原网站取，封面 1440px 宽、图标 128px，见 catchup/images.py），
 * 手机缓存目录里存一份，同一张只下一次；解好的图在内存里留一些（按应用可用内存的六分之一）。服务器说没有的，这次运行里不再问。
 * 行里的小图按要显示的大小解码（maxPx），不为一张 56dp 的小图解一整张大图。
 */
final class Images {
    interface Done {
        void loaded(Bitmap b);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final String DIR = "hd"; // 高清图换了缓存目录，旧的 images/ 里那些低清图不再用，见 clearOld
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(
            (int) Math.min(Runtime.getRuntime().maxMemory() / 6, 128L << 20)) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();

    private Images() {}

    /** 删掉换成高清以前的缓存目录（低清的封面和图标）；在后台线程调用，只做一次也没关系，删过了就是空操作。 */
    static void clearOld(Context c) {
        File[] old = new File(c.getCacheDir(), "images").listFiles();
        if (old == null) return;
        for (File f : old) f.delete();
        new File(c.getCacheDir(), "images").delete();
    }

    /** 已经解好、在内存里的图，没有返回 null。 */
    static Bitmap cached(String path) {
        return MEMORY.get(path);
    }

    /** 按 maxPx 解码的那份（见 load）。 */
    static Bitmap cached(String path, int maxPx) {
        return MEMORY.get(key(path, maxPx));
    }

    /** 后台取图，取到了在主线程交给 done；没有图、没取到或页面已经关了就不回调。 */
    static void load(Activity a, String path, Done done) {
        load(a, path, 0, done);
    }

    /** 同上，短边解到不小于 maxPx 就够（缩小到二分之一、四分之一……），0 是原图。 */
    static void load(Activity a, String path, int maxPx, Done done) {
        Bitmap b = MEMORY.get(key(path, maxPx));
        if (b != null) {
            done.loaded(b);
            return;
        }
        if (MISSING.contains(path)) return;
        POOL.execute(() -> {
            Bitmap got = get(a, path, maxPx);
            if (got != null) a.runOnUiThread(() -> {
                if (!a.isDestroyed()) done.loaded(got);
            });
        });
    }

    /** 在调用线程里取图，需在后台线程：内存、手机缓存、服务器依次找，没有返回 null。 */
    static Bitmap get(Context c, String path) {
        return get(c, path, 0);
    }

    private static String key(String path, int maxPx) {
        return maxPx > 0 ? path + "@" + maxPx : path;
    }

    private static Bitmap get(Context c, String path, int maxPx) {
        String key = key(path, maxPx);
        Bitmap b = MEMORY.get(key);
        if (b != null || MISSING.contains(path)) return b;
        File f = new File(c.getCacheDir(), DIR + "/" + path.replaceAll("[^\\w.-]", "_"));
        try {
            if (!f.exists()) {
                byte[] data = Api.image(c, path);
                if (data == null) {
                    MISSING.add(path);
                    return null;
                }
                f.getParentFile().mkdirs();
                File tmp = File.createTempFile("img", null, f.getParentFile()); // 写完再改名，同时在读的不会读到半张
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(data);
                }
                tmp.renameTo(f);
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            if (maxPx > 0) {
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int shortSide = Math.min(o.outWidth, o.outHeight), sample = 1;
                while (shortSide / (sample * 2) >= maxPx) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
            }
            b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b != null) MEMORY.put(key, b);
            return b;
        } catch (Exception e) {
            Trace.log(c, "image.fail", path + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }
}
