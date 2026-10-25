package com.imagefetch.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.HorizontalScrollView;

/**
 * 横向翻页容器：内容按「一整屏」为单位排布，左右拖动松手后自动吸附到最近的整屏边，
 * 这样缩略图网格既能横向滑动，又能像翻页一样一屏一屏停住。
 */
public final class PagedGridScroll extends HorizontalScrollView {

    /** 停稳在某一屏时回调；screenCount 为总屏数。 */
    public interface OnScreenSettled {
        void onScreenSettled(int screen, int screenCount);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable settleTask = this::snapToNearest;
    private OnScreenSettled listener;
    private boolean touching;

    public PagedGridScroll(Context context) {
        this(context, null);
    }

    public PagedGridScroll(Context context, AttributeSet attrs) {
        super(context, attrs);
        setHorizontalScrollBarEnabled(false);
    }

    public void setOnScreenSettledListener(OnScreenSettled listener) {
        this.listener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = super.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touching = true;
            handler.removeCallbacks(settleTask);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touching = false;
            scheduleSettle(120);
        }
        return handled;
    }

    @Override
    protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
        super.onScrollChanged(left, top, oldLeft, oldTop);
        // 惯性滑动期间持续延后吸附，等真正停下来再对位。
        if (!touching) scheduleSettle(180);
    }

    private void scheduleSettle(long delay) {
        handler.removeCallbacks(settleTask);
        handler.postDelayed(settleTask, delay);
    }

    private void snapToNearest() {
        int width = getWidth();
        if (width <= 0 || getChildCount() == 0) return;
        int contentWidth = getChildAt(0).getWidth();
        int screenCount = Math.max(1, (contentWidth + width - 1) / width);
        int screen = Math.min(screenCount - 1, Math.round((float) getScrollX() / width));
        int target = Math.min(screen * width, Math.max(0, contentWidth - width));
        if (Math.abs(getScrollX() - target) > 2) {
            smoothScrollTo(target, 0);
        }
        if (listener != null) listener.onScreenSettled(screen, screenCount);
    }
}
