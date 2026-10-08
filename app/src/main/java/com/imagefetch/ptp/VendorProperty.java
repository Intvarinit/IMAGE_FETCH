package com.imagefetch.ptp;

import java.util.Locale;

/**
 * Canon EOS 厂商属性码（PTP 厂商属性段 {@code 0xD000–0xDFFF}）及其名称。
 *
 * <p>码值与命名取自 libgphoto2 {@code camlibs/ptp2/ptp.h}（本工程的一手参考资料）：
 * {@code 0xD11D BracketMode}（ptp.h:2119）、{@code 0xD134 IntervalShootSetting}（ptp.h:2140）、
 * {@code 0xD135 IntervalShootState}（ptp.h:2141）、{@code 0xD13D HDRSetting}（ptp.h:2149）。
 *
 * <p><b>这些属性没有同步读接口</b>：标准 {@code 0x1015 GetDevicePropValue} 对厂商属性段一律回
 * {@code 0x2005 DevicePropNotSupported}，当前值只能从 {@code 0x9116 GetEvent} 事件块里的
 * {@code 0xC189}/{@code 0xC18A} 记录取。
 */
public final class VendorProperty {
    /** 取景输出设备掩码（bit0=TFT 机身屏、bit1=PC、bit2=MOBILE、bit3=MOBILE2）。 */
    public static final int EVF_OUTPUT_DEVICE = 0xd1b0;
    /** 取景模式（写 1 = 开）。 */
    public static final int EVF_MODE = 0xd1b1;
    /** 实时取景的整体对焦方式（单点/区域等）。 */
    public static final int AF_METHOD = 0xd1ba;
    /** 对焦模式。 */
    public static final int FOCUS_MODE = 0xd108;
    /** 包围模式（曝光/ISO/WB/FE 等）。 */
    public static final int BRACKET_MODE = 0xd11d;
    /** 间隔定时拍摄设置。 */
    public static final int INTERVAL_SHOOT_SETTING = 0xd134;
    /** 间隔定时拍摄状态（0 = 空闲）。 */
    public static final int INTERVAL_SHOOT_STATE = 0xd135;
    /** HDR 拍摄设置。 */
    public static final int HDR_SETTING = 0xd13d;
    /**
     * 对焦偏移设置（= 机身菜单里的「对焦包围」）。
     *
     * <p>这不是从文档查来的，是差分实测确认的：在 EOS RP 上把对焦包围从「关」改成
     * 「开 / 4 张 / 对焦增量 4 / 曝光平滑化关」，{@code 0xD208} 的 24 字节值区域出现
     * {@code 18 00 00 00 | 01 00 00 00 | 1 | 4 | 4 | 0}（第 8 字节 0→1、第 12 字节 = 张数、
     * 第 16 字节 = 对焦增量、第 20 字节 = 曝光平滑化）。libgphoto2 把它登记为
     * {@code PTP_DPC_CANON_EOS_FocusShiftSetting}（ptp.h:2307），但 config.c 里没有条目，
     * 也没有任何字段定义——所以上面这套偏移是<b>本工程实测</b>的结论，仍属「待真机复核」。
     */
    public static final int FOCUS_SHIFT_SETTING = 0xd208;

    /**
     * P0 能力探测的目标属性，顺序即界面展示顺序。前四个属性在 Canon EOS RP 的握手事件流里
     * 都有 info 记录（canon-eos-r.txt 的 event 63/95/104/105），所以「存在」这一列预期为真；
     * {@link #FOCUS_SHIFT_SETTING} 是差分实测后补进来的（对焦包围）。
     */
    public static final int[] PROBE_TARGETS = {
            BRACKET_MODE,
            INTERVAL_SHOOT_SETTING,
            INTERVAL_SHOOT_STATE,
            HDR_SETTING,
            FOCUS_SHIFT_SETTING,
    };

    /** 属性的中文说明；未登记的自定义码返回 {@code null}（由调用方决定怎么显示）。 */
    public static String name(int propertyCode) {
        switch (propertyCode) {
            case EVF_OUTPUT_DEVICE:
                return "取景输出设备";
            case EVF_MODE:
                return "取景模式";
            case AF_METHOD:
                return "实时取景对焦方式";
            case FOCUS_MODE:
                return "对焦模式";
            case BRACKET_MODE:
                return "包围模式";
            case INTERVAL_SHOOT_SETTING:
                return "间隔定时拍摄设置";
            case INTERVAL_SHOOT_STATE:
                return "间隔定时拍摄状态";
            case HDR_SETTING:
                return "HDR 拍摄设置";
            case FOCUS_SHIFT_SETTING:
                return "对焦偏移设置（对焦包围）";
            default:
                return null;
        }
    }

    /** 属性的 libgphoto2 符号名；未登记返回空串。用于诊断里对齐上游命名。 */
    public static String symbol(int propertyCode) {
        switch (propertyCode) {
            case BRACKET_MODE:
                return "EOS_BracketMode";
            case INTERVAL_SHOOT_SETTING:
                return "EOS_IntervalShootSetting";
            case INTERVAL_SHOOT_STATE:
                return "EOS_IntervalShootState";
            case HDR_SETTING:
                return "EOS_HDRSetting";
            case FOCUS_SHIFT_SETTING:
                return "EOS_FocusShiftSetting";
            case FOCUS_MODE:
                return "EOS_FocusMode";
            case AF_METHOD:
                return "EOS_LvAfSystem";
            case EVF_OUTPUT_DEVICE:
                return "EOS_EVFOutputDevice";
            case EVF_MODE:
                return "EOS_EVFMode";
            default:
                return "";
        }
    }

    /** 形如 {@code 0xD11D} 的属性码文案。 */
    public static String hex(int propertyCode) {
        return String.format(Locale.ROOT, "0x%04X", propertyCode);
    }

    /** 「0xD11D BracketMode（包围模式）」这样的完整标签。 */
    public static String label(int propertyCode) {
        String name = name(propertyCode);
        String symbol = symbol(propertyCode);
        StringBuilder text = new StringBuilder(hex(propertyCode));
        if (!symbol.isEmpty()) text.append(' ').append(symbol);
        if (name != null) text.append('（').append(name).append('）');
        return text.toString();
    }

    private VendorProperty() {
    }
}