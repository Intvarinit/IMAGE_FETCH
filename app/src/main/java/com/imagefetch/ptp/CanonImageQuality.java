package com.imagefetch.ptp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Canon EOS 画质（{@code 0xD120 EOS_ImageFormat}）值区域的解析规则。
 *
 * <p><b>为什么不能复用现有解析</b>：这条属性的值区域不是普通 u16，而是一段<b>变长 u32 序列</b>
 * （libgphoto2 {@code ptp_unpack_EOS_ImageFormat} 的注释，ptp-pack.c:1471-1509）：
 * <pre>
 *   [n][l=0x10][type][size][compression]              n == 1，共 5 个 u32
 *   [n][l=0x10][type1][size1][comp1][l=0x10][type2][size2][comp2]   n == 2，共 9 个 u32
 * </pre>
 * 其中 {@code type}: 1 = JPG，6 = RAW；{@code size}: 0 = L、1 = M、2 = S、5 = M1、6 = M2、
 * {@code 0xe} = S1、{@code 0xf} = S2、{@code 0x10} = S3；{@code compression}: 2 = coarse、
 * 3 = fine（RAW 时为 cRAW）、4 = lossless。{@code 0xC189} 推来的当前值与 {@code 0xC18A}
 * 推来的允许值列表的<b>每一项</b>都是这样一段序列（ptp-pack.c:2052-2060 对 ImageFormat 家族
 * 按变长前进）。传输层的 {@code propValueOf}/{@code propEnumOf} 是按固定 stride 取 u16 写的，
 * 对这条属性只会读到 {@code n} 或 {@code type}——所以画质必须走本类。
 *
 * <p><b>写入为什么回放原始字节而不是反打包 u16</b>：libgphoto2 走「u16 → {@code ptp_pack_EOS_ImageFormat}
 * 反打包」，但该编码在 n==1 与 n==2 之间有不可逆歧义（ptp-pack.c:1581-1582 把 s2==0&&c2==0
 * 一律改写成 0xF，于是「单文件」和「L + user-L」混为一谈）。所以这里只把选项在允许列表里的
 * <b>原始字节</b>原样交给 {@code 0x9110} —— 那是相机自己声明支持的那组字节，语义无歧义。
 *
 * <p>标签规则对照 libgphoto2 {@code _get_Canon_EOS_ImageFormat} 与
 * {@code canon_eos_single_ImageFormats}（config.c:3138-3172），但由原始字节直接生成，
 * 因此不会出现「L + L」这类由 u16 歧义造成的假标签。
 *
 * <p>纯算术，不碰 Android，可直接单测（见 {@code CanonImageQualityTest}）。
 */
public final class CanonImageQuality {
    /** 画质（图像格式）。EOS 上是「文件设置」，见 ptp.h:2122、config.c:12062。 */
    public static final int IMAGE_FORMAT = 0xd120;
    /** 画质的 CF 卡变体。 */
    public static final int IMAGE_FORMAT_CF = 0xd121;
    /** 画质的 SD 卡变体。 */
    public static final int IMAGE_FORMAT_SD = 0xd122;
    /** 画质的扩展存储变体。 */
    public static final int IMAGE_FORMAT_EXT = 0xd123;

    /** 每个条目里 {@code l} 字段的固定值：该条目的字节数（4 × u32）。 */
    private static final int ENTRY_LENGTH_FIELD = 0x10;
    /** n == 1 时的条目字节数：n、l、type、size、compression 共 5 个 u32。 */
    private static final int ENTRY_BYTES_SINGLE = 4 + ENTRY_LENGTH_FIELD;
    /** n == 2 时的条目字节数：多出一组 l、type、size、compression。 */
    private static final int ENTRY_BYTES_PAIR = 4 + 2 * ENTRY_LENGTH_FIELD;

    private static final int TYPE_JPG = 1;
    private static final int TYPE_RAW = 6;

    /** 单个画质选项：给界面显示的标签 + 写回相机时原样回放的字节。 */
    public static final class Option {
        public final String label;
        /** 允许列表里该选项的原始字节区域（写回相机时原样使用）。 */
        public final byte[] raw;
        private final boolean jpeg;
        private final boolean rawFormat;

        Option(String label, byte[] raw, boolean jpeg, boolean rawFormat) {
            this.label = label;
            this.raw = raw;
            this.jpeg = jpeg;
            this.rawFormat = rawFormat;
        }

        /** 该选项是否会在卡上产生 JPEG（决定 App 能不能取回/合成）。 */
        public boolean hasJpeg() {
            return jpeg;
        }

        /** 该选项是否会在卡上产生 RAW。 */
        public boolean hasRaw() {
            return rawFormat;
        }
    }

    /** 下拉框要用的解析结果。 */
    public static final class Choices {
        /** 可选画质，保持相机给的顺序；相机没给列表时为空表。 */
        public final List<Option> options;
        /** 当前值在 {@link #options} 里的下标；未知为 -1。 */
        public final int currentIndex;
        /** 当前画质是否含 JPEG。 */
        public final boolean currentHasJpeg;
        /** true 表示 {@link #options} 来自相机上报；false 表示相机没给列表。 */
        public final boolean fromCamera;

        Choices(List<Option> options, int currentIndex, boolean currentHasJpeg, boolean fromCamera) {
            this.options = options;
            this.currentIndex = currentIndex;
            this.currentHasJpeg = currentHasJpeg;
            this.fromCamera = fromCamera;
        }

        public String[] labels() {
            String[] labels = new String[options.size()];
            for (int i = 0; i < options.size(); i++) labels[i] = options.get(i).label;
            return labels;
        }
    }

    private CanonImageQuality() {
    }

    /**
     * 解析一整段值区域（可能是若干个条目首尾相接，{@code 0xC18A} 的允许列表就是这样）。
     * 遇到不合法的条目立刻停止并返回已解析的部分——事件块里可能有截断数据，
     * 这里绝不抛异常。
     */
    public static List<Option> parseList(byte[] region) {
        List<Option> options = new ArrayList<>();
        if (region == null) return options;
        int offset = 0;
        while (offset + ENTRY_BYTES_SINGLE <= region.length) {
            Option option = parseAt(region, offset);
            if (option == null) break;
            options.add(option);
            offset += option.raw.length;
        }
        return options;
    }

    /** 解析一个条目（当前值用）；区域为空或不是一个合法条目时返回 {@code null}。 */
    public static Option parseOne(byte[] region) {
        if (region == null || region.length < ENTRY_BYTES_SINGLE) return null;
        return parseAt(region, 0);
    }

    /** 一段条目字节里是否含 JPEG。 */
    public static boolean hasJpeg(byte[] region) {
        Option option = parseOne(region);
        return option != null && option.hasJpeg();
    }

    /** 在选项表里按原始字节定位当前值；没命中返回 -1。 */
    public static int indexOfRaw(List<Option> options, byte[] raw) {
        if (options == null || raw == null) return -1;
        for (int i = 0; i < options.size(); i++) {
            if (Arrays.equals(options.get(i).raw, raw)) return i;
        }
        return -1;
    }

    /**
     * 「自动 JPG 兜底」的选取规则：优先选<b>既含 RAW 又含 JPEG</b> 的项（切过去不丢 RAW），
     * 其次第一个纯 JPEG 项；列表里没有任何含 JPEG 的项时返回 {@code null}。
     */
    public static Option firstJpegOption(List<Option> options) {
        if (options == null) return null;
        Option jpegOnly = null;
        for (Option option : options) {
            if (!option.hasJpeg()) continue;
            if (option.hasRaw()) return option;
            if (jpegOnly == null) jpegOnly = option;
        }
        return jpegOnly;
    }

    /**
     * 由相机上报的允许列表与当前值构造下拉框选项。相机没给列表时返回空表
     * （这里<b>不发明候选值</b>：写错画质会改变相机存储的既有行为，比界面留空更糟）；
     * 上报的列表不含当前值时把当前值追加到末尾并选中它，避免下游把第 0 项错显为选中。
     */
    public static Choices resolve(byte[] allowedRegion, byte[] currentRaw) {
        List<Option> options = parseList(allowedRegion);
        if (options.isEmpty()) return new Choices(options, -1, false, false);
        int index = indexOfRaw(options, currentRaw);
        if (index < 0) {
            Option current = parseOne(currentRaw);
            if (current != null) {
                options.add(current);
                index = options.size() - 1;
            }
        }
        boolean hasJpeg = index >= 0 && options.get(index).hasJpeg();
        return new Choices(options, index, hasJpeg, true);
    }

    /** 从 {@code offset} 处解析一个条目；不合法返回 {@code null}。 */
    private static Option parseAt(byte[] region, int offset) {
        int available = region.length - offset;
        if (available < ENTRY_BYTES_SINGLE) return null;
        long n = u32(region, offset);
        if (n != 1 && n != 2) return null;
        if (u32(region, offset + 4) != ENTRY_LENGTH_FIELD) return null;
        int needed = n == 2 ? ENTRY_BYTES_PAIR : ENTRY_BYTES_SINGLE;
        if (available < needed) return null;

        int type1 = (int) u32(region, offset + 8);
        int size1 = (int) u32(region, offset + 12);
        int compression1 = (int) u32(region, offset + 16);
        String label = entryName(type1, size1, compression1);
        boolean jpeg = type1 == TYPE_JPG;
        boolean rawFormat = type1 == TYPE_RAW;

        if (n == 2) {
            if (u32(region, offset + 20) != ENTRY_LENGTH_FIELD) return null;
            int type2 = (int) u32(region, offset + 24);
            int size2 = (int) u32(region, offset + 28);
            int compression2 = (int) u32(region, offset + 32);
            label = label + " + " + entryName(type2, size2, compression2);
            jpeg = jpeg || type2 == TYPE_JPG;
            rawFormat = rawFormat || type2 == TYPE_RAW;
        }
        return new Option(label, Arrays.copyOfRange(region, offset, offset + needed), jpeg, rawFormat);
    }

    /**
     * 单个生成文件的名称。尺寸用佳能机身菜单里的中文名（大/中/小/中1/中2/小1/小2/小3），
     * 并在前面标出这是 JPG 还是 RAW——原来只有 {@code L}/{@code cL} 这类代号，用户看不懂哪个能出 JPG。
     * 精细（compression 3）是常态，不加后缀；其余压缩档位补一个括号说明。
     */
    private static String entryName(int type, int size, int compression) {
        if (type == TYPE_RAW) return compression == 3 ? "cRAW" : "RAW";
        String sizeName = sizeName(size);
        if (sizeName == null) return "类型 " + type;
        return "JPG " + sizeName + compressionSuffix(compression);
    }

    /** 压缩档位的中文后缀（精细是常态，留空）。语义对照 ptp-pack.c:1487-1492。 */
    private static String compressionSuffix(int compression) {
        switch (compression) {
            case 3:
                return "";
            case 2:
                return "（普通）";
            case 4:
                return "（无损）";
            case 0:
                return "（自定义）";
            case 1:
                return "（未知档）";
            default:
                return "（压缩 " + compression + "）";
        }
    }

    /** 尺寸编码 → 中文名，编码对照 ptp-pack.c:1478-1486，名称沿用佳能机身菜单的用词。 */
    private static String sizeName(int size) {
        switch (size) {
            case 0:
                return "大";
            case 1:
                return "中";
            case 2:
                return "小";
            case 5:
                return "中1";
            case 6:
                return "中2";
            case 0xe:
                return "小1";
            case 0xf:
                return "小2";
            case 0x10:
                return "小3";
            default:
                return null;
        }
    }

    /** 小端 u32。 */
    private static long u32(byte[] block, int offset) {
        return (block[offset] & 0xffL)
                | ((block[offset + 1] & 0xffL) << 8)
                | ((block[offset + 2] & 0xffL) << 16)
                | ((block[offset + 3] & 0xffL) << 24);
    }
}
