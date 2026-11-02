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
 * 事件封面和网站图标：经 /v1 向服务器要（服务器去原网站取、缩成小图），手机缓存目录里存一份，同一张只下一次；
 * 解好的图在内存里留一些。服务器说没有的，这次运行里不再问。
 */
final class Images {
    interface Done {
        void loaded(Bitmap b);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(16 << 20) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();

    private Images() {}

    /** 已经解好、在内存里的图，没有返回 null。 */
    static Bitmap cached(String path) {
        return MEMORY.get(path);
    }

    /** 后台取图，取到了在主线程交给 done；没有图、没取到或页面已经关了就不回调。 */
    static void load(Activity a, String path, Done done) {
        Bitmap b = MEMORY.get(path);
        if (b != null) {
            done.loaded(b);
            return;
        }
        if (MISSING.contains(path)) return;
        POOL.execute(() -> {
            Bitmap got = get(a, path);
            if (got != null) a.runOnUiThread(() -> {
                if (!a.isDestroyed()) done.loaded(got);
            });
        });
    }

    /** 在调用线程里取图，需在后台线程：内存、手机缓存、服务器依次找，没有返回 null。 */
    static Bitmap get(Context c, String path) {
        Bitmap b = MEMORY.get(path);
        if (b != null || MISSING.contains(path)) return b;
        File f = new File(c.getCacheDir(), "images/" + path.replaceAll("[^\\w.-]", "_"));
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
            b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) MEMORY.put(path, b);
            return b;
        } catch (Exception e) {
            Trace.log(c, "image.fail", path + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }
}
