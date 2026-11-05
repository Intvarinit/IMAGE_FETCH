package com.imagefetch.storage;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.File;

/**
 * 相机 JPEG 的拍摄方向通常只写在 EXIF 里，像素数据本身并不旋转。
 * BitmapFactory 解码会忽略该标记，竖拍照片直接显示就会横躺（宽高比例看起来是反的），
 * 因此解码后按 EXIF 方向统一摆正。读不到 EXIF 时原样返回。
 */
public final class JpegOrientation {

    private JpegOrientation() {
    }

    public static Bitmap applyFromFile(File file, Bitmap source) {
        if (source == null) return null;
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            return apply(
                    exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
                    source);
        } catch (Exception | OutOfMemoryError ignored) {
            // 个别文件没有 EXIF 段或读取失败：按解码后的原样显示，不影响看图。
            return source;
        }
    }

    private static Bitmap apply(int orientation, Bitmap source) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                // 5：沿主对角线翻，等于先转 90° 再水平镜像。
                matrix.postRotate(90);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                // 7：沿副对角线翻，等于先转 270° 再水平镜像。
                matrix.postRotate(270);
                matrix.postScale(-1f, 1f);
                break;
            default:
                return source;
        }
        Bitmap oriented = Bitmap.createBitmap(
                source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        if (oriented != source) source.recycle();
        return oriented;
    }
}
