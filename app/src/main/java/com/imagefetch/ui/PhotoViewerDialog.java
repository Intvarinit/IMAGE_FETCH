package com.imagefetch.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.imagefetch.R;
import com.imagefetch.ptp.CameraStorage;

import java.util.Locale;

/**
 * 全屏看图组件：黑底大图、双指/双击缩放、左右滑动切换当前页的其它照片，
 * 底部沿用原来的参数说明与选中切换。取代原先 AlertDialog 里的小窗预览。
 */
public final class PhotoViewerDialog extends Dialog {

    /** 翻到下一张/上一张时通知外部（主界面同步缩略图条的高亮与序号）。 */
    public interface PageListener {
        void onPageChanged(int position);
    }

    /** 取原图：由调用方（MainActivity）转交给相机会话，拿到结果后回调。 */
    public interface Loader {
        void loadPreview(CameraStorage.Item item, Result result);

        interface Result {
            void onPreview(long handle, Bitmap bitmap);
        }
    }

    /** 图片栏之外的直接操作：由 MainActivity 负责真正下载/删除，这里是入口。 */
    public interface ActionListener {
        void onDownload(CameraStorage.Item item);

        void onDelete(CameraStorage.Item item);
    }

    private final PhotoGridAdapter adapter;
    private final Loader loader;
    private int position;
    private PageListener pageListener;
    private ActionListener actionListener;

    private ZoomableImageView image;
    private TextView titleView;
    private TextView counterView;
    private TextView infoView;
    private Button toggleView;
    private Button downloadView;

    public PhotoViewerDialog(Context context, PhotoGridAdapter adapter, int position, Loader loader) {
        super(context);
        this.adapter = adapter;
        this.position = position;
        this.loader = loader;
    }

    public void setOnPageChangedListener(PageListener listener) {
        pageListener = listener;
    }

    public void setActionListener(ActionListener listener) {
        actionListener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_photo_preview);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.setDimAmount(0f);
        }

        // targetSdk 35 起窗口会延伸到系统栏下方，给上下两条工具条让出安全区。
        findViewById(R.id.previewRoot).setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        image = findViewById(R.id.previewImage);
        titleView = findViewById(R.id.previewTitle);
        counterView = findViewById(R.id.previewCounter);
        infoView = findViewById(R.id.previewInfo);
        toggleView = findViewById(R.id.previewToggle);
        downloadView = findViewById(R.id.previewDownload);

        findViewById(R.id.previewClose).setOnClickListener(view -> dismiss());
        toggleView.setOnClickListener(view -> {
            CameraStorage.Item item = currentItem();
            if (item == null) return;
            adapter.toggle(item);
            updateToggle(item);
        });
        downloadView.setOnClickListener(view -> {
            CameraStorage.Item item = currentItem();
            if (item != null && actionListener != null) actionListener.onDownload(item);
        });
        findViewById(R.id.previewDelete).setOnClickListener(view -> {
            CameraStorage.Item item = currentItem();
            if (item != null && actionListener != null) actionListener.onDelete(item);
        });
        image.setSwipeListener(direction -> {
            int target = position + direction;
            if (target < 0 || target >= adapter.getCount()) return;
            position = target;
            bind();
        });

        bind();
    }

    @Override
    public void show() {
        super.show();
        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    /** 载入当前序号的照片：先放已缓存的缩略图，再等相机原图替换。 */
    private void bind() {
        CameraStorage.Item item = currentItem();
        if (item == null) {
            dismiss();
            return;
        }
        titleView.setText(item.filename);
        counterView.setText(String.format(Locale.ROOT, "%d / %d", position + 1, adapter.getCount()));

        final Bitmap thumbnail = adapter.thumbnail(item.handle);
        image.setImageBitmap(thumbnail);
        infoView.setText(describe(item) + "\n" + getContext().getString(R.string.preview_loading));
        updateToggle(item);
        if (pageListener != null) pageListener.onPageChanged(position);

        loader.loadPreview(item, (handle, bitmap) -> {
            CameraStorage.Item shown = currentItem();
            if (!isShowing() || shown == null || shown.handle != handle) return;
            if (bitmap == null) {
                image.setImageBitmap(thumbnail);
                infoView.setText(thumbnail == null
                        ? describe(shown)
                        : describe(shown) + "\n"
                                + getContext().getString(R.string.preview_thumbnail_only));
                return;
            }
            image.setImageBitmap(bitmap);
            infoView.setText(describe(shown));
        });
    }

    private CameraStorage.Item currentItem() {
        if (position < 0 || position >= adapter.getCount()) return null;
        return adapter.itemAt(position);
    }

    /** 任意格式都可选中（选中后既能删除，JPEG 还能下载），因此全部显示切换按钮。 */
    private void updateToggle(CameraStorage.Item item) {
        toggleView.setVisibility(View.VISIBLE);
        toggleView.setText(adapter.isSelected(item.handle)
                ? R.string.preview_toggle_unselect
                : R.string.preview_toggle_select);
        // 取回只处理 JPEG：RAW/视频停在缩略图状态，下载按钮置灰，避免点了之后只得到一句跳过的提示。
        downloadView.setEnabled(item.isJpeg());
        downloadView.setText(item.isJpeg()
                ? R.string.preview_download
                : R.string.preview_download_unavailable);
    }

    private static String describe(CameraStorage.Item item) {
        StringBuilder text = new StringBuilder(item.capturedAt);
        if (item.width > 0 && item.height > 0) {
            text.append(" · ").append(item.width).append(" × ").append(item.height);
        }
        if (item.sizeBytes > 0) {
            text.append(String.format(
                    Locale.ROOT, " · %.1f MB", item.sizeBytes / (1024.0 * 1024.0)));
        }
        return text.toString();
    }
}
