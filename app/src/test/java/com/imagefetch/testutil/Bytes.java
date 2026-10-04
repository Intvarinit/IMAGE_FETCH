package com.imagefetch.testutil;

import java.nio.charset.StandardCharsets;

/** 测试用的小端字节工具（PTP/IP 与 PTP 数据集均为 LE）。 */
public final class Bytes {
    public static byte[] u16(long value) {
        return new byte[]{(byte) value, (byte) (value >> 8)};
    }

    public static byte[] u32(long value) {
        return new byte[]{
                (byte) value,
                (byte) (value >> 8),
                (byte) (value >> 16),
                (byte) (value >> 24)};
    }

    public static byte[] u64(long value) {
        return concat(u32(value & 0xffff_ffffL), u32((value >>> 32) & 0xffff_ffffL));
    }

    public static void putU16(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >> 8);
    }

    public static void putU32(byte[] target, int offset, long value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >> 8);
        target[offset + 2] = (byte) (value >> 16);
        target[offset + 3] = (byte) (value >> 24);
    }

    public static int getU16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    public static long getU32(byte[] data, int offset) {
        return (data[offset] & 0xffL)
                | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24);
    }

    /** PTP STR：[u8 字符数（含终止符）] + UTF-16LE + 终止符。 */
    public static byte[] ptpString(String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_16LE);
        byte[] result = new byte[1 + text.length + 2];
        result[0] = (byte) (value.length() + 1);
        System.arraycopy(text, 0, result, 1, text.length);
        return result;
    }

    public static byte[] utf16(String value) {
        return value.getBytes(StandardCharsets.UTF_16LE);
    }

    public static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    public static byte[] zeros(int count) {
        return new byte[count];
    }

    private Bytes() {}
}
