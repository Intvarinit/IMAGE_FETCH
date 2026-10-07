package com.imagefetch.ptp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Canon EOS 曝光参数（快门/光圈/ISO）的取值表与编解码。
 *
 * <p>编码值逐行取自 libgphoto2 {@code camlibs/ptp2/config.c} 的三张表：
 * {@code canon_shutterspeed[]}（GENERIC16TABLE(Canon_ShutterSpeed)）、
 * {@code canon_aperture[]}（Canon_Aperture）、{@code canon_isospeed[]}（Canon_ISO）。
 * 它们是 {@code struct deviceproptableu16}，即机上编码是 16 位，但写入时经
 * {@code 0x9110 EOS_SetDevicePropValueEx} 的 12 字节负载当作 u32 放（libgphoto2
 * 对 UINT16 只填低 2 字节、高位为 0，等价于 u32 小端写小值）。
 *
 * <p>对应的 Canon 属性码（ptp.h:2091-2100）：{@code 0xD101 Aperture}、
 * {@code 0xD102 ShutterSpeed}、{@code 0xD103 ISOSpeed}。注意 D101 是光圈、
 * D102 才是快门，两者不要对调。
 *
 * <p>读取：0xD1xx 一族的 EOS 属性没有同步读接口，值只能从 {@code 0x9116 GetEvent}
 * 的事件缓存里取（标准 {@code 0x1015 GetDevicePropValue} 对 0xD1xx 直接回 0x2005），
 * 取回的就是本类表里的编码值，故用 {@link #indexOfValue} 定位档位。
 * 本类只负责「表值 ↔ 显示文案」与「读数 → 表项」的换算。
 */
public final class CanonExposure {
    /** 可调曝光参数。 */
    public enum Setting {
        /** 快门速度：Canon 0xD102 / 标准 0x500d。 */
        SHUTTER_SPEED(0xD102, 0x500d, "快门"),
        /** 光圈：Canon 0xD101 / 标准 0x5007。 */
        APERTURE(0xD101, 0x5007, "光圈"),
        /** ISO：Canon 0xD103 / 标准 0x500f。 */
        ISO(0xD103, 0x500f, "ISO");

        /** Canon EOS 属性码，用于 0x9110 写入。 */
        public final int vendorProperty;
        /** 标准 PTP 属性码，用于 0x1015 读取。 */
        public final int standardProperty;
        /** 界面上的中文名。 */
        public final String label;

        Setting(int vendorProperty, int standardProperty, String label) {
            this.vendorProperty = vendorProperty;
            this.standardProperty = standardProperty;
            this.label = label;
        }
    }

    /** 一个可选值：{@code value} 为机上编码值，{@code label} 为显示文案。 */
    public static final class Entry {
        public final int value;
        public final String label;

        Entry(int value, String label) {
            this.value = value;
            this.label = label;
        }

        @Override public String toString() {
            return label;
        }
    }

    /** 快门速度表编码（canon_shutterspeed[]，config.c:2818）。 */
    private static final int[] SHUTTER_VALUES = {
            0x0000, 0x0004, 0x000c,
            0x0010, 0x0013, 0x0014, 0x0015, 0x0018,
            0x001b, 0x001c, 0x001d, 0x0020, 0x0023,
            0x0024, 0x0025, 0x0028, 0x002b, 0x002c,
            0x002d, 0x0030, 0x0033, 0x0034, 0x0035,
            0x0038, 0x003b, 0x003c, 0x003d, 0x0040,
            0x0043, 0x0044, 0x0045, 0x0048, 0x004b,
            0x004c, 0x004d, 0x0050, 0x0053, 0x0054,
            0x0055, 0x0058, 0x005b, 0x005c, 0x005d,
            0x0060, 0x0063, 0x0064, 0x0065, 0x0068,
            0x006b, 0x006c, 0x006d, 0x0070, 0x0073,
            0x0074, 0x0075, 0x0078, 0x007b, 0x007c,
            0x007d, 0x0080, 0x0083, 0x0084, 0x0085,
            0x0088, 0x008b, 0x008c, 0x008d, 0x0090,
            0x0093, 0x0094, 0x0095, 0x0098, 0x009b,
            0x009c, 0x009d, 0x00a0, 0x00a8,
    };

    /** 快门速度表文案，与 SHUTTER_VALUES 逐行对应。 */
    private static final String[] SHUTTER_LABELS = {
            "auto", "bulb", "bulb",
            "30", "25", "20.3", "20", "15",
            "13", "10", "10.3", "8", "6",
            "6.3", "5", "4", "3.2", "3",
            "2.5", "2", "1.6", "1.5", "1.3",
            "1", "0.8", "0.7", "0.6", "0.5",
            "0.4", "0.3", "0.3", "1/4", "1/5",
            "1/6", "1/6", "1/8", "1/10", "1/10",
            "1/13", "1/15", "1/20", "1/20", "1/25",
            "1/30", "1/40", "1/45", "1/50", "1/60",
            "1/80", "1/90", "1/100", "1/125", "1/160",
            "1/180", "1/200", "1/250", "1/320", "1/350",
            "1/400", "1/500", "1/640", "1/750", "1/800",
            "1/1000", "1/1250", "1/1500", "1/1600", "1/2000",
            "1/2500", "1/3000", "1/3200", "1/4000", "1/5000",
            "1/6000", "1/6400", "1/8000", "1/16000",
    };

    /** 光圈表（canon_aperture[]，config.c:6966）。 */
    private static final int[] APERTURE_VALUES = {
            0x0000, 0xffff, 0x00b0,
            0x0008, 0x000b, 0x000c, 0x000d, 0x0010, 0x0013, 0x0014, 0x0015,
            0x0018, 0x001b, 0x001c, 0x001d, 0x0020, 0x0023, 0x0024, 0x0025,
            0x0028, 0x002c, 0x002b, 0x002d, 0x0030, 0x0033, 0x0034, 0x0035,
            0x0038, 0x003b, 0x003c, 0x003d, 0x0040, 0x0043, 0x0044, 0x0045,
            0x0048, 0x004b, 0x004c, 0x004d, 0x0050, 0x0053, 0x0054, 0x0055,
            0x0058, 0x005b, 0x005c, 0x005d, 0x0060, 0x0063, 0x0064, 0x0065,
            0x0068, 0x006b, 0x006c, 0x006d, 0x0070,
    };

    private static final String[] APERTURE_LABELS = {
            "implicit auto", "auto", "auto",
            "1", "1.1", "1.2", "1.2", "1.4", "1.6", "1.8", "1.8",
            "2", "2.2", "2.5", "2.5", "2.8", "3.2", "3.5", "3.5",
            "4", "4.5", "4.5", "5", "5.6", "6.3", "6.7", "7.1",
            "8", "9", "9.5", "10", "11", "13", "13", "14",
            "16", "18", "19", "20", "22", "25", "27", "29",
            "32", "36", "38", "40", "45", "51", "54", "57",
            "64", "72", "76", "81", "91",
    };

    /** ISO 表（canon_isospeed[]，config.c:3091）。 */
    private static final int[] ISO_VALUES = {
            0xffff, 0x0028, 0x0030, 0x0038, 0x0040, 0x0043, 0x0045, 0x0048,
            0x004b, 0x004d, 0x0050, 0x0053, 0x0055, 0x0058, 0x005b, 0x005d,
            0x0060, 0x0063, 0x0065, 0x0068, 0x006b, 0x006d, 0x0070, 0x0073,
            0x0075, 0x0078, 0x007b, 0x007d, 0x0080, 0x0083, 0x0085, 0x0088,
            0x008b, 0x008d, 0x0090, 0x0093, 0x0095, 0x0098, 0x00a0, 0x00a8,
            0x00b0, 0x0000, 0x0001,
    };

    private static final String[] ISO_LABELS = {
            "Factory Default", "6", "12", "25", "50", "64", "80", "100",
            "125", "160", "200", "250", "320", "400", "500", "640",
            "800", "1000", "1250", "1600", "2000", "2500", "3200", "4000",
            "5000", "6400", "8000", "10000", "12800", "16000", "20000", "25600",
            "32000", "40000", "51200", "64000", "80000", "102400", "204800", "409600",
            "819200", "Auto", "Auto ISO",
    };

    private static final Map<Setting, List<Entry>> SELECTABLE = buildSelectable();

    private CanonExposure() {
    }

    /**
     * 界面上可逐档选择的取值（按表顺序，按文案去重）。
     *
     * <p>去重只消除文案完全相同的相邻档（表里为 1/2 档与 1/3 档各留一份编码，
     * 例如 {@code 1/6 = 0x004c} 与 {@code 0x004d}），保留首个编码。
     * ISO 的 {@code 0xffff Factory Default} 不进列表——它是「恢复出厂」而非一档感光度。
     */
    private static Map<Setting, List<Entry>> buildSelectable() {
        Map<Setting, List<Entry>> result = new LinkedHashMap<>();
        result.put(Setting.SHUTTER_SPEED, dedupe("快门", SHUTTER_VALUES, SHUTTER_LABELS));
        result.put(Setting.APERTURE, dedupe("光圈", APERTURE_VALUES, APERTURE_LABELS));
        List<Entry> iso = new ArrayList<>();
        // ISO 的「自动」档排在表末，挪到最前，让 +/− 沿数值单调（Auto → 6 → … → 819200）。
        iso.add(new Entry(0x0000, "Auto"));
        iso.add(new Entry(0x0001, "Auto ISO"));
        for (int i = 0; i < ISO_VALUES.length; i++) {
            if (ISO_VALUES[i] == 0xffff || ISO_VALUES[i] == 0x0000 || ISO_VALUES[i] == 0x0001) continue;
            iso.add(new Entry(ISO_VALUES[i], ISO_LABELS[i]));
        }
        result.put(Setting.ISO, Collections.unmodifiableList(iso));
        return Collections.unmodifiableMap(result);
    }

    private static List<Entry> dedupe(String settingName, int[] values, String[] labels) {
        // 表值与文案数量写死在源码里，长度不一致说明抄表抄错了，直接暴露。
        if (labels.length != values.length) {
            throw new IllegalStateException(
                    settingName + "表长度不一致：编码 " + values.length + " 条，文案 " + labels.length + " 条");
        }
        Map<String, Entry> unique = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            if (!unique.containsKey(labels[i])) {
                unique.put(labels[i], new Entry(values[i], labels[i]));
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    /** 界面上可选的取值列表（只读）。 */
    public static List<Entry> entries(Setting setting) {
        return SELECTABLE.get(setting);
    }

    /**
     * 按相机上报的允许值列表收窄取值表：沿用原表顺序，只留下 {@code value} 出现在
     * {@code allowed} 里的档。
     *
     * <p>相机上报的允许值列表来自 {@code 0xC18A EOS_AvailListChanged} 事件（见
     * transport 的事件缓存），本机型的机身支持的档位只是本表的一个子集（例如 M50 II 没有那么
     * 高的 ISO、也没有 f/91）。{@code allowed} 为 null/空、或没有任何一档命中时一律交回**原表**
     * ——绝不把空列表交给界面（否则档位行会变成不可选）。相机给的顺序不参与结果顺序，仍以本表为准。
     */
    public static List<Entry> restrict(Setting setting, int[] allowed) {
        List<Entry> full = entries(setting);
        if (allowed == null || allowed.length == 0) return full;
        List<Entry> result = new ArrayList<>();
        for (Entry entry : full) {
            if (containsValue(allowed, entry.value)) result.add(entry);
        }
        return result.isEmpty() ? full : Collections.unmodifiableList(result);
    }

    private static boolean containsValue(int[] values, int target) {
        for (int value : values) {
            if (value == target) return true;
        }
        return false;
    }

    /** 约定默认档（读取失败时用）：1/125、F4、ISO 100。 */
    public static int defaultIndex(Setting setting) {
        switch (setting) {
            case SHUTTER_SPEED:
                return indexOfLabel(setting, "1/125");
            case APERTURE:
                return indexOfLabel(setting, "4");
            default:
                return indexOfLabel(setting, "100");
        }
    }

    /** 按显示文案找档位下标；找不到返回 -1。 */
    public static int indexOfLabel(Setting setting, String label) {
        List<Entry> list = entries(setting);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).label.equals(label)) return i;
        }
        return -1;
    }

    /** 按机上编码值找档位下标；找不到返回 -1。 */
    public static int indexOfValue(Setting setting, int value) {
        List<Entry> list = entries(setting);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).value == value) return i;
        }
        return -1;
    }

    /** 档位显示文案：光圈加 F、ISO 加 ISO 前缀，快门直接用表内文案。 */
    public static String display(Setting setting, String label) {
        if (label == null || label.isEmpty()) return "—";
        switch (setting) {
            case APERTURE:
                return label.startsWith("F") ? label : "F" + label;
            case ISO:
                return "ISO " + label;
            default:
                return label;
        }
    }

    /**
     * 把标准 PTP 属性的原始读数换算成档位下标（读取成功时用它定位当前档）。
     * 无法对应到表内任何一档时返回 -1。
     *
     * @param raw 0x5007（F×100）、0x500d（1/10000 秒）、0x500f（原始 ISO）的读数
     */
    public static int matchStandard(Setting setting, long raw) {
        switch (setting) {
            case APERTURE: {
                double f = raw / 100.0;
                for (int i = 0; i < entries(setting).size(); i++) {
                    Entry entry = entries(setting).get(i);
                    double candidate = parseNumber(entry.label);
                    if (!Double.isNaN(candidate) && Math.abs(candidate - f) < 0.05) return i;
                }
                return -1;
            }
            case ISO: {
                for (int i = 0; i < entries(setting).size(); i++) {
                    Entry entry = entries(setting).get(i);
                    if (parseNumber(entry.label) == raw) return i;
                }
                return -1;
            }
            default: {
                double seconds = raw / 10000.0;
                if (seconds <= 0) return -1;
                String label = formatSeconds(seconds);
                int index = indexOfLabel(setting, label);
                if (index >= 0) return index;
                // 相机可能回一个表里没有的档（例如 1/3 档的近似），退一步按秒数就近匹配。
                return nearestBySeconds(setting, seconds);
            }
        }
    }

    /** 标准读数无法直接命中时的就近匹配（只看纯数值档，忽略 auto/bulb）。 */
    private static int nearestBySeconds(Setting setting, double seconds) {
        int best = -1;
        double bestDelta = Double.MAX_VALUE;
        List<Entry> list = entries(setting);
        for (int i = 0; i < list.size(); i++) {
            double candidate = secondsOfLabel(list.get(i).label);
            if (candidate <= 0) continue;
            double delta = Math.abs(Math.log(candidate) - Math.log(seconds));
            if (delta < bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }
        return best;
    }

    private static double secondsOfLabel(String label) {
        if (label.contains("/")) {
            String[] parts = label.split("/", 2);
            double denominator = parseNumber(parts[1]);
            return denominator <= 0 ? -1 : 1.0 / denominator;
        }
        return parseNumber(label);
    }

    /** 秒 → 表内文案风格（整数不带小数、小数至多一位、不足 1 秒用 1/N）。 */
    static String formatSeconds(double seconds) {
        if (seconds >= 1) {
            double rounded = Math.round(seconds * 10) / 10.0;
            if (rounded == Math.floor(rounded)) {
                return String.valueOf((long) rounded);
            }
            return String.format(Locale.ROOT, "%.1f", rounded);
        }
        return "1/" + Math.round(1 / seconds);
    }

    private static double parseNumber(String text) {
        if (text == null) return Double.NaN;
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException error) {
            return Double.NaN;
        }
    }
}
