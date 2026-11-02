package cloud.boxpkaka.infofeed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/**
 * 圆角图片：圆角直接画在图上（BitmapShader 画圆角矩形，按 centerCrop 铺满），不靠 setClipToOutline。
 * 卡片展开、收回时 Card 用 View.draw 给卡片拍照，软件绘制不认轮廓裁剪，靠轮廓裁出来的圆角在照片里是直角，
 * 收回动画结束、换回真卡片时图就从直角“变”成了圆角。
 */
final class RoundImage extends ImageView {
    private final float radius;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private Bitmap shaded; // paint 里的着色器是哪张图的

    RoundImage(Context c, float radiusPx) {
        super(c);
        radius = radiusPx;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable d = getDrawable();
        Bitmap b = d instanceof BitmapDrawable ? ((BitmapDrawable) d).getBitmap() : null;
        if (b == null || b.getWidth() == 0 || b.getHeight() == 0) {
            super.onDraw(canvas);
            return;
        }
        if (b != shaded) {
            paint.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            shaded = b;
        }
        float w = getWidth(), h = getHeight(), scale = Math.max(w / b.getWidth(), h / b.getHeight());
        matrix.setScale(scale, scale);
        matrix.postTranslate((w - b.getWidth() * scale) / 2, (h - b.getHeight() * scale) / 2);
        paint.getShader().setLocalMatrix(matrix);
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }
}
