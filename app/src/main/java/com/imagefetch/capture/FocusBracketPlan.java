package com.imagefetch.capture;

import java.util.Locale;

/**
 * 机内「对焦包围」设置（0xD208 {@code EOS_FocusShiftSetting}）的参数。
 *
 * <h3>证据从哪来</h3>
 * <ul>
 *   <li>libgphoto2 的 ptp.h 只登记了名字（{@code PTP_DPC_CANON_EOS_FocusShiftSetting}，
 *       ptp.h:2307），config.c 里没有 d208 条目，字段含义没有文档。</li>
 *   <li>差分实测：在 EOS RP 上把「对焦包围」从关改成「开 / 4 张 / 对焦增量 4 / 曝光平滑化关」，
 *       24 字节值区域里只有<b>偏移 8</b> 由 0 变 1——所以「偏移 8 = 开关」是<b>已证实</b>的。</li>
 *   <li>同一次设置里偏移 12 = 4、偏移 16 = 4、偏移 20 = 0，与用户当时填的「张数 4 / 增量 4 /
 *       平滑化关」逐项对上，但这只是<b>旁证</b>（一次采样、三个字段同时变），不排除是别的字段。</li>
 * </ul>
 *
 * <p>所以这里按「已证实」与「旁证」两档标注：{@link #OFFSET_ENABLED} 之外的三个字段在报告里
 * 一律标明是旁证。与「HDR 拍摄」同样的谨慎——{@link #applyTo} 只覆盖这四个 u32，其余 8 字节
 * 原样保留从相机读回来的内容。
 */
public final class FocusBracketPlan {
    /** 0xD208 的取值区域长度（机型 dump 与实测都是 24）。 */
    public static final int BLOCK_LENGTH = 24;
    /** 已由差分实测证实的开关字段所在的 u32 偏移。 */
    public static final int OFFSET_ENABLED = 8;
    /** 旁证：张数。 */
    public static final int OFFSET_COUNT = 12;
    /** 旁证：对焦增量。 */
    public static final int OFFSET_INCREMENT = 16;
    /** 旁证：曝光平滑化。 */
    public static final int OFFSET_SMOOTHING = 20;
    /** 开关：关。 */
    public static final long ENABLED_OFF = 0L;
    /** 开关：开。 */
    public static final long ENABLED_ON = 1L;
    /** 各字段都是 u32，取值上限。 */
    public static final long MAX_FIELD = 0xffff_ffffL;

    private final long enabled;
    private final long count;
    private final long increment;
    private final long smoothing;

    public FocusBracketPlan(long enabled, long count, long increment, long smoothing) {
        requireField(enabled, "开关");
        requireField(count, "张数");
        requireField(increment, "对焦增量");
        requireField(smoothing, "曝光平滑化");
        if (enabled != ENABLED_OFF && count < 1) {
            throw new IllegalArgumentException("开启对焦包围时张数至少为 1（当前 " + count + "）");
        }
        this.enabled = enabled;
        this.count = count;
        this.increment = increment;
        this.smoothing = smoothing;
    }

    /** 从相机读回的 24 字节区域里读出四个字段。 */
    public static FocusBracketPlan fromBlock(byte[] block) {
        requireBlock(block);
        return new FocusBracketPlan(
                getU32(block, OFFSET_ENABLED),
                getU32(block, OFFSET_COUNT),
                getU32(block, OFFSET_INCREMENT),
                getU32(block, OFFSET_SMOOTHING));
    }

    /**
     * 把四个字段写进 {@code original} 的副本：只覆盖偏移 8/12/16/20，其余 8 字节原样保留。
     *
     * @param original 从相机读回来的 24 字节区域（保存着尚未解读的字段）
     */
    public byte[] applyTo(byte[] original) {
        requireBlock(original);
        byte[] result = original.clone();
        putU32(result, OFFSET_ENABLED, enabled);
        putU32(result, OFFSET_COUNT, count);
        putU32(result, OFFSET_INCREMENT, increment);
        putU32(result, OFFSET_SMOOTHING, smoothing);
        return result;
    }

    public long enabled() {
        return enabled;
    }

    public long count() {
        return count;
    }

    public long increment() {
        return increment;
    }

    public long smoothing() {
        return smoothing;
    }

    public boolean isOn() {
        return enabled == ENABLED_ON;
    }

    public String describe() {
        return "开关 " + describeEnabled(enabled)
                + " / 张数 " + count
                + " / 对焦增量 " + increment
                + " / 曝光平滑化 " + smoothing;
    }

    /** 已知取值给名字，其余如实说是未知码——不能替相机编档位名。 */
    public static String describeEnabled(long enabled) {
        if (enabled == ENABLED_OFF) return "0（关闭）";
        if (enabled == ENABLED_ON) return "1（开启）";
        return String.format(Locale.ROOT, "0x%08X（未证实）", enabled);
    }

    /**
     * 把 24 字节区域拆成 6 个 u32 分组展示，方便在机身屏幕上对照核对。
     * 只有偏移 8 是已证实的字段，偏移 12/16/20 只是旁证。
     */
    public static String describeBlock(byte[] block) {
        if (block == null || block.length == 0) return "（未读到）";
        StringBuilder text = new StringBuilder();
        text.append(block.length).append(" 字节：\n");
        for (int offset = 0; offset + 4 <= block.length; offset += 4) {
            long value = getU32(block, offset);
            text.append(String.format(Locale.ROOT, "  +%02d: %08X = %d%s%n",
                    offset, value, value, markFor(offset)));
        }
        if (block.length % 4 != 0) {
            text.append("  （余 ").append(block.length % 4).append(" 字节未按 u32 分组）\n");
        }
        return text.toString();
    }

    private static String markFor(int offset) {
        switch (offset) {
            case OFFSET_ENABLED:
                return "   ← 已证实：开关";
            case OFFSET_COUNT:
                return "   ← 旁证：张数";
            case OFFSET_INCREMENT:
                return "   ← 旁证：对焦增量";
            case OFFSET_SMOOTHING:
                return "   ← 旁证：曝光平滑化";
            default:
                return "";
        }
    }

    private static void requireBlock(byte[] block) {
        if (block == null || block.length != BLOCK_LENGTH) {
            throw new IllegalArgumentException("0xD208 的取值区域应为 "
                    + BLOCK_LENGTH + " 字节，实际 " + (block == null ? "null" : block.length + " 字节"));
        }
    }

    private static void requireField(long value, String label) {
        if (value < 0 || value > MAX_FIELD) {
            throw new IllegalArgumentException(label + "取值必须落在 0–" + MAX_FIELD + " 之间");
        }
    }

    private static long getU32(byte[] data, int offset) {
        return (data[offset] & 0xffL)
                | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24);
    }

    private static void putU32(byte[] target, int offset, long value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >> 8);
        target[offset + 2] = (byte) (value >> 16);
        target[offset + 3] = (byte) (value >> 24);
    }
}
