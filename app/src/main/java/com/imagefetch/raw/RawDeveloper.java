package com.imagefetch.raw;

import java.io.File;
import java.io.IOException;

/**
 * LibRaw 显影入口：把相机 RAW 转成全尺寸 JPEG。
 *
 * <p>只做显影与编码，不碰相机会话；调用方必须把它放在独立线程执行（单张 26MP RAW
 * 需要数秒和数百 MB 原生内存），不要占用 PTP 单线程执行器。
 */
public final class RawDeveloper {
    /** 显影后的像素尺寸。 */
    public static final class Result {
        public final int width;
        public final int height;

        Result(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static boolean loadAttempted;
    private static String loadFailure;

    private RawDeveloper() {
    }

    /** 原生库是否可用；不可用时给出原因（例如 ABI 不受支持）。 */
    public static synchronized boolean isAvailable() {
        ensureLoaded();
        return loadFailure == null;
    }

    public static synchronized String unavailableReason() {
        ensureLoaded();
        return loadFailure;
    }

    public static Result develop(File rawFile, File jpegFile, int quality) throws IOException {
        if (rawFile == null || !rawFile.isFile() || rawFile.length() == 0) {
            throw new IOException("待显影的 RAW 文件不存在或为空。");
        }
        ensureLoaded();
        if (loadFailure != null) throw new IOException(loadFailure);
        int[] size = nativeDevelop(rawFile.getAbsolutePath(), jpegFile.getAbsolutePath(), quality);
        if (size == null || size.length != 2 || size[0] <= 0 || size[1] <= 0) {
            throw new IOException("RAW 显影未返回有效图像尺寸。");
        }
        return new Result(size[0], size[1]);
    }

    private static void ensureLoaded() {
        if (loadAttempted) return;
        loadAttempted = true;
        try {
            System.loadLibrary("imagefetchraw");
        } catch (Throwable error) {
            String reason = error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            loadFailure = "RAW 显影库不可用：" + reason;
        }
    }

    private static native int[] nativeDevelop(String rawPath, String jpegPath, int quality)
            throws IOException;
}
