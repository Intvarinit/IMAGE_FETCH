package com.imagefetch.capture;

import java.util.Locale;

/**
 * 机内 HDR 拍摄设置（0xD13D {@code EOS_HDRSetting}）的参数。
 *
 * <h3>证据从哪来</h3>
 * <ul>
 *   <li>libgphoto2 的 ptp.h 只登记了名字（{@code PTP_DPC_CANON_EOS_HDRSetting}），
 *       config.c 里没有 d13d 条目，各机型 dump 一律只有「Unknown EOS property d13d,
 *       datasize is 24」——字段含义没有文档。</li>
 *   <li>本工程的能力探测（P0，EOS RP 真机）确认这条属性<b>可写</b>：原值回写回 {@code 0x2001}
 *       且回读一致，说明 {@code 0x9110} 能吃下 24 字节长载荷。</li>
 *   <li>差分实测：机身把 HDR 从「关」改成「自动」后，24 字节里只有<b>偏移 4</b> 由
 *       {@code 00 00 00 00} 变 {@code 01 00 00 00}，其余字节不动；同一轮里 {@code 0xD1DC}
 *       也从 1 变 3（那是相机自己的连带改动，本类不碰）。</li>
 * </ul>
 *
 * <p>所以这里只认定一件事：<b>偏移 4 的 u32 是模式</b>，0 = 关、1 = 自动。
 * 其余模式（机身菜单里还有带曝光补偿量的几档）的取值没有任何证据，界面不假装知道，
 * 只给一个可以自己填的原始数值输入。
 *
 * <p>与「间隔定时拍摄」同样的谨慎：{@link #applyTo} 只覆盖偏移 4，其余 20 字节原样保留
 * 从相机读回来的内容。
 */
public final class HdrPlan {
    /** 0xD13D 的取值区域长度（机型 dump 与实测都是 24）。 */
    public static final int BLOCK_LENGTH = 24;
    /** 已由差分实测证实的模式字段所在的 u32 偏移。 */
    public static final int OFFSET_MODE = 4;
    /** 模式：HDR 关闭。 */
    public static final long MODE_OFF = 0L;
    /** 模式：HDR 自动。 */
    public static final long MODE_AUTO = 1L;
    /** 模式字段是 u32，取值上限。 */
    public static final long MAX_MODE = 0xffff_ffffL;

    private final long mode;

    public HdrPlan(long mode) {
        if (mode < 0 || mode > MAX_MODE) {
            throw new IllegalArgumentException("HDR 模式取值必须落在 0–" + MAX_MODE + " 之间");
        }
        this.mode = mode;
    }

    /** 从相机读回的 24 字节区域里读出模式。 */
    public static HdrPlan fromBlock(byte[] block) {
        requireBlock(block);
        return new HdrPlan(getU32(block, OFFSET_MODE));
    }

    /**
     * 把模式写进 {@code original} 的副本：只覆盖偏移 4，其余字节原样保留。
     *
     * @param original 从相机读回来的 24 字节区域（保存着尚未解读的字段）
     */
    public byte[] applyTo(byte[] original) {
        requireBlock(original);
        byte[] result = original.clone();
        putU32(result, OFFSET_MODE, mode);
        return result;
    }

    public long mode() {
        return mode;
    }

    public String describe() {
        return "模式 " + describeMode(mode);
    }

    /** 已知取值给名字，其余如实说是未知码——不能替相机编档位名。 */
    public static String describeMode(long mode) {
        if (mode == MODE_OFF) return "0（关闭）";
        if (mode == MODE_AUTO) return "1（自动）";
        return String.format(Locale.ROOT, "0x%08X（未证实）", mode);
    }

    /**
     * 把 24 字节区域拆成 6 个 u32 分组展示，方便在机身屏幕上对照核对。
     * 只有偏移 4 是已证实的字段，其余位置的含义没有证据。
     */
    public static String describeBlock(byte[] block) {
        if (block == null || block.length == 0) return "（未读到）";
        StringBuilder text = new StringBuilder();
        text.append(block.length).append(" 字节：\n");
        for (int offset = 0; offset + 4 <= block.length; offset += 4) {
            String mark = offset == OFFSET_MODE ? "   ← 已证实：模式" : "";
            text.append(String.format(Locale.ROOT, "  +%02d: %08X = %d%s%n",
                    offset, getU32(block, offset), getU32(block, offset), mark));
        }
        if (block.length % 4 != 0) {
            text.append("  （余 ").append(block.length % 4).append(" 字节未按 u32 分组）\n");
        }
        return text.toString();
    }

    private static void requireBlock(byte[] block) {
        if (block == null || block.length != BLOCK_LENGTH) {
            throw new IllegalArgumentException("0xD13D 的取值区域应为 "
                    + BLOCK_LENGTH + " 字节，实际 " + (block == null ? "null" : block.length + " 字节"));
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