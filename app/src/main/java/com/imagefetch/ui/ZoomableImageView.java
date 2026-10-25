package com.imagefetch.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * 看图用的 ImageView：按视图尺寸等比铺满居中，支持双指缩放、双击放大/还原，
 * 未处于放大状态时把横向甩动交给 {@link SwipeListener} 用于切换上一张/下一张。
 * 放大状态下单指拖动变为平移，不会误触发切图。
 */
public final class ZoomableImageView extends ImageView {

    /** 左右滑动回调：direction 为 -1 表示上一张，1 表示下一张。 */
    public interface SwipeListener {
        void onSwipe(int direction);
    }

    private static final float MAX_ZOOM = 4f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;
    private static final float ZOOMED_EPSILON = 1.01f;

    private final Matrix imageMatrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaling;

    private float baseScale = 1f;
    private SwipeListener swipeListener;

    public ZoomableImageView(Context context) {
        this(context, null);
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent event) {
                toggleZoom(event.getX(), event.getY());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent first, MotionEvent current, float dx, float dy) {
                if (!isZoomed()) return false;
                imageMatrix.postTranslate(-dx, -dy);
                clampBounds();
                applyMatrix();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent first, MotionEvent current, float vx, float vy) {
                if (first == null || isZoomed()) return false;
                float dx = current.getX() - first.getX();
                if (Math.abs(dx) < dp(48) || Math.abs(vx) < dp(280)) return false;
                if (swipeListener != null) swipeListener.onSwipe(dx < 0 ? 1 : -1);
                return true;
            }
        });

        scaling = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (getDrawable() == null) return false;
                float current = currentScale();
                float target = current * detector.getScaleFactor();
                float min = baseScale;
                float max = baseScale * MAX_ZOOM;
                if (target < min) target = min;
                if (target > max) target = max;
                float factor = target / current;
                imageMatrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                clampBounds();
                applyMatrix();
                return true;
            }
        });
    }

    public void setSwipeListener(SwipeListener listener) {
        swipeListener = listener;
    }

    /** 换图后回到「铺满居中」的初始状态。 */
    public void resetZoom() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() == 0 || getHeight() == 0) return;
        float width = drawable.getIntrinsicWidth();
        float height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) return;
        baseScale = Math.min(getWidth() / width, getHeight() / height);
        imageMatrix.reset();
        imageMatrix.postScale(baseScale, baseScale);
        imageMatrix.postTranslate(
                (getWidth() - width * baseScale) / 2f,
                (getHeight() - height * baseScale) / 2f);
        applyMatrix();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        resetZoom();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        resetZoom();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getDrawable() == null) return super.onTouchEvent(event);
        scaling.onTouchEvent(event);
        if (!scaling.isInProgress()) gestures.onTouchEvent(event);
        return true;
    }

    private boolean isZoomed() {
        return currentScale() > baseScale * ZOOMED_EPSILON;
    }

    private float currentScale() {
        imageMatrix.getValues(matrixValues);
        return matrixValues[Matrix.MSCALE_X];
    }

    private void toggleZoom(float focusX, float focusY) {
        if (getDrawable() == null || baseScale <= 0f) return;
        if (isZoomed()) {
            resetZoom();
            return;
        }
        float factor = baseScale * DOUBLE_TAP_ZOOM / currentScale();
        imageMatrix.postScale(factor, factor, focusX, focusY);
        clampBounds();
        applyMatrix();
    }

    /** 图片比视图大时不允许拖出边界；比视图小时保持居中。 */
    private void clampBounds() {
        Drawable drawable = getDrawable();
        if (drawable == null) return;
        RectF rect = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        imageMatrix.mapRect(rect);

        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float dx = 0;
        float dy = 0;
        if (rect.width() <= viewWidth) {
            dx = (viewWidth - rect.width()) / 2f - rect.left;
        } else if (rect.left > 0) {
            dx = -rect.left;
        } else if (rect.right < viewWidth) {
            dx = viewWidth - rect.right;
        }
        if (rect.height() <= viewHeight) {
            dy = (viewHeight - rect.height()) / 2f - rect.top;
        } else if (rect.top > 0) {
            dy = -rect.top;
        } else if (rect.bottom < viewHeight) {
            dy = viewHeight - rect.bottom;
        }
        imageMatrix.postTranslate(dx, dy);
    }

    private void applyMatrix() {
        setImageMatrix(imageMatrix);
        invalidate();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
