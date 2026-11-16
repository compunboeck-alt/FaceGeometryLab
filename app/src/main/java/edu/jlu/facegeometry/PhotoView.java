package edu.jlu.facegeometry;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

final class PhotoView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap bitmap;
    private Geometry.Point[] points;
    private Rect bounds;
    private boolean showPoints = true;

    PhotoView(Context context) {
        super(context);
        setContentDescription("照片及面部关键点");
    }

    void setPhoto(Bitmap image, Geometry.Point[] mesh, Rect faceBounds) {
        bitmap = image;
        points = mesh;
        bounds = faceBounds;
        invalidate();
    }

    void setShowPoints(boolean visible) {
        showPoints = visible;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) {
            return;
        }
        float scale = Math.min((float) getWidth() / bitmap.getWidth(),
                (float) getHeight() / bitmap.getHeight());
        float dx = (getWidth() - bitmap.getWidth() * scale) / 2;
        float dy = (getHeight() - bitmap.getHeight() * scale) / 2;
        RectF target = new RectF(dx, dy, dx + bitmap.getWidth() * scale,
                dy + bitmap.getHeight() * scale);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xffffffff);
        canvas.drawBitmap(bitmap, null, target, paint);
        if (!showPoints || points == null || bounds == null) {
            return;
        }
        int saved = canvas.save();
        canvas.clipRect(target);
        float density = getResources().getDisplayMetrics().density;
        paint.setColor(0xffa8f4ce);
        paint.setStrokeWidth(1.5f * density);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRoundRect(dx + bounds.left * scale, dy + bounds.top * scale,
                dx + bounds.right * scale, dy + bounds.bottom * scale,
                10 * density, 10 * density, paint);
        paint.setStyle(Paint.Style.FILL);
        for (Geometry.Point point : points) {
            if (point != null) {
                canvas.drawCircle(dx + (float) point.x() * scale,
                        dy + (float) point.y() * scale, density, paint);
            }
        }
        canvas.restoreToCount(saved);
    }
}
