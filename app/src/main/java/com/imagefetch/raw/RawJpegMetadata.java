package com.imagefetch.raw;

import android.media.ExifInterface;
import android.util.Log;

import java.io.File;

/**
 * 给 RAW 显影出的 JPG 补最关键的 EXIF。
 *
 * <p>LibRaw 只负责像素；相机机型与拍摄时间来自 PTP ObjectInfo，补进去后相册才能按
 * 拍摄时间排序。像素已经由 LibRaw 按 sensor 方向转正，所以 Orientation 固定写 1。
 * 元数据写入失败不影响成品，只记日志。
 */
public final class RawJpegMetadata {
    private static final String TAG = "RawJpegMetadata";

    private RawJpegMetadata() {
    }

    public static void apply(File jpeg, String capturedAt, String cameraModel) {
        try {
            ExifInterface exif = new ExifInterface(jpeg.getAbsolutePath());
            exif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            String dateTime = exifDateTime(capturedAt);
            if (dateTime != null) {
                exif.setAttribute(ExifInterface.TAG_DATETIME, dateTime);
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime);
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateTime);
            }
            if (cameraModel != null && !cameraModel.isEmpty()) {
                exif.setAttribute(ExifInterface.TAG_MODEL, cameraModel);
            }
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "IMAGE_FETCH / LibRaw");
            exif.saveAttributes();
        } catch (Exception error) {
            Log.w(TAG, "写入 RAW 显影 JPG 的 EXIF 失败：" + error.getMessage());
        }
    }

    /** ObjectInfo 的 "2026-01-01 12:00:00" → EXIF 的 "2026:01:01 12:00:00"。 */
    static String exifDateTime(String capturedAt) {
        if (capturedAt == null || capturedAt.length() < 19) return null;
        return capturedAt.substring(0, 10).replace('-', ':') + capturedAt.substring(10);
    }
}
