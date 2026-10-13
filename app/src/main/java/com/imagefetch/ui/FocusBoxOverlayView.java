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

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasBox) return;
        float density = getResources().getDisplayMetrics().density;
        int white = paint.getColor();
        // 先在方框位置压一圈稍粗的半透明黑，再画白框：只有黑底画面时才不会被吃掉轮廓。
        paint.setColor(0x80000000);
        paint.setStrokeWidth(STROKE_DP * density + 2f);
        canvas.drawRect(box, paint);
        paint.setColor(white);
        paint.setStrokeWidth(STROKE_DP * density);
        canvas.drawRect(box, paint);
    }
}
