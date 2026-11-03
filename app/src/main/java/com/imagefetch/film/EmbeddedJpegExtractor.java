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
 * <p>扫描按 64 KB 分块进行，不再把整份 RAW 读进堆；CR3 动辄 30–60 MB，
 * 整文件载入会和其他预览位图一起把峰值推到 OOM。
 *
 * <p>注意：EOS RP 的 CR3 内嵌预览通常约 1620×1080，不是全尺寸；本模块只在
 * 手机屏幕上做胶片模拟与导出，这个尺寸够用，但不要把它当原图。
 */
public final class EmbeddedJpegExtractor {
    /** 小于这个长度的 JPEG 基本是缩略图，不参与“最长”比较。 */
    private static final int MIN_JPEG_BYTES = 32 * 1024;
    /** 防御异常文件：最多扫描 96 MB。 */
    private static final long MAX_SCAN_BYTES = 96L * 1024 * 1024;
    /** 扫描窗口；只常驻这一块，扫描顺序紧凑时基本不回读。 */
    private static final int SCAN_CHUNK_BYTES = 64 * 1024;

    private EmbeddedJpegExtractor() {
    }

    /** 返回内嵌 JPEG 的字节；找不到时返回 null。 */
    public static byte[] extractLargestJpeg(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() <= 0) return null;
        long limit = Math.min(file.length(), MAX_SCAN_BYTES);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            Scanner scanner = new Scanner(raf, limit);
            long[] bounds = findLargest(scanner);
            if (bounds == null) return null;
            int size = (int) (bounds[1] - bounds[0]);
            byte[] jpeg = new byte[size];
            int copied = 0;
            while (copied < size) {
                int read = scanner.read(bounds[0] + copied, jpeg, copied, size - copied);
                if (read <= 0) throw new IOException("读取内嵌 JPEG 失败。");
                copied += read;
            }
            return jpeg;
        }
    }

    /** 顺序扫描一遍，记录最长 JPEG 的起止偏移。 */
    private static long[] findLargest(Scanner in) throws IOException {
        long bestStart = -1;
        long bestEnd = -1;
        long pos = 0;
        while (true) {
            int b0 = in.readAt(pos);
            if (b0 < 0) break;
            if (b0 != 0xFF || in.readAt(pos + 1) != 0xD8 || in.readAt(pos + 2) != 0xFF) {
                pos++;
                continue;
            }
            long end = jpegEnd(in, pos);
            if (end > pos && end - pos >= MIN_JPEG_BYTES && end - pos > bestEnd - bestStart) {
                bestStart = pos;
                bestEnd = end;
                pos = end;
            } else {
                pos++;
            }
        }
        return bestStart < 0 ? null : new long[]{bestStart, bestEnd};
    }

    /** 从 SOI 的偏移算到 EOI 之后；不是一个完整 JPEG 时返回 -1。 */
    private static long jpegEnd(Scanner in, long soi) throws IOException {
        long i = soi + 2;
        while (true) {
            int b = in.readAt(i);
            if (b < 0) return -1;
            if (b != 0xFF) {
                // 正常 JPEG 不会在这里出现非标记字节，出现就当作坏数据放弃。
                return -1;
            }
            int marker = in.readAt(i + 1);
            if (marker < 0) return -1;
            if (marker == 0xD9) {
                return i + 2;
            }
            if (marker == 0xDA) {
                // SOS 之后的熵编码数据要一直扫到下一个真正的标记。
                int segment = 2 + readU16(in, i + 2);
                i += segment;
                while (true) {
                    int nextByte = in.readAt(i);
                    int nextMarker = in.readAt(i + 1);
                    if (nextByte < 0 || nextMarker < 0) return -1;
                    if (nextByte != 0xFF) {
                        i++;
                        continue;
                    }
                    if (nextMarker == 0xD9) return i + 2;
                    if (nextMarker == 0x00 || (nextMarker >= 0xD0 && nextMarker <= 0xD7)) {
                        i += 2;
                        continue;
                    }
                    break;
                }
                continue;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;
                continue;
            }
            int segment = 2 + readU16(in, i + 2);
            if (segment <= 2) return -1;
            i += segment;
        }
    }

    private static int readU16(Scanner in, long offset) throws IOException {
        int high = in.readAt(offset);
        int low = in.readAt(offset + 1);
        if (high < 0 || low < 0) return 0;
        return (high << 8) | low;
    }

    /** 顺序扫描用的窗口读取器：一次只驻留 {@link #SCAN_CHUNK_BYTES}，越界返回 -1。 */
    private static final class Scanner {
        private final RandomAccessFile file;
        private final long limit;
        private final byte[] buffer = new byte[SCAN_CHUNK_BYTES];
        private long bufferOffset = -1;
        private int bufferLength;

        Scanner(RandomAccessFile file, long limit) {
            this.file = file;
            this.limit = limit;
        }

        int readAt(long offset) throws IOException {
            if (offset < 0 || offset >= limit) return -1;
            if (offset < bufferOffset || offset >= bufferOffset + bufferLength) fill(offset);
            int index = (int) (offset - bufferOffset);
            return index < bufferLength ? buffer[index] & 0xFF : -1;
        }

        int read(long offset, byte[] target, int targetOffset, int count) throws IOException {
            if (count <= 0 || offset < 0 || offset >= limit) return -1;
            if (offset < bufferOffset || offset + count > bufferOffset + bufferLength) {
                fill(offset);
            }
            int available = (int) Math.min(count, bufferOffset + bufferLength - offset);
            if (available <= 0) return -1;
            System.arraycopy(buffer, (int) (offset - bufferOffset), target, targetOffset, available);
            return available;
        }

        private void fill(long offset) throws IOException {
            file.seek(offset);
            int want = (int) Math.min(SCAN_CHUNK_BYTES, limit - offset);
            int read = 0;
            while (read < want) {
                int n = file.read(buffer, read, want - read);
                if (n < 0) break;
                read += n;
            }
            bufferOffset = offset;
            bufferLength = read;
        }
    }
}
