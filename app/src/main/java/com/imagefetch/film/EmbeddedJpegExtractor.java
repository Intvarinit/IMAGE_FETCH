package com.imagefetch.film;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 从 RAW 容器里取出机身内嵌的 JPEG 预览。
 *
 * <p>CR3 是 ISO BMFF 容器，预览图与缩略图都以完整 JPEG 存放，但不同机型的 box 组织
 * 并不统一；这里不解析 box 树，直接按 JPEG 段结构扫描整份文件，取最长的一段，
 * 对 CR3/CR2/NEF/ARW 都适用。EXIF 里嵌套的缩略图会被 APP1 的段长跳过，不会被误选。
 *
 * <p>注意：EOS RP 的 CR3 内嵌预览通常约 1620×1080，不是全尺寸；本模块只在
 * 手机屏幕上做胶片模拟与导出，这个尺寸够用，但不要把它当原图。
 */
public final class EmbeddedJpegExtractor {
    /** 小于这个长度的 JPEG 基本是缩略图，不参与“最长”比较。 */
    private static final int MIN_JPEG_BYTES = 32 * 1024;
    /** 防御异常文件：最多扫描 96 MB。 */
    private static final long MAX_SCAN_BYTES = 96L * 1024 * 1024;

    private EmbeddedJpegExtractor() {
    }

    /** 返回内嵌 JPEG 的字节；找不到时返回 null。 */
    public static byte[] extractLargestJpeg(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() <= 0) return null;
        long length = Math.min(file.length(), MAX_SCAN_BYTES);
        byte[] data = new byte[(int) length];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.readFully(data);
        }

        int bestStart = -1;
        int bestEnd = -1;
        for (int i = 0; i + 2 < data.length; i++) {
            if (data[i] != (byte) 0xFF || data[i + 1] != (byte) 0xD8
                    || data[i + 2] != (byte) 0xFF) {
                continue;
            }
            int end = jpegEnd(data, i);
            if (end > i && end - i >= MIN_JPEG_BYTES && end - i > bestEnd - bestStart) {
                bestStart = i;
                bestEnd = end;
                i = end - 1;
            }
        }
        if (bestStart < 0) return null;

        byte[] jpeg = new byte[bestEnd - bestStart];
        System.arraycopy(data, bestStart, jpeg, 0, jpeg.length);
        return jpeg;
    }

    /** 从 SOI 的偏移算到 EOI 之后；不是一个完整 JPEG 时返回 -1。 */
    private static int jpegEnd(byte[] data, int soi) {
        int i = soi + 2;
        while (i + 3 < data.length) {
            if (data[i] != (byte) 0xFF) {
                // 正常 JPEG 不会在这里出现非标记字节，出现就当作坏数据放弃。
                return -1;
            }
            int marker = data[i + 1] & 0xFF;
            if (marker == 0xD9) {
                return i + 2;
            }
            if (marker == 0xDA) {
                // SOS 之后的熵编码数据要一直扫到下一个真正的标记。
                int segment = 2 + readU16(data, i + 2);
                i += segment;
                while (i + 1 < data.length) {
                    if (data[i] == (byte) 0xFF) {
                        int next = data[i + 1] & 0xFF;
                        if (next == 0xD9) return i + 2;
                        if (next == 0x00 || (next >= 0xD0 && next <= 0xD7)) {
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;
                continue;
            }
            int segment = 2 + readU16(data, i + 2);
            if (segment <= 2) return -1;
            i += segment;
        }
        return -1;
    }

    private static int readU16(byte[] data, int offset) {
        if (offset + 1 >= data.length) return 0;
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }
}
