package com.imagefetch.storage;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 把已落盘的原图导出到系统相册（MediaStore），使用户在系统图库中直接可见。
 *
 * <p>与原图落盘分离：应用文件库中的副本始终是"真源"，相册导出失败不回滚落盘，
 * 只如实上报，避免把系统相册权限问题与相机链路耦合在一起。
 */
public final class GalleryExporter {
    /** 相册子目录名：Android 9 及以下作为 DCIM 下的目录名，10+ 作为 RELATIVE_PATH 尾段。 */
    public static final String ALBUM_FOLDER = "IMAGE_FETCH";
    /** 相册内目标目录（Android 10+ 的 RELATIVE_PATH）：放在 DCIM 下，图库才会正常收录。 */
    public static final String RELATIVE_PATH = "DCIM/" + ALBUM_FOLDER;

    private final Context context;

    public GalleryExporter(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 导出到相册。
     * Android 10+ 走分区存储：无需存储权限，用 IS_PENDING 保证条目不会以半成品可见；
     * Android 9 及以下需要 WRITE_EXTERNAL_STORAGE，未授权时抛出 SecurityException 由调用方上报。
     */
    public Uri export(File source, String displayName) throws IOException {
        if (source == null || !source.isFile() || source.length() == 0) {
            throw new IOException("待导出的文件不存在或为空。");
        }
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(displayName));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        } else {
            values.put(MediaStore.MediaColumns.DATA, legacyTargetPath(displayName));
        }

        Uri target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (target == null) throw new IOException("系统相册拒绝了写入请求。");
        try {
            try (OutputStream output = resolver.openOutputStream(target)) {
                if (output == null) throw new IOException("无法打开相册写入流。");
                try (FileInputStream input = new FileInputStream(source)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (count > 0) output.write(buffer, 0, count);
                    }
                }
                output.flush();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues publish = new ContentValues();
                publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(target, publish, null, null);
            }
        } catch (Exception error) {
            // 失败不得留下半成品条目。
            resolver.delete(target, null, null);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException(
                    error.getMessage() == null ? "相册写入失败。" : error.getMessage(), error);
        }
        return target;
    }

    /**
     * Android 9 及以下没有分区存储，只能用 DATA 指定绝对路径；
     * 目标目录与 10+ 的 RELATIVE_PATH 保持一致（外置存储 DCIM/IMAGE_FETCH）。
     */
    @SuppressWarnings("deprecation")
    private static String legacyTargetPath(String displayName) throws IOException {
        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        File album = new File(dcim, ALBUM_FOLDER);
        if (!album.exists() && !album.mkdirs()) {
            throw new IOException("无法创建相册目录：" + album.getAbsolutePath());
        }
        return new File(album, displayName).getAbsolutePath();
    }

    /** 依扩展名推断 MIME；首版为 JPEG 原图，同时给出常见 RAW/HEIF 的归类。 */
    public static String mimeTypeFor(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) return "image/heic";
        if (lower.endsWith(".dng")) return "image/x-adobe-dng";
        if (lower.endsWith(".cr2")) return "image/x-canon-cr2";
        if (lower.endsWith(".cr3")) return "image/x-canon-cr3";
        if (lower.endsWith(".nef")) return "image/x-nikon-nef";
        if (lower.endsWith(".arw")) return "image/x-sony-arw";
        if (lower.endsWith(".rw2")) return "image/x-panasonic-rw2";
        if (lower.endsWith(".raf")) return "image/x-fuji-raf";
        if (lower.endsWith(".orf")) return "image/x-olympus-orf";
        return "image/*";
    }
}
