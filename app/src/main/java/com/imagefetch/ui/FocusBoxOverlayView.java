package com.imagefetch.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 实时取景预览上的对焦点小方框。
 *
 * <p>相机侧的 {@code 0x915B EOS_TouchAfPosition} 是「只移动、不触发对焦」，且只有单点/定点
 * 对焦方式才响应。没有任何视觉反馈时，用户分不清「命令没下发」还是「相机没响应」，
 * 所以这里把最近一次落点画在画面上。方框坐标由 {@code MainActivity} 按 ImageView 的显示矩阵
 * 换算后传进来（见 {@code updateFocusBox()}）。
 *
 * <p>覆盖层不碰触摸（{@code clickable=false}、{@code focusable=false}），点按仍落到下层 ImageView。
 */
public final class FocusBoxOverlayView extends View {
    /** 方框线宽（dp）；细一点更像取景器里的对焦框。 */
    private static final float STROKE_DP = 2f;
    /** 三分线颜色：半透明白，压在画面上但不抢主体。 */
    private static final int GRID_COLOR = 0x66FFFFFF;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    /** 画面在控件里的实际显示区域（fitCenter 时两侧/上下可能有黑边）；没设过就用整个控件。 */
    private final RectF content = new RectF();
    private boolean hasContent;
    private boolean gridVisible;
    private boolean hasBox;

    public FocusBoxOverlayView(Context context) {
        this(context, null);
    }

    public FocusBoxOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.WHITE);
    }

    /** 设定方框在控件坐标系里的位置（左、上、右、下）。 */
    public void setBoxRect(float left, float top, float right, float bottom) {
        box.set(left, top, right, bottom);
        hasBox = true;
        invalidate();
    }

    /** 清除方框。面板关闭或还没点过时调用。 */
    public void clear() {
        hasBox = false;
        invalidate();
    }

    /** 开关三分构图线。 */
    public void setGridVisible(boolean visible) {
        if (gridVisible == visible) return;
        gridVisible = visible;
        invalidate();
    }

    /** 传入画面实际显示区域（控件坐标）；不调用时三分线按整个控件画。 */
    public void setContentRect(float left, float top, float right, float bottom) {
        content.set(left, top, right, bottom);
        hasContent = true;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (gridVisible) drawGrid(canvas);
        if (!hasBox) return;
        float density = getResources().getDisplayMetrics().density;
        // 先在方框位置压一圈稍粗的半透明黑，再画白框：只有黑底画面时才不会被吃掉轮廓。
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0x80000000);
        paint.setStrokeWidth(STROKE_DP * density + 2f);
        canvas.drawRect(box, paint);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(STROKE_DP * density);
        canvas.drawRect(box, paint);
    }

    /** 三分线只画在真实画面内，fitCenter 留下的黑边不画线。 */
    private void drawGrid(Canvas canvas) {
        float left = hasContent ? content.left : 0f;
        float top = hasContent ? content.top : 0f;
        float right = hasContent ? content.right : getWidth();
        float bottom = hasContent ? content.bottom : getHeight();
        if (right <= left || bottom <= top) return;
        float density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(GRID_COLOR);
        paint.setStrokeWidth(Math.max(1f, 1f * density));
        float width = right - left;
        float height = bottom - top;
        for (int i = 1; i < 3; i++) {
            float x = left + width * i / 3f;
            float y = top + height * i / 3f;
            canvas.drawLine(x, top, x, bottom, paint);
            canvas.drawLine(left, y, right, y, paint);
        }
    }
}
