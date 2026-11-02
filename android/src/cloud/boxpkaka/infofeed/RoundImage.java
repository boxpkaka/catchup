package cloud.boxpkaka.infofeed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/**
 * 圆角图片：圆角直接画在图上（BitmapShader 画圆角矩形，按 centerCrop 铺满），不靠 setClipToOutline。
 * 卡片展开、收回时 Card 用 View.draw 给卡片拍照，软件绘制不认轮廓裁剪，靠轮廓裁出来的圆角在照片里是直角，
 * 收回动画结束、换回真卡片时图就从直角“变”成了圆角。
 * fadeBottom 让图的下面一段渐渐透明，融进后面卡片的底色；setImageAlpha 照常起作用（图下来时淡入）。
 */
final class RoundImage extends ImageView {
    private final float radius;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private float fade;
    private Bitmap shaded; // paint 里的着色器是哪张图、多大的时候做的
    private int shadedW, shadedH;

    RoundImage(Context c, float radiusPx) {
        super(c);
        radius = radiusPx;
    }

    /** 底下占图高 fraction 的一段从不透明渐变到全透明。 */
    void fadeBottom(float fraction) {
        fade = fraction;
        shaded = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable d = getDrawable();
        Bitmap b = d instanceof BitmapDrawable ? ((BitmapDrawable) d).getBitmap() : null;
        if (b == null || b.getWidth() == 0 || b.getHeight() == 0) {
            super.onDraw(canvas);
            return;
        }
        int w = getWidth(), h = getHeight();
        if (b != shaded || w != shadedW || h != shadedH) {
            BitmapShader image = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix matrix = new Matrix();
            float scale = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate((w - b.getWidth() * scale) / 2, (h - b.getHeight() * scale) / 2);
            image.setLocalMatrix(matrix);
            paint.setShader(fade <= 0 ? image : new ComposeShader(image, new LinearGradient(0, h * (1 - fade), 0, h,
                    Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP), PorterDuff.Mode.DST_IN));
            shaded = b;
            shadedW = w;
            shadedH = h;
        }
        paint.setAlpha(d.getAlpha());
        canvas.drawRoundRect(new RectF(0, 0, w, h), radius, radius, paint);
    }
}
