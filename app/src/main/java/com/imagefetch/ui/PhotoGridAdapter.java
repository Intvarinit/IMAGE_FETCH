package com.imagefetch.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.imagefetch.R;
import com.imagefetch.ptp.CameraStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 图片栏：缩略图网格 + 点选。 */
public final class PhotoGridAdapter extends BaseAdapter {
    public interface SelectionListener {
        void onSelectionChanged(int count);
    }

    private static final int TARGET_THUMBNAIL_PX = 256;

    private final Context context;
    private final LayoutInflater inflater;
    private final List<CameraStorage.Item> items = new ArrayList<>();
    private final Map<Long, Bitmap> thumbnails = new HashMap<>();
    /**
     * 跨页保留选中项：翻页只换 items，选中集合按对象句柄累积，
     * 因此可以跨页勾选后一次性下载。
     */
    private final Map<Long, CameraStorage.Item> selected = new LinkedHashMap<>();
    private SelectionListener selectionListener;

    public PhotoGridAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    public void setSelectionListener(SelectionListener listener) {
        selectionListener = listener;
    }

    /** 换页只替换当前页数据；选中项跨页保留。 */
    public void setItems(List<CameraStorage.Item> value) {
        items.clear();
        if (value != null) items.addAll(value);
        notifyDataSetChanged();
    }

    /** 缩略图 JPEG 就绪：按网格尺寸降采样解码后缓存。 */
    public void putThumbnailJpeg(long handle, byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return;
        Bitmap bitmap = decode(jpeg, TARGET_THUMBNAIL_PX);
        if (bitmap == null) return;
        thumbnails.put(handle, bitmap);
        notifyDataSetChanged();
    }

    /**
     * 切换选中。任意格式都可选中：下载仍只处理 JPEG（非 JPEG 会在下载时被跳过），
     * 但删除支持任意格式，因此不能因为「不能下载」就拒绝选中。
     */
    public void toggle(CameraStorage.Item item) {
        if (item == null) return;
        if (selected.remove(item.handle) == null) selected.put(item.handle, item);
        notifyDataSetChanged();
        notifySelection();
    }

    /** 已缓存的缩略图（可能为 null）；放大预览先显示它，再等原图替换。 */
    public Bitmap thumbnail(long handle) {
        return thumbnails.get(handle);
    }

    public boolean isSelected(long handle) {
        return selected.containsKey(handle);
    }

    public void clearSelection() {
        selected.clear();
        notifyDataSetChanged();
        notifySelection();
    }

    /**
     * 链路断开：清空当前页、缩略图与选中项。
     * 此时图片已无法再下载/预览，继续展示只会让用户误以为还能操作。
     */
    public void reset() {
        items.clear();
        thumbnails.clear();
        selected.clear();
        notifyDataSetChanged();
        notifySelection();
    }

    public int selectedCount() {
        return selected.size();
    }

    /** 已选对象（含其它页选中的），按选择顺序返回。 */
    public List<CameraStorage.Item> selectedItems() {
        return new ArrayList<>(selected.values());
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).handle;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View cell = convertView;
        if (cell == null) {
            cell = inflater.inflate(R.layout.grid_item_photo, parent, false);
        }
        final CameraStorage.Item item = items.get(position);
        ImageView thumb = cell.findViewById(R.id.thumb);
        TextView caption = cell.findViewById(R.id.caption);
        TextView indicator = cell.findViewById(R.id.selectIndicator);

        Bitmap bitmap = thumbnails.get(item.handle);
        if (bitmap != null) {
            thumb.setImageBitmap(bitmap);
        } else {
            // 尚未取到缩略图：先占位，避免复用视图时显示上一张的图。
            thumb.setImageDrawable(null);
        }
        caption.setText(String.format(
                Locale.ROOT, "%s · %s", item.filename, readableSize(item.sizeBytes)));
        // 右上角指示点：任意格式都可选中（选中可用于删除；下载只处理其中的 JPEG）。
        // 它自己消费点击（点它 = 切换选中），点缩略图其它区域仍走 GridView 的点击 = 放大预览。
        boolean checked = selected.containsKey(item.handle);
        indicator.setVisibility(View.VISIBLE);
        indicator.setSelected(checked);
        indicator.setText(checked ? context.getString(R.string.selected_mark) : "");
        indicator.setOnClickListener(view -> toggle(item));
        return cell;
    }

    private void notifySelection() {
        if (selectionListener != null) selectionListener.onSelectionChanged(selected.size());
    }

    private static String readableSize(long bytes) {
        if (bytes <= 0) return "—";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** 两遍解码：先读尺寸算 inSampleSize，再真正解码，避免原图位图撑爆内存。 */
    private static Bitmap decode(byte[] jpeg, int targetPx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (targetPx > 0 && longest / sample > targetPx * 2) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
    }
}
